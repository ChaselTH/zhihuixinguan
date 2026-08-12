#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PAYLOAD_DIR="$SCRIPT_DIR/payload"
CHECKSUM_FILE="$SCRIPT_DIR/SHA256SUMS"
INSTALL_DIR="${INSTALL_DIR:-$HOME/ZhihuiXinguan}"
STAGING_DIR=""
BACKUP_DIR=""
UPGRADE=0
OLD_MOVED=0
DATA_MOVED=0
NEW_INSTALLED=0

fail() { echo "安装失败：$*" >&2; exit 1; }
section() { printf '\n==> %s\n' "$*"; }
safe_target() {
  local path="${1:-}"
  [[ -n "$path" && "$path" != "/" && "$path" != "." && "$path" != "$HOME" ]]
}
is_our_install() {
  local path="${1:-}"
  [[ -d "$path" && ! -L "$path" && -f "$path/.zhihui_xinguan_install" ]] || return 1
  [[ "$(head -n 1 -- "$path/.zhihui_xinguan_install" 2>/dev/null || true)" == "zhihui-xinguan" ]]
}
service_running() {
  local pid_file="$1/run/service.pid" pid=""
  [[ -f "$pid_file" ]] || return 1
  IFS= read -r pid < "$pid_file" || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null
}
cleanup_and_rollback() {
  local code=$?
  trap - EXIT
  if (( code != 0 && OLD_MOVED == 1 && NEW_INSTALLED == 0 )); then
    echo "升级未完成，正在恢复旧版本……" >&2
    if (( DATA_MOVED == 1 )) && [[ -d "$STAGING_DIR/data" && ! -e "$BACKUP_DIR/data" ]]; then
      mv -- "$STAGING_DIR/data" "$BACKUP_DIR/data" || true
      DATA_MOVED=0
    fi
    if [[ ! -e "$INSTALL_DIR" && -d "$BACKUP_DIR" ]]; then mv -- "$BACKUP_DIR" "$INSTALL_DIR" || true; fi
  fi
  if [[ -n "${STAGING_DIR:-}" && -d "$STAGING_DIR" ]]; then
    local parent=""; parent="$(cd "$(dirname "$STAGING_DIR")" && pwd)"
    case "$STAGING_DIR" in "$parent"/.zhihui-xinguan-install-*) rm -rf -- "$STAGING_DIR" ;; *) echo "异常临时目录未自动清理：$STAGING_DIR" >&2 ;; esac
  fi
  exit "$code"
}
trap cleanup_and_rollback EXIT

section "检查离线安装环境"
[[ -d "$PAYLOAD_DIR" ]] || fail "缺少 payload 目录，请从 U 盘完整复制安装包"
[[ -f "$CHECKSUM_FILE" ]] || fail "缺少 SHA256SUMS"
command -v sha256sum >/dev/null 2>&1 || fail "系统缺少 sha256sum"
command -v tar >/dev/null 2>&1 || fail "系统缺少 tar"
(cd "$SCRIPT_DIR" && sha256sum -c SHA256SUMS) || fail "安装包校验失败，请重新从 U 盘复制"

case "$(uname -m)" in
  aarch64|arm64) JDK_ARCHIVE="$PAYLOAD_DIR/microsoft-jdk-21.0.12-linux-aarch64.tar.gz" ;;
  x86_64|amd64) JDK_ARCHIVE="$PAYLOAD_DIR/microsoft-jdk-21.0.12-linux-x64.tar.gz" ;;
  *) fail "不支持当前 CPU 架构：$(uname -m)；安装包仅包含 aarch64/arm64 与 x86_64/amd64" ;;
esac
[[ -f "$JDK_ARCHIVE" ]] || fail "缺少当前 CPU 所需的离线 Java 运行环境"
[[ -f "$PAYLOAD_DIR/app.tar.gz" ]] || fail "缺少应用程序包"

