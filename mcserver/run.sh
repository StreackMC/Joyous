#!/bin/bash

# 检查环境
java -version >/dev/null 2>&1
if [ $? -ne 0 ]; then
  echo "错误: 未找到 Java 运行环境。"
  exit 127
fi
mvn -v >/dev/null 2>&1
if [ $? -ne 0 ]; then
  echo "错误: 未找到 Maven 构建工具。"
  exit 127
fi
if [ -f "./mcserver/server.jar" ]; then
  echo "找到服务器文件。"
else
  echo "错误: 未找到 mcserver/server.jar 文件。请确保该文件存在。"
  exit 127
fi

# 确保目标目录中只有最新的编译产物
echo "清理旧的编译产物..."
rm -rf ./target/Joyous-*.jar
rm -rf ./target/original-Joyous-*.jar

# 编译项目
echo "正在编译项目..."
mvn package -f ./pom.xml || { exit $?; }

# 清理旧插件
echo "清理旧插件..."
PLUGINS_DIR="./mcserver/plugins"
mkdir -p "${PLUGINS_DIR}"
rm -rf "${PLUGINS_DIR}"/SteackLib-*.jar
rm -rf "${PLUGINS_DIR}"/.paper-remapped/SteackLib-*.jar


# 复制新插件
echo "复制新插件..."
cp ./target/Joyous-*.jar "${PLUGINS_DIR}/"
rm -rf "${PLUGINS_DIR}"/Joyous-*-sources.jar

# 启动服务器
echo "启动服务器..."
cd ./mcserver
java -Xms1M -Xmx2048M -jar ./server.jar nogui
exit $?