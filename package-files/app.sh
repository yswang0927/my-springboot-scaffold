#!/bin/bash

# 获取当前脚本的绝对路径
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
PRG_DIR="$(cd -P "$(dirname "$PRG")" && pwd)"

# echo with colors
infoLog() {
  time=`date "+%Y-%m-%d %H:%M:%S"`
  echo -e "\e[39m [$time] : AIP-GATEWAY INFO    : $1 \e[0m"
}
successLog() {
  time=`date "+%Y-%m-%d %H:%M:%S"`
  echo -e "\e[32m [$time] : AIP-GATEWAY SUCCESS : $1 \e[0m"
}
warnLog() {
  time=`date "+%Y-%m-%d %H:%M:%S"`
  echo -e "\e[33m [$time] : AIP-GATEWAY WARNING : $1 \e[0m"
}
errorLog() {
  time=`date "+%Y-%m-%d %H:%M:%S"`
  echo -e "\e[31m [$time] : AIP-GATEWAY ERROR   : $1 \e[0m"
}

# 通用函数：检查命令是否存在
# 例如: if available "wget"; then echo "wget 已安装" fi
available() { command -v $1 >/dev/null; }
require() {
    local MISSING=''
    for TOOL in $*; do
        if ! available $TOOL; then
            MISSING="$MISSING $TOOL"
        fi
    done

    echo $MISSING
}
# Demo
#NEEDS=$(require curl unzip)
#if [ -n "$NEEDS" ]; then
#    echo "ERROR: The following tools are required but missing:"
#    for NEED in $NEEDS; do
#        echo "  - $NEED"
#    done
#    exit 1
#fi

#SUDO=
#if [ "$(id -u)" -ne 0 ]; then
#    if ! available sudo; then
#        error "This script requires superuser permissions. Please re-run as root."
#    fi
#    SUDO="sudo"
#fi

detect_os() {
  case "$(uname -s 2>/dev/null || true)" in
    Darwin) echo "Darwin" ;;
    Linux) echo "Linux" ;;
    *) echo "unsupported" ;;
  esac
}

detect_arch() {
  local arch="$(uname -m 2>/dev/null)"
  case "$arch" in
    x86_64|amd64) echo "x86_64" ;;
    arm64|aarch64) echo "aarch64" ;;
    *) echo "$arch" ;;
  esac
}

# 根据PID文件检测进程是否存在
check_process() {
  local pid_file="$1"
  if [ ! -f "$pid_file" ]; then
    return 1
  fi

  local pid = $(cat "$pid_file")
  if [ -z "$pid" ] || ! [[ "$pid" =~ ^[0-9]+$ ]]; then
    return 1
  fi

  if kill -0 "$pid" > /dev/null 2>&1; then
    return 0
  else
    return 1
  fi
}

os="$(detect_os)"
os_arch="$(detect_arch)"

APP_HOME="$PRG_DIR"
CONF_DIR="$APP_HOME/conf"
EMBED_JAVA_HOME="$APP_HOME/jdk"
APP_PID_FILE="$APP_HOME/run.pid"
LOGS_DIR="$APP_HOME/logs"
LOG_FILE="$LOGS_DIR/app.log"

# 应用包，搜索 app-<version>.jar包文件，获取最后一个
APP_JAR_PATTER="app-*.jar"
APP_JAR=$(ls "$APP_HOME"/lib/$APP_JAR_PATTERN 2>/dev/null | tail -n 1)
if [ -z "$APP_JAR" ]; then
  errorLog "jar file: '$APP_HOME/lib/$APP_JAR_PATTERN' not found."
  exit 1
fi

if [ ! -d "$LOGS_DIR" ]; then
  mkdir -p "$LOGS_DIR"
fi

prepare_jdk() {
  if [ ! -d "$EMBED_JAVA_HOME" ]; then
    # 搜索 jdk-linux-*.tar.gz 包文件，获取最后一个(tail -n 1)
    JDK_FILE_PATTERN="jdk-linux-*.tar.gz"
    JDK_FILE=$(ls "$APP_HOME"/$JDK_FILE_PATTERN 2>/dev/null | tail -n 1)
    if [ -f "$JDK_FILE" ]; then
      tar xzf $JDK_FILE -C $APP_HOME
    fi
  fi
}

check_java() {
  MIN_JAVA_VERSION=17

  prepare_jdk

  # Embed java command first
  JAVA_CMD="$EMBED_JAVA_HOME/bin/java"
  if [ -f "$JAVA_CMD" ]; then
    chmod +x "$JAVA_CMD"
  fi

  if [ ! -x "$JAVA_CMD" ]; then
    if [ -n "$JAVA_HOME" ]; then
      if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        # IBM's JDK on AIX
        JAVA_CMD="$JAVA_HOME/jre/sh/java"
      else
        JAVA_CMD="$JAVA_HOME/bin/java"
      fi
      if [ ! -x "$JAVA_CMD" ]; then
        errorLog "JAVA_HOME is set to an invalid directory: '$JAVA_HOME' , no 'java' command could be found in it."
        exit 1
      fi
    else
      JAVA_CMD=java
      if ! command -v java >/dev/null 2>&1
      then
        errorLog "JAVA_HOME is not set and no 'java' command could be found in your PATH."
        exit 1
      fi
    fi
  fi

  # 获取 Java 版本信息
  JAVA_VERSION=$($JAVA_CMD -version 2>&1 | awk -F '"' '/version/ {print $2}')
  # 检查 Java 版本是否低于 17
  if [[ "$JAVA_VERSION" =~ ^[0-9]+(\.[0-9]+)(.*)$ ]]; then
    MAJOR_VERSION=$(echo $JAVA_VERSION | cut -d '.' -f 1)
    if (( $(printf '%s\n' "$MAJOR_VERSION" | sort -V | head -n 1) < $MIN_JAVA_VERSION )); then
      errorLog "JDK version must be >=$MIN_JAVA_VERSION, current version: $JAVA_VERSION"
      exit 1
    fi
  else
    errorLog "Failed to get Java version: $JAVA_VERSION"
    exit 1
  fi
}

