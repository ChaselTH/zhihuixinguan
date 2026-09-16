#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PAYLOAD_DIR="$SCRIPT_DIR/payload"
# Keep the tested PR0 installation path so upgrades retain existing users and business data.
INSTALL_DIR="${INSTALL_DIR:-$HOME/ZhihuiXinguan-PR0}"
STAGING_DIR=""
BACKUP_DIR=""
OLD_MOVED=0
NEW_INSTALLED=0
fail(){ printf '安装失败：%s\n' "$*" >&2; exit 1; }
section(){ printf '\n==> %s\n' "$*"; }
is_install(){ [[ -d "$1" && ! -L "$1" && -f "$1/.zhihui_xinguan_install" ]] && [[ "$(head -n 1 -- "$1/.zhihui_xinguan_install")" == "zhihui-xinguan" ]]; }
rollback(){
  local result=$?
  trap - EXIT
  if (( result != 0 )); then
    if (( OLD_MOVED == 1 && NEW_INSTALLED == 0 )) && [[ ! -e "$INSTALL_DIR" ]] && is_install "$BACKUP_DIR"; then
      mv -- "$BACKUP_DIR" "$INSTALL_DIR" || { printf '自动恢复失败，请保留备份并联系维护人：%s\n' "$BACKUP_DIR" >&2; exit "$result"; }
      printf '已恢复原安装目录：%s\n' "$INSTALL_DIR" >&2
    fi
    [[ -z "$STAGING_DIR" || ! -d "$STAGING_DIR" ]] || printf '未完成的暂存目录已保留，未删除任何旧版数据：%s\n' "$STAGING_DIR" >&2
  fi
  exit "$result"
}
trap rollback EXIT

section '校验离线安装包'
[[ -d "$PAYLOAD_DIR" && -f "$SCRIPT_DIR/SHA256SUMS" ]] || fail '请从 U 盘完整复制安装目录（包括 payload 和 SHA256SUMS）'
for command_name in sha256sum tar mktemp cp mv find; do command -v "$command_name" >/dev/null 2>&1 || fail "系统缺少 $command_name"; done
(cd "$SCRIPT_DIR" && sha256sum -c SHA256SUMS) || fail '安装包校验失败'
case "$(uname -m)" in
  aarch64|arm64) JDK_ARCHIVE="$PAYLOAD_DIR/microsoft-jdk-21.0.12-linux-aarch64.tar.gz" ;;
  x86_64|amd64) JDK_ARCHIVE="$PAYLOAD_DIR/microsoft-jdk-21.0.12-linux-x64.tar.gz" ;;
  *) fail "安装包不支持当前 CPU 架构：$(uname -m)" ;;
esac
[[ -f "$JDK_ARCHIVE" && -f "$PAYLOAD_DIR/app.tar.gz" ]] || fail '缺少应用或运行时文件'
for archive in "$PAYLOAD_DIR/app.tar.gz" "$JDK_ARCHIVE"; do
  tar -tzf "$archive" | awk 'BEGIN{bad=0} /^\//{bad=1} /(^|\/)\.\.(\/|$)/{bad=1} END{exit bad}' || fail '压缩包包含异常路径'
done

