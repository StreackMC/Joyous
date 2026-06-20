#!/bin/bash

# 检查环境
java -version >/dev/null 2>&1
if [ $? -ne 0 ]; then
  echo "错误: 未找到 Java 运行环境。"
  exit 127
fi
if [ -f "./mcserver/server.jar" ]; then
  echo "找到服务器文件。"
else
  echo "错误: 未找到 mcserver/server.jar 文件。请确保该文件存在。"
  exit 127
fi

# 启动服务器
echo "启动服务器..."
cd ./mcserver
java $@ -jar ./server.jar nogui
exit $?