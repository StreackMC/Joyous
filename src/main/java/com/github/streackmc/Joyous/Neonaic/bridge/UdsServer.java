package com.github.streackmc.Joyous.Neonaic.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.github.streackmc.Joyous.jlogger;

/**
 * UDS（Unix Domain Socket）监听侧（PROTOCOL §10 / §13.1）。
 * <p>
 * JoyousPlugin 是**通信路径的建立方**：在 POSIX 上监听一个套接字文件，等待 Neonaic 主动连接。
 * 每接受一条连接即建立一个 {@link BridgeSession}，并按「JSON + LF」分帧收发。
 * <p>
 * 注意：
 * <ul>
 *   <li>平台差异只在「端点解析」这一处（{@link #isSupported()} / 绑定地址）；</li>
 *   <li>本类**拥有**套接字文件：启动前清理陈旧文件、关闭时删除自己创建的文件；</li>
 *   <li>Windows 上 Java 不支持 {@code AF_UNIX} 的命名管道语义，此时 {@link #isSupported()}
 *       返回 false，上层应回退到 HTTP 模式。</li>
 * </ul>
 *
 * @since 0.3.0
 */
public class UdsServer {

  /** 每条连接建立会话的工厂：由上层注入读写回调与自述信息 */
  public interface SessionFactory {
    BridgeSession create(Consumer<String> write, Consumer<String> teardown);
  }

  private final Path path;
  private final SessionFactory sessionFactory;

  private volatile ServerSocketChannel serverChannel;
  private volatile boolean running;

  public UdsServer(Path path, SessionFactory sessionFactory) {
    this.path = path;
    this.sessionFactory = sessionFactory;
  }

  /** 当前平台是否支持 AF_UNIX 套接字（Windows 返回 false） */
  public static boolean isSupported() {
    return !System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  public Path path() {
    return path;
  }

  public boolean running() {
    return running;
  }

  /**
   * 开始监听。
   *
   * @throws IOException 绑定失败（含平台不支持）
   */
  public void start() throws IOException {
    if (running) return;
    if (!isSupported()) {
      throw new IOException("当前平台不支持 AF_UNIX（请改用 HTTP 模式，或由插件侧提供命名管道实现）");
    }

    // 本端拥有该套接字文件：清理上次异常退出留下的陈旧文件
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      jlogger.warn("[NeonaicBridge] 无法删除陈旧套接字文件 %s：%s", path, e.getLocalizedMessage());
    }

    ServerSocketChannel channel;
    try {
      channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    } catch (UnsupportedOperationException e) {
      throw new IOException("当前 JVM/平台不支持 AF_UNIX：" + e.getLocalizedMessage(), e);
    }
    channel.bind(UnixDomainSocketAddress.of(path));
    this.serverChannel = channel;
    this.running = true;

    Thread acceptor = Thread.ofVirtual().name("Joyous-Neonaic-UDS-Accept").unstarted(this::acceptLoop);
    acceptor.start();
    jlogger.info("[NeonaicBridge] UDS 已监听：%s", path);
  }

  /** 停止监听并删除本端创建的套接字文件 */
  public void stop() {
    running = false;
    ServerSocketChannel channel = this.serverChannel;
    this.serverChannel = null;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // 关闭失败无碍
      }
    }
    try {
      Files.deleteIfExists(path);
      jlogger.info("[NeonaicBridge] UDS 已停止并清理：%s", path);
    } catch (IOException e) {
      jlogger.warn("[NeonaicBridge] 无法删除套接字文件 %s：%s", path, e.getLocalizedMessage());
    }
  }

  // ---- 内部 ----

  private void acceptLoop() {
    while (running) {
      SocketChannel client;
      try {
        ServerSocketChannel channel = this.serverChannel;
        if (channel == null) break;
        client = channel.accept();
      } catch (ClosedChannelException e) {
        break;
      } catch (Exception e) {
        if (!running) break;
        jlogger.warn("[NeonaicBridge] accept 失败：%s", e.getLocalizedMessage());
        continue;
      }
      Thread.ofVirtual().name("Joyous-Neonaic-UDS-Conn").start(() -> handleConnection(client));
    }
  }

  private void handleConnection(SocketChannel client) {
    Object writeLock = new Object();
    Consumer<String> write = text -> {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      synchronized (writeLock) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
          try {
            client.write(buf);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }
    };
    Consumer<String> teardown = why -> {
      try {
        client.close();
      } catch (IOException ignored) {
        // 忽略
      }
    };

    BridgeSession session;
    try {
      session = sessionFactory.create(write, teardown);
    } catch (Exception e) {
      jlogger.error("[NeonaicBridge] 创建会话失败：%s", e.getLocalizedMessage(), e);
      teardown.accept("创建会话失败");
      return;
    }

    session.beginHandshake();
    try {
      readLoop(client, session);
    } catch (IOException e) {
      jlogger.debug("[NeonaicBridge] UDS 读取中断：%s", e.getLocalizedMessage());
    } finally {
      session.transportClosed("对端关闭连接");
      teardown.accept("连接结束");
    }
  }

  /**
   * 读取循环：把字节流按 UTF-8 增量解码（正确处理跨 read 的多字节字符），
   * 再交给会话做分帧。
   * <p>
   * {@code in.compact()} 保留下一次读取前未被解码消费的字节（半字符），
   * 避免 UTF-8 多字节序列被 read 边界截断而损坏。
   */
  private void readLoop(SocketChannel client, BridgeSession session) throws IOException {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);

    ByteBuffer in = ByteBuffer.allocate(8192);
    CharBuffer out = CharBuffer.allocate(8192);

    while (true) {
      int n = client.read(in);
      if (n < 0) break; // EOF
      if (n == 0) continue;
      in.flip();

      while (in.hasRemaining()) {
        out.clear();
        CoderResult r = decoder.decode(in, out, false);
        out.flip();
        if (out.hasRemaining()) {
          session.acceptChunk(out.toString());
        }
        if (r.isOverflow()) continue;
        if (r.isError()) {
          r.throwException();
        }
      }
      // 保留未消费的半个多字节字符，供下次 read 拼接
      in.compact();
    }

    // 冲刷解码器中可能残留的字符
    out.clear();
    CoderResult tail = decoder.decode(ByteBuffer.allocate(0), out, true);
    if (tail.isError()) tail.throwException();
    out.flip();
    if (out.hasRemaining()) session.acceptChunk(out.toString());
  }
}