case "$INSTALL_DIR" in /*) ;; *) INSTALL_DIR="$PWD/$INSTALL_DIR" ;; esac
[[ "$INSTALL_DIR" != / && "$INSTALL_DIR" != "$HOME" && "$INSTALL_DIR" != "$PWD" ]] || fail '不能以根目录、用户目录或当前目录为安装目标'
INSTALL_BASENAME="$(basename -- "$INSTALL_DIR")"
[[ -n "$INSTALL_BASENAME" && "$INSTALL_BASENAME" != . && "$INSTALL_BASENAME" != .. ]] || fail '安装目录无效'
INSTALL_PARENT="$(dirname -- "$INSTALL_DIR")"
[[ -d "$INSTALL_PARENT" && ! -L "$INSTALL_PARENT" ]] || fail '安装父目录必须已经存在且不能是符号链接'
INSTALL_PARENT="$(cd -- "$INSTALL_PARENT" && pwd -P)"
INSTALL_DIR="$INSTALL_PARENT/$INSTALL_BASENAME"
[[ "$INSTALL_DIR" != "$HOME" && "$INSTALL_DIR" != / && ! -L "$INSTALL_DIR" ]] || fail '安装目标校验失败'
UPGRADE=0
if [[ -e "$INSTALL_DIR" ]]; then
  is_install "$INSTALL_DIR" || fail "目标不是智慧信管安装目录，拒绝覆盖：$INSTALL_DIR"
  if [[ -f "$INSTALL_DIR/run/service.pid" ]]; then
    IFS= read -r old_pid < "$INSTALL_DIR/run/service.pid" || true
    if [[ "${old_pid:-}" =~ ^[0-9]+$ ]] && kill -0 "$old_pid" 2>/dev/null; then fail '服务仍在运行，请在启动窗口按 Ctrl+C 后重试'; fi
  fi
  [[ -d "$INSTALL_DIR/data" && ! -L "$INSTALL_DIR/data" ]] || fail '旧数据目录缺失或为链接，停止升级'
  invalid_data="$(find "$INSTALL_DIR/data" ! -type f ! -type d -print -quit)"
  [[ -z "$invalid_data" ]] || fail "数据目录含链接或特殊文件，停止升级：$invalid_data"
  UPGRADE=1
fi

section "准备安装目标：$INSTALL_DIR"
AVAILABLE_KB="$(df -Pk "$INSTALL_PARENT" | awk 'NR==2 {print $4}')"
[[ "$AVAILABLE_KB" =~ ^[0-9]+$ ]] || fail '无法检查磁盘剩余空间'
(( AVAILABLE_KB >= 1048576 )) || fail '安装磁盘可用空间不足 1 GB'
STAGING_DIR="$(mktemp -d "$INSTALL_PARENT/.zhihui-xinguan-stage-XXXXXXXX")"
[[ -n "$STAGING_DIR" && "$STAGING_DIR" == "$INSTALL_PARENT"/.zhihui-xinguan-stage-* && -d "$STAGING_DIR" && ! -L "$STAGING_DIR" ]] || fail '暂存目录校验失败'
tar -xzf "$PAYLOAD_DIR/app.tar.gz" -C "$STAGING_DIR"
mkdir -p "$STAGING_DIR/runtime/jdk" "$STAGING_DIR/data" "$STAGING_DIR/run"
tar -xzf "$JDK_ARCHIVE" -C "$STAGING_DIR/runtime/jdk" --strip-components=1
[[ -f "$STAGING_DIR/app/zhihui-xinguan.jar" && -f "$STAGING_DIR/VERSION" && -f "$STAGING_DIR/start.sh" ]] || fail '应用内容不完整'
chmod +x "$STAGING_DIR/start.sh" "$STAGING_DIR/runtime/jdk/bin/java"
printf 'zhihui-xinguan\n' > "$STAGING_DIR/.zhihui_xinguan_install"
if (( UPGRADE == 1 )); then
  section '复制旧数据到暂存区；原程序和原数据尚未改动'
  cp -a -- "$INSTALL_DIR/data/." "$STAGING_DIR/data/"
fi

# Keep existing local settings on upgrades; a generic package contains no credentials.
if (( UPGRADE == 1 )) && [[ -e "$INSTALL_DIR/bootstrap.local.properties" || -L "$INSTALL_DIR/bootstrap.local.properties" ]]; then
  [[ -f "$INSTALL_DIR/bootstrap.local.properties" && ! -L "$INSTALL_DIR/bootstrap.local.properties" ]] || fail '旧初始化配置不是普通文件，停止升级'
  cp -- "$INSTALL_DIR/bootstrap.local.properties" "$STAGING_DIR/bootstrap.local.properties"
elif [[ -e "$PAYLOAD_DIR/bootstrap.local.properties" || -L "$PAYLOAD_DIR/bootstrap.local.properties" ]]; then
  [[ -f "$PAYLOAD_DIR/bootstrap.local.properties" && ! -L "$PAYLOAD_DIR/bootstrap.local.properties" ]] || fail '离线初始化配置不是普通文件'
  cp -- "$PAYLOAD_DIR/bootstrap.local.properties" "$STAGING_DIR/bootstrap.local.properties"
fi
if [[ -f "$STAGING_DIR/bootstrap.local.properties" ]]; then chmod 600 "$STAGING_DIR/bootstrap.local.properties"; fi

section '验证离线 Java 并检查数据迁移'
"$STAGING_DIR/runtime/jdk/bin/java" -version || fail '离线 Java 无法运行，原版本保持不变'
"$STAGING_DIR/runtime/jdk/bin/java" -Dfile.encoding=UTF-8 -Xmx768m -cp "$STAGING_DIR/app/zhihui-xinguan.jar:$STAGING_DIR/app/lib/*" Main --root "$STAGING_DIR" --data-root "$STAGING_DIR/data" --migrate-only || fail '数据迁移验证未通过，原版本保持不变'

if (( UPGRADE == 1 )); then
  BACKUP_DIR="$INSTALL_PARENT/.zhihui-xinguan-backup-$(date +%Y%m%d%H%M%S)-$$"
  [[ "$BACKUP_DIR" == "$INSTALL_PARENT"/.zhihui-xinguan-backup-* && ! -e "$BACKUP_DIR" ]] || fail '备份目标异常'
  section "保留完整旧版备份：$BACKUP_DIR"
  mv -- "$INSTALL_DIR" "$BACKUP_DIR"
  OLD_MOVED=1
fi
[[ ! -e "$INSTALL_DIR" ]] || fail '安装目标意外出现，停止替换'
mv -- "$STAGING_DIR" "$INSTALL_DIR"
STAGING_DIR=""
NEW_INSTALLED=1
trap - EXIT
VERSION="$(tr -d '\r\n' < "$INSTALL_DIR/VERSION")"
section "智慧信管 V$VERSION 安装完成"
printf '已有账号及密码保持不变。首次空库启动需本地 bootstrap.local.properties；通用包只提供空白示例。\n'
printf '安装目录：%s\n' "$INSTALL_DIR"
[[ -z "$BACKUP_DIR" ]] || printf '旧版和全部旧数据备份：%s（不会自动删除）\n' "$BACKUP_DIR"
printf '\n这是联调测试版，默认沿用 ZhihuiXinguan-PR0 目录以保留原测试数据。\n启动：cd "%s" && ./start.sh\n默认端口 2874，可在启动时另选；Ctrl+C 关闭服务。\n查看状态：./start.sh status\n' "$INSTALL_DIR"
