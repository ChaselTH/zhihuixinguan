#!/usr/bin/env bash
set -euo pipefail
umask 077

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="$(tr -d '\r\n' < "$APP_DIR/VERSION")"
DEFAULT_PORT="2874"
PID_FILE="$APP_DIR/run/service.pid"

valid_port() { [[ "${1:-}" =~ ^[0-9]+$ ]] && (( 10#$1 >= 1024 && 10#$1 <= 65535 )); }
port_in_use() {
  local candidate="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|[:.])${candidate}$"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|[:.])${candidate}$"
  else
    return 1
  fi
}
service_running() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid=""
  IFS= read -r pid < "$PID_FILE" || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null
}
show_status() {
  if service_running; then
    echo "服务状态：运行中（PID $(<"$PID_FILE")）"
  else
    echo "服务状态：已停止"
  fi
}

if [[ "${1:-}" == "status" ]]; then show_status; exit 0; fi
if service_running; then echo "服务已经在运行，请先回到原启动窗口按 Ctrl+C。" >&2; show_status; exit 1; fi

PORT="${PORT:-}"
while true; do
  if [[ -z "$PORT" ]]; then
    read -r -p "请输入网页服务端口 [$DEFAULT_PORT]：" PORT
    PORT="${PORT:-$DEFAULT_PORT}"
  fi
  if ! valid_port "$PORT"; then echo "端口必须是 1024-65535 的整数。" >&2; PORT=""; continue; fi
  if port_in_use "$PORT"; then echo "端口 $PORT 已被占用，请选择其他端口。" >&2; PORT=""; continue; fi
  break
done

[[ -x "$APP_DIR/runtime/jdk/bin/java" ]] || { echo "缺少离线 Java 运行环境，请重新执行 install.sh。" >&2; exit 1; }
[[ -f "$APP_DIR/app/zhihui-xinguan.jar" ]] || { echo "缺少应用程序文件，请重新执行 install.sh。" >&2; exit 1; }
mkdir -p "$APP_DIR/run" "$APP_DIR/data"
chmod 700 "$APP_DIR/run" "$APP_DIR/data" 2>/dev/null || true

cleanup() {
  local code=$?
  trap - EXIT
  if [[ -n "${JAVA_PID:-}" && -f "$PID_FILE" && ! -L "$PID_FILE" ]]; then
    local recorded_pid=""
    IFS= read -r recorded_pid < "$PID_FILE" || true
    if [[ "$recorded_pid" == "$JAVA_PID" ]]; then rm -- "$PID_FILE"; fi
  fi
  echo
  echo "服务状态：已停止"
  exit "$code"
}
trap cleanup EXIT
echo
echo "智慧信管 V$VERSION"
echo "服务状态：启动中"
echo "首页：http://127.0.0.1:$PORT"
echo "登录入口：http://127.0.0.1:$PORT/login"
echo "局域网访问：http://<这台麒麟电脑的IP>:$PORT"
echo

"$APP_DIR/runtime/jdk/bin/java" \
  -Dfile.encoding=UTF-8 \
  -Xms128m -Xmx768m \
  -cp "$APP_DIR/app/zhihui-xinguan.jar:$APP_DIR/app/lib/*" \
  Main --root "$APP_DIR" --data-root "$APP_DIR/data" --port "$PORT" &
JAVA_PID=$!
printf '%s\n' "$JAVA_PID" > "$PID_FILE"
chmod 600 "$PID_FILE" 2>/dev/null || true
stop_child() {
  trap '' INT TERM
  kill -TERM "$JAVA_PID" 2>/dev/null || true
  wait "$JAVA_PID" || true
}
trap stop_child INT TERM
wait "$JAVA_PID"
