package com.github.streackmc.Joyous.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * 按天切分的异步追加日志
 * <p>
 * 用于「需要留档、但不该拖慢主线程」的流水：抽卡、出售、兑换这类一笔一条的记录。
 * 布局与 Entroprix 的日志一致，便于统一备份与检索：
 * <pre>
 *   &lt;root&gt;/2026-11/15.txt
 * </pre>
 * 写入全部在一条守护线程上排队执行，调用方（通常是主线程）只做一次 {@code submit}；
 * 每行写完立刻 {@code flush}，所以服务端被强杀最多丢最后一行，不会丢掉整批。
 *
 * <h3>关闭</h3>
 * 模块 {@code onDisable} 里必须调 {@link #close()}：它会把队列排空再停线程。
 * 不注册 JVM shutdown hook——每行都已 flush，强杀只会丢最后一行，没必要为此增加重载时的钩子残留。
 *
 * @author Neonai
 * @since 0.4.0
 */
public final class AsyncDailyLog {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd");

  private final Path root;
  private final ExecutorService executor;

  /** 当前打开的当日文件；只在写线程里访问，volatile 只为可见性 */
  private volatile BufferedWriter writer;
  private volatile LocalDate currentDate;
  private volatile boolean closed;

  /**
   * @param rootDir    日志根目录（会被创建）
   * @param threadName 写线程名，方便在 jstack / 线程转储里认出来
   */
  public AsyncDailyLog(File rootDir, String threadName) {
    this.root = rootDir.toPath();
    this.executor = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, threadName);
      thread.setDaemon(true);
      return thread;
    });
  }

  /** 追加一行（异步；不抛异常，失败只记控制台） */
  public void write(@Nullable String line) {
    if (line == null || line.isEmpty() || closed) {
      return;
    }
    try {
      executor.execute(() -> append(line));
    } catch (Exception e) {
      // 线程池已关闭（关服竞态）：直接同步写一次，别把这一行丢了
      append(line);
    }
  }

  private void append(String line) {
    try {
      BufferedWriter out = writerFor(LocalDate.now());
      out.write(line);
      out.newLine();
      out.flush();
    } catch (Exception e) {
      jlogger.err("无法写入日志文件（%s）：%s", root, e.getLocalizedMessage(), e);
    }
  }

  /** 取当日 Writer；跨天或首次访问时切文件 */
  private BufferedWriter writerFor(LocalDate date) throws IOException {
    if (writer != null && date.equals(currentDate)) {
      return writer;
    }
    closeWriter();
    Path dir = root.resolve(date.format(MONTH));
    Files.createDirectories(dir);
    Path file = dir.resolve(date.format(DAY) + ".txt");
    writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    currentDate = date;
    return writer;
  }

  private void closeWriter() {
    BufferedWriter out = writer;
    writer = null;
    currentDate = null;
    if (out == null) {
      return;
    }
    try {
      out.flush();
      out.close();
    } catch (IOException e) {
      jlogger.err("无法关闭日志文件（%s）：%s", root, e.getLocalizedMessage(), e);
    }
  }

  /**
   * 排空队列并停止写线程
   * <p>
   * 幂等：重复调用无副作用（模块禁用与重载可能各触发一次）。
   */
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      // 排在已提交任务之后，保证「先全部写完再关文件」
      executor.execute(this::closeWriter);
      executor.shutdown();
      if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