start() {
  mem_capacity=$(free -m | grep Mem | awk '{print int($2*0.5)}')
  if [ -z "$mem_capacity" ]; then
    mem_capacity=1024
  fi

  if check_process "$APP_PID_FILE"; then
    warnLog "App is already running"
  else
    check_java
    infoLog "Starting App..."
    nohup "$JAVA_CMD" -server \
      --add-opens=java.base/java.io=ALL-UNNAMED \
      --add-opens=java.base/java.lang=ALL-UNNAMED \
      --add-opens=java.base/java.lang.ref=ALL-UNNAMED \
      --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
      --add-opens=java.base/java.net=ALL-UNNAMED \
      --add-opens=java.base/java.nio=ALL-UNNAMED \
      --add-opens=java.base/java.nio.charset=ALL-UNNAMED \
      --add-opens=java.base/java.security=ALL-UNNAMED \
      --add-opens=java.base/java.text=ALL-UNNAMED \
      --add-opens=java.base/java.time=ALL-UNNAMED \
      --add-opens=java.base/java.util=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
      --add-opens=java.base/jdk.internal.access=ALL-UNNAMED \
      --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
      --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED \
      --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
      --add-opens=java.base/sun.nio.fs=ALL-UNNAMED \
      --add-opens=java.base/sun.security.ssl=ALL-UNNAMED \
      --add-opens=java.base/sun.security.util=ALL-UNNAMED \
      --add-opens=java.base/sun.net.dns=ALL-UNNAMED \
      --add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED \
      --add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
      -Xms${mem_capacity}m -Xmx${mem_capacity}m -XX:+UseG1GC \
      -XX:ReservedCodeCacheSize=256m -XX:InitialCodeCacheSize=256m \
      -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m -XX:MaxDirectMemorySize=2g \
      -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:InitiatingHeapOccupancyPercent=45 \
      -XX:ConcGCThreads=4 -XX:ParallelGCThreads=8 -XX:+UseStringDeduplication -XX:+AlwaysPreTouch -XX:+ParallelRefProcEnabled \
      -XX:+DisableExplicitGC -Xlog:gc*,safepoint,gc+age=info:file=${LOGS_DIR}/gc-%t.log:time,level,tid,tags:filecount=5,filesize=50m \
      -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOGS_DIR}/oom.dump \
      -Dlog.level=INFO -Dlog.target=file -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 \
      -Dapp.home="$APP_HOME" -Dconf.path="${CONF_DIR}" \
      -jar "$APP_JAR" --spring.config.additional-location="optional:file:${APP_HOME}/application.properties" > "$LOG_FILE" >/dev/null 2>&1 &
    echo $! > "$APP_PID_FILE"

    # 循环检测
    sleep 5
    max_loop=20
    while [ $max_loop -gt 0 ]; do
      if check_process "$APP_PID_FILE"; then
        break
      fi
      sleep 1
      max_loop=$((max_loop-1))
      sleep 2
    done

    if check_process "$APP_PID_FILE"; then
      successLog "App started successfully"
    else
      errorLog "App start failed, see log: $LOG_FILE"
    fi
  fi
}

stop() {
  if check_process "$APP_PID_FILE"; then
    infoLog "Stopping App..."
    kill "$(cat "$APP_PID_FILE")"
    sleep 2

    max_loop=20
    while [ $max_loop -gt 0 ]; do
      if check_process "$APP_PID_FILE"; then
        sleep 2
        max_loop=$((max_loop-1))
      else
        break
      fi
    done

    if check_process "$APP_PID_FILE"; then
      # kill -9 强制停止
      kill -9 "$(cat "$APP_PID_FILE")"
      sleep 2
      if check_process "$APP_PID_FILE"; then
        errorLog "App stop failed"
        return 1
      else
        successLog "App stopped"
        rm -f "$APP_PID_FILE"
        return 0
      fi
    else
      successLog "App stopped"
      rm -f "$APP_PID_FILE"
    fi
    return 0
  else
    warnLog "App is not running"
    return 1
  fi
}

restart() {
  stop
  sleep 2
  start
}

status() {
  if check_process "$APP_PID_FILE"; then
    successLog "App is running"
  else
    warnLog "App is not running"
  fi
}

case "$1" in
  start)
    start
    ;;
  stop)
    stop
    ;;
  restart)
    restart
    ;;
  status)
    status
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status}"
    exit 1
    ;;
esac

exit 0