case "$INSTALL_DIR" in /*) ;; *) INSTALL_DIR="$PWD/$INSTALL_DIR" ;; esac
safe_target "$INSTALL_DIR" || fail "安装目录不安全：$INSTALL_DIR"
INSTALL_BASENAME="$(basename "$INSTALL_DIR")"
[[ -n "$INSTALL_BASENAME" && "$INSTALL_BASENAME" != "." && "$INSTALL_BASENAME" != ".." ]] || fail "安装目录名称无效"
INSTALL_PARENT="$(dirname "$INSTALL_DIR")"
mkdir -p "$INSTALL_PARENT"
INSTALL_PARENT="$(cd "$INSTALL_PARENT" && pwd -P)"
INSTALL_DIR="$INSTALL_PARENT/$INSTALL_BASENAME"
if [[ -e "$INSTALL_DIR" ]]; then
  is_our_install "$INSTALL_DIR" || fail "目标已存在但不是智慧信管安装目录，拒绝覆盖：$INSTALL_DIR"
  service_running "$INSTALL_DIR" && fail "服务仍在运行；请回到启动窗口按 Ctrl+C，确认 ./start.sh status 显示已停止后重试"
  UPGRADE=1
  OLD_VERSION="$(tr -d '\r\n' < "$INSTALL_DIR/VERSION" 2>/dev/null || printf '未知')"
  section "检测到旧版本 V$OLD_VERSION，将原地升级并保留全部数据和密码"
fi

STAGING_DIR="$INSTALL_PARENT/.zhihui-xinguan-install-$$"
[[ "$STAGING_DIR" == "$INSTALL_PARENT"/.zhihui-xinguan-install-* && ! -e "$STAGING_DIR" ]] || fail "临时目录校验失败"
mkdir "$STAGING_DIR"
chmod 700 "$STAGING_DIR" 2>/dev/null || true

AVAILABLE_KB="$(df -Pk "$INSTALL_PARENT" | awk 'NR==2 {print $4}')"
if [[ "$AVAILABLE_KB" =~ ^[0-9]+$ ]] && (( AVAILABLE_KB < 1048576 )); then fail "安装磁盘剩余空间不足 1 GB"; fi

section "安装智慧信管应用"
tar -xzf "$PAYLOAD_DIR/app.tar.gz" -C "$STAGING_DIR"
mkdir -p "$STAGING_DIR/runtime/jdk" "$STAGING_DIR/data" "$STAGING_DIR/run"

section "安装匹配 CPU 架构的离线 Java 21"
tar -xzf "$JDK_ARCHIVE" -C "$STAGING_DIR/runtime/jdk" --strip-components=1
chmod +x "$STAGING_DIR/start.sh" "$STAGING_DIR/runtime/jdk/bin/java"
printf 'zhihui-xinguan\n' > "$STAGING_DIR/.zhihui_xinguan_install"
chmod 700 "$STAGING_DIR/data" "$STAGING_DIR/run" 2>/dev/null || true

if (( UPGRADE == 0 )); then
  mv -- "$STAGING_DIR" "$INSTALL_DIR"
  STAGING_DIR=""
  NEW_INSTALLED=1
else
  section "保留数据并原地替换旧程序"
  BACKUP_DIR="$INSTALL_PARENT/.zhihui-xinguan-backup-$(date +%Y%m%d%H%M%S)-$$"
  [[ "$BACKUP_DIR" == "$INSTALL_PARENT"/.zhihui-xinguan-backup-* && ! -e "$BACKUP_DIR" ]] || fail "升级备份目录校验失败"
  mv -- "$INSTALL_DIR" "$BACKUP_DIR"
  OLD_MOVED=1
  is_our_install "$BACKUP_DIR" || fail "旧安装移动后校验失败"
  rmdir -- "$STAGING_DIR/data"
  mv -- "$BACKUP_DIR/data" "$STAGING_DIR/data"
  DATA_MOVED=1
  mv -- "$STAGING_DIR" "$INSTALL_DIR"
  STAGING_DIR=""
  NEW_INSTALLED=1
  if [[ -d "$BACKUP_DIR" && ! -L "$BACKUP_DIR" && ! -e "$BACKUP_DIR/data" ]] && is_our_install "$BACKUP_DIR"; then
    rm -rf -- "$BACKUP_DIR" || echo "警告：新版本安装完成，但旧程序备份未能清理：$BACKUP_DIR" >&2
  else
    echo "警告：新版本安装完成；旧程序备份校验未通过，未自动清理：$BACKUP_DIR" >&2
  fi
fi

trap - EXIT
VERSION="$(tr -d '\r\n' < "$INSTALL_DIR/VERSION")"
section "$([[ "$UPGRADE" -eq 1 ]] && printf '升级完成' || printf '安装完成')：智慧信管 V$VERSION"
echo "安装目录：$INSTALL_DIR"
if (( UPGRADE == 1 )); then echo "已保留：全部月份数据、导入批次和管理员密码"; fi
echo
echo "启动命令："
echo "  cd \"$INSTALL_DIR\""
echo "  ./start.sh"
echo
echo "启动时询问端口，直接回车使用默认端口 2874。"
echo "查看状态：./start.sh status"
echo "关闭服务：回到启动窗口按 Ctrl+C"
echo "管理入口：http://<这台麒麟电脑的IP>:端口/admin"
