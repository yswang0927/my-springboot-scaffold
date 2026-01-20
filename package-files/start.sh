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

# 应用包，搜索 app-<version>.jar包文件，获取最后一个
APP_JAR_PATTER="app-*.jar"
APP_JAR=$(ls "$PRG_DIR/$APP_JAR_PATTER" 2>/dev/null | tail -n 1)
# 应用进程PID文件
APP_PID_FILE="$PRG_DIR/run.pid"

LOGS_DIR="$PRG_DIR/logs"
LOG_FILE="$LOGS_DIR/app.log"
if [ ! -d "$LOGS_DIR" ]; then
  mkdir -p "$LOGS_DIR"
fi


start() {
  mem_capacity=$(free -m | grep Mem | awk '{print int($2*0.5)}')
  if [ -z "$mem_capacity" ]; then
    mem_capacity=1024
  fi

  if check_process "$APP_PID_FILE"; then
    echo "App is already running"
  else
    echo "Starting App..."
    nohup java -server \
      --add-opens=java.base/java.lang=ALL-UNNAMED \
      --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
      --add-opens=java.base/java.net=ALL-UNNAMED \
      --add-opens=java.base/java.nio=ALL-UNNAMED \
      --add-opens=java.base/java.security=ALL-UNNAMED \
      --add-opens=java.base/java.util=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
      --add-opens=java.base/jdk.internal.access=ALL-UNNAMED \
      --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
      -XX:+UseG1GC -Xms${mem_capacity}m -Xmx${mem_capacity}m \
      -jar "$APP_JAR" --spring.config.additional-location="optional:file:${PRG_DIR}/application.properties" > "$LOG_FILE" >/dev/null 2>&1 &
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
      echo "App started successfully"
    else
      echo "App start failed, see log: $LOG_FILE"
    fi
  fi
}

stop() {
  if check_process "$APP_PID_FILE"; then
    echo "Stopping App..."
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
        echo "App stop failed"
        return 1
      else
        echo "App stopped"
        rm -f "$APP_PID_FILE"
        return 0
      fi
    else
      echo "App stopped"
      rm -f "$APP_PID_FILE"
    fi
    return 0
  else
    echo "App is not running"
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
    echo "App is running"
  else
    echo "App is not running"
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
