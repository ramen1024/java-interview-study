#!/usr/bin/env bash
#
# 一键启动开发环境（后端 8081 + 前端 5173）
#
#   bash dev.sh                 # 起后端和前端，前台实时日志，Ctrl+C 一起停
#   bash dev.sh --init-db       # 先建库/建表（schema.sql 幂等）再启动
#   bash dev.sh --backend       # 只起后端
#   bash dev.sh --frontend      # 只起前端
#   bash dev.sh --restart       # 已在跑的服务也强制重启（默认是复用）
#   bash dev.sh --force         # 端口被别的进程占用时强制结束它
#   bash dev.sh --no-import     # 跳过启动时的 content/ 自动导入
#   bash dev.sh --timeout 600   # 后端启动等待上限（秒），默认 300
#
# 设计取舍：
#   1. 只结束"本次启动的服务"。已在运行且能响应 HTTP 的服务默认复用，
#      不会误杀你手动开的 Vite（Vite 只监听 ::1，探测必须用 localhost，
#      用 127.0.0.1 会得到连接失败并把它误判成残留进程）。
#   2. 清理按端口找人，不按 $! 找。mvn spring-boot:run 会 fork 出独立 JVM，
#      结束 Maven 进程后 8081 仍被占用，这正是 AGENTS.md 记的那个坑；
#      端口是唯一可靠的锚点。$! 只作补充（拿到 winpid 后连整棵进程树一起收）。
#   3. 服务输出的日志落在 logs/ 下（已在 .gitignore 里），前台用带前缀的
#      tail 流式展示，所以异常堆栈不会被吞掉。
#
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LOG_DIR="$ROOT/logs"
BACKEND_PORT=8081
FRONTEND_PORT=5173
DB_NAME=jis
DB_HOST_DEFAULT=localhost
DB_PORT_DEFAULT=3306
REDIS_HOST_DEFAULT=localhost
REDIS_PORT_DEFAULT=6379

WANT_BACKEND=1
WANT_FRONTEND=1
OPT_INIT_DB=0
OPT_RESTART=0
OPT_FORCE=0
OPT_NO_IMPORT=0
OPT_TIMEOUT=300

BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"

# 本次真正启动的服务，清理时只动这些；被复用的服务不碰
STARTED_PIDS=()
STARTED_PORTS=()
TAIL_PIDS=()
INSPECT_PID=""

if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_BLUE=$'\033[36m'; C_DIM=$'\033[2m'
else
    C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_DIM=""
fi

info() { printf '%s[信息]%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s[完成]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s[警告]%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die()  { printf '%s[错误]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

usage() {
    sed -n '3,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# ---------- 平台差异 ----------

is_windows() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

# 端口上正在 LISTENING 的进程号；端口空闲则输出空串
port_listener() {
    local port="$1"
    if is_windows; then
        netstat -ano 2>/dev/null \
            | grep -E ":${port}[[:space:]]" \
            | grep -i "LISTENING" \
            | awk '{print $NF}' \
            | head -1
    elif command -v lsof >/dev/null 2>&1; then
        lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null | head -1
    elif command -v fuser >/dev/null 2>&1; then
        fuser "${port}/tcp" 2>/dev/null | tr -d ' '
    fi
}

# Windows 上 /e/xxx 这种 MSYS 路径在资源管理器里没法直接用，转回盘符路径显示
log_dir_display() {
    if is_windows && command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$LOG_DIR"
    else
        printf '%s' "$LOG_DIR"
    fi
}

proc_name() {
    local pid="$1"
    if is_windows; then
        tasklist //FI "PID eq ${pid}" //FO CSV //NH 2>/dev/null \
            | head -1 \
            | awk -F'","' '{gsub(/"/, "", $1); print $1}'
    else
        ps -p "$pid" -o comm= 2>/dev/null
    fi
}

# 结束进程及其子进程。Windows 下要先把 MSYS 的 pid 换成 winpid，
# taskkill 认的是后者，直接喂 MSYS pid 会报"找不到进程"而静默失败。
#
# 下面两个守卫都是踩出来的：目标刚退出时 /proc/<pid>/winpid 可能查不到，
# 也可能查到别的进程；一旦撞上脚本自身，taskkill //T 会把收工流程就地杀掉，
# 端口再也释放不了，现场还完全看不出原因。所以既跳过自身 winpid，
# 又只认名字合理的进程。
SELF_WINPID=""
self_winpid() {
    if [ -z "$SELF_WINPID" ] && is_windows; then
        SELF_WINPID=$(cat "/proc/$$/winpid" 2>/dev/null || true)
    fi
    printf '%s' "$SELF_WINPID"
}

kill_tree() {
    local pid="$1" winpid name
    [ -z "$pid" ] && return 0
    winpid=$(cat "/proc/${pid}/winpid" 2>/dev/null || true)
    if is_windows && [ -n "$winpid" ]; then
        [ "$winpid" = "$(self_winpid)" ] && return 0
        name=$(proc_name "$winpid")
        printf '%s' "$name" | grep -qiE '^(bash|sh|dash|java|javaw|node|mvn|cmd)\.exe$' || return 0
        taskkill //PID "$winpid" //F //T >/dev/null 2>&1 || true
    else
        kill -TERM "$pid" 2>/dev/null || true
    fi
}

free_port() {
    local port="$1" pid
    pid=$(port_listener "$port")
    [ -z "$pid" ] && return 0
    if is_windows; then
        [ "$pid" = "$(self_winpid)" ] && return 0
        taskkill //PID "$pid" //F //T >/dev/null 2>&1 || true
    else
        kill -TERM "$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null || true
    fi
}

http_code() {
    local code
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$1" 2>/dev/null) || true
    [ -z "$code" ] && code=000
    printf '%s' "$code"
}

port_reachable() {
    (exec 3<>"/dev/tcp/$1/$2") >/dev/null 2>&1
}

# ---------- 端口占用判定 ----------
# 结果写入 INSPECT_STATE（free / reuse / kill / foreign）与 INSPECT_PID。
# 这两个值是全局变量而不是返回值：调用方若写成 $(inspect_port ...)，
# 函数会跑在子 shell 里，赋值传不回来，PID 与进程名就成了空串。
# 判定依赖 owner 正则：端口被占用时，进程名不匹配就是外人，绝不擅自结束
inspect_port() {
    local port="$1" url="$2" owner="$3" pid name
    INSPECT_PID=""
    pid=$(port_listener "$port")
    if [ -z "$pid" ]; then
        INSPECT_STATE=free
        return
    fi
    INSPECT_PID="$pid"
    name=$(proc_name "$pid")
    if ! printf '%s' "$name" | grep -qiE "$owner"; then
        INSPECT_STATE=foreign
        return
    fi
    if [ "$(http_code "$url")" != "000" ]; then
        INSPECT_STATE=reuse
    else
        INSPECT_STATE=kill
    fi
}

# 让端口进入可用状态；决定本次是否要启动该服务
# 结果写入 SHOULD_START
SHOULD_START=1
prepare_port() {
    local label="$1" port="$2" url="$3" owner="$4" state pid
    SHOULD_START=1
    inspect_port "$port" "$url" "$owner"
    state="$INSPECT_STATE"
    pid="$INSPECT_PID"
    case "$state" in
        free)
            ;;
        reuse)
            if [ "$OPT_RESTART" = 1 ]; then
                info "${label}已在 ${port} 运行，--restart 要求重启，先结束进程 $pid"
                kill_tree "$pid"
                free_port "$port"
                sleep 1
            else
                info "${label}已在 ${port} 运行且能响应，本次复用（要重启加 --restart）"
                SHOULD_START=0
            fi
            ;;
        kill)
            warn "${label}在 ${port} 上有僵死的残留进程（PID $pid $(proc_name "$pid")），先清理"
            kill_tree "$pid"
            free_port "$port"
            sleep 1
            ;;
        foreign)
            if [ "$OPT_FORCE" = 1 ]; then
                warn "${port} 被非本项目的进程 $pid（$(proc_name "$pid")）占用，--force 已强制结束"
                kill_tree "$pid"
                free_port "$port"
                sleep 1
            else
                die "${port} 被非本项目的进程 $pid（$(proc_name "$pid")）占用，请自行处理或用 --force 强制结束"
            fi
            ;;
    esac
}

# ---------- 体检 ----------

require_cmd() {
    local cmd="$1" hint="$2"
    command -v "$cmd" >/dev/null 2>&1 || die "找不到命令 $cmd，$hint"
}

db_mysql() {
    # 密码走 MYSQL_PWD，避免 mysql 打印"命令行传密码不安全"的警告干扰输出
    MYSQL_PWD="${JIS_DB_PASSWORD:-root}" \
        mysql -h "${JIS_DB_HOST:-$DB_HOST_DEFAULT}" \
              -P "${JIS_DB_PORT:-$DB_PORT_DEFAULT}" \
              -u "${JIS_DB_USERNAME:-root}" "$@"
}

check_dependencies() {
    local db_host="${JIS_DB_HOST:-$DB_HOST_DEFAULT}"
    local db_port="${JIS_DB_PORT:-$DB_PORT_DEFAULT}"
    # Redis 地址不支持环境变量：application.yml 里就是硬编码的 localhost:6379，
    # 这里跟着硬编码，免得给出一个实际不生效的"可覆盖"假象
    local redis_host="$REDIS_HOST_DEFAULT"
    local redis_port="$REDIS_PORT_DEFAULT"

    if [ "$WANT_BACKEND" = 1 ]; then
        port_reachable "$db_host" "$db_port" \
            || die "连不上 MySQL（${db_host}:${db_port}），先确认本机 MySQL 8 已启动"
        port_reachable "$redis_host" "$redis_port" \
            || die "连不上 Redis（${redis_host}:${redis_port}），先确认本机 Redis 已启动"
        ok "MySQL ${db_host}:${db_port} 与 Redis ${redis_host}:${redis_port} 均可连接"
    fi
}

check_database() {
    local exists
    if ! command -v mysql >/dev/null 2>&1; then
        warn "本机 PATH 里没有 mysql 客户端，跳过 ${DB_NAME} 库检查"
        return
    fi
    exists=$(db_mysql -N -B -e \
        "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='${DB_NAME}'" \
        2>/dev/null | tr -d '\r')
    if [ -n "$exists" ]; then
        return
    fi
    if [ "$OPT_INIT_DB" = 1 ]; then
        init_database
        return
    fi
    die "${DB_NAME} 库不存在，先执行：bash dev.sh --init-db"
}

init_database() {
    local schema="$ROOT/backend/src/main/resources/db/schema.sql"
    [ -f "$schema" ] || die "找不到建表脚本 $schema"
    command -v mysql >/dev/null 2>&1 || die "找不到 mysql 客户端，无法自动建库"
    info "执行 schema.sql（幂等，可重复跑）"
    db_mysql < "$schema" || die "建库失败，请检查 MySQL 账号密码（可用 JIS_DB_USERNAME/JIS_DB_PASSWORD 覆盖）"
    ok "${DB_NAME} 库已就绪"
}

# ---------- 启动与等待 ----------

wait_http_ready() {
    # $1 名称 $2 探测地址 $3 超时秒 $4 日志 $5 进程 pid $6 日志里的失败特征
    local name="$1" url="$2" timeout="$3" logfile="$4" pid="$5" fail_re="$6"
    local start=$SECONDS code
    while :; do
        code=$(http_code "$url")
        [ "$code" = "200" ] && return 0
        if grep -qE "$fail_re" "$logfile" 2>/dev/null; then
            warn "$name 启动报错"
            return 1
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            warn "$name 进程已退出"
            return 1
        fi
        if [ $((SECONDS - start)) -ge "$timeout" ]; then
            warn "$name 等待 ${timeout}s 仍未就绪"
            return 2
        fi
        sleep 2
    done
}

show_log_tail() {
    local logfile="$1" lines="${2:-40}"
    if [ -s "$logfile" ]; then
        printf '%s---------- %s 末尾 %s 行 ----------%s\n' \
            "$C_DIM" "$(basename "$logfile")" "$lines" "$C_RESET"
        tail -n "$lines" "$logfile"
        printf '%s----------------------------------------%s\n' "$C_DIM" "$C_RESET"
    fi
}

start_backend() {
    info "启动后端：mvn spring-boot:run（首次会下载依赖/编译，耐心等）"
    (
        cd "$ROOT/backend" || exit 1
        exec mvn spring-boot:run
    ) >"$BACKEND_LOG" 2>&1 &
    local pid=$!
    if ! wait_http_ready "后端" "http://localhost:${BACKEND_PORT}/v3/api-docs" \
            "$OPT_TIMEOUT" "$BACKEND_LOG" "$pid" \
            "APPLICATION FAILED TO START|BUILD FAILURE|Address already in use"; then
        show_log_tail "$BACKEND_LOG" 60
        return 1
    fi
    STARTED_PIDS+=("$pid")
    STARTED_PORTS+=("$BACKEND_PORT")
    ok "后端已就绪：http://localhost:${BACKEND_PORT}"
    return 0
}

start_frontend() {
    if [ ! -d "$ROOT/frontend/node_modules" ]; then
        info "前端依赖未安装，先执行 pnpm install"
        ( cd "$ROOT/frontend" && pnpm install ) || die "pnpm install 失败"
    fi
    info "启动前端：pnpm dev"
    (
        cd "$ROOT/frontend" || exit 1
        exec pnpm dev
    ) >"$FRONTEND_LOG" 2>&1 &
    local pid=$!
    if ! wait_http_ready "前端" "http://localhost:${FRONTEND_PORT}/" \
            120 "$FRONTEND_LOG" "$pid" \
            "EADDRINUSE|error when starting dev server"; then
        show_log_tail "$FRONTEND_LOG" 40
        return 1
    fi
    STARTED_PIDS+=("$pid")
    STARTED_PORTS+=("$FRONTEND_PORT")
    ok "前端已就绪：http://localhost:${FRONTEND_PORT}"
    return 0
}

# ---------- 日志流 / 收工 ----------

stream_logs() {
    local logfile="$1" prefix="$2"
    tail -n +1 -f "$logfile" 2>/dev/null | sed -u "s/^/${prefix} /" &
    TAIL_PIDS+=("$!")
}

cleanup() {
    local pid port try
    # 收工期间把信号改成忽略而不是恢复默认：慌乱中连按两下 Ctrl+C，
    # 第二下若落在这里会当场打断清理，端口就留在占用状态。
    trap '' INT TERM
    trap - EXIT
    [ "${CLEANED:-0}" = 1 ] && return 0
    CLEANED=1

    if [ ${#TAIL_PIDS[@]} -gt 0 ]; then
        for pid in "${TAIL_PIDS[@]}"; do
            kill "$pid" 2>/dev/null || true
        done
    fi
    if [ ${#STARTED_PIDS[@]} -eq 0 ] && [ ${#STARTED_PORTS[@]} -eq 0 ]; then
        return 0
    fi

    printf '\n'
    info "正在停止本次启动的服务"
    # 端口才是可靠锚点：mvn spring-boot:run 会 fork 出独立 JVM，只结束 Maven
    # 进程的话 8081 还占着。所以先按端口收到干净，再按进程树补掉 Maven 外壳；
    # 端口释放可能是异步的，多试几次再判定失败。
    for port in "${STARTED_PORTS[@]}"; do
        for try in 1 2 3 4 5; do
            [ -z "$(port_listener "$port")" ] && break
            free_port "$port"
            sleep 0.5
        done
    done
    for pid in "${STARTED_PIDS[@]}"; do
        kill_tree "$pid"
    done
    for port in "${STARTED_PORTS[@]}"; do
        pid=$(port_listener "$port")
        if [ -n "$pid" ]; then
            warn "端口 ${port} 仍被占用（PID $pid），可能需要手工结束"
        else
            ok "端口 ${port} 已释放"
        fi
    done
    printf '%s已停止。%s\n' "$C_DIM" "$C_RESET"
}

# 信号要走到这里，不能只靠 EXIT：Ctrl+C 之后如果不立刻退出，
# bash 会回到被中断的守护循环里，再吐一句"有服务已退出"，
# 把一次正常收工说成异常，顺序也乱。
on_signal() {
    cleanup
    exit 130
}

# ---------- 参数 ----------

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)       usage; exit 0 ;;
        --init-db)       OPT_INIT_DB=1 ;;
        --backend)       WANT_FRONTEND=0 ;;
        --frontend)      WANT_BACKEND=0 ;;
        --restart)       OPT_RESTART=1 ;;
        --force)         OPT_FORCE=1 ;;
        --no-import)     OPT_NO_IMPORT=1 ;;
        --timeout)       shift; OPT_TIMEOUT="${1:-}"
                         case "$OPT_TIMEOUT" in
                             ''|*[!0-9]*) die "--timeout 需要一个正整数秒数" ;;
                         esac ;;
        *)               die "未知参数：$1（用 --help 看用法）" ;;
    esac
    shift
done

[ "$WANT_BACKEND" = 1 ] || [ "$WANT_FRONTEND" = 1 ] || die "后端和前端都被排除了，没有可启动的服务"

mkdir -p "$LOG_DIR"

if [ "$OPT_NO_IMPORT" = 1 ]; then
    export JIS_AUTO_IMPORT=false
fi

printf '%sJava 面试学习站 —— 一键启动%s\n' "$C_BLUE" "$C_RESET"

if [ "$WANT_BACKEND" = 1 ]; then
    require_cmd java "装 JDK 21 后再来"
    require_cmd mvn "装 Maven 后再来"
fi
if [ "$WANT_FRONTEND" = 1 ]; then
    require_cmd node "装 Node 后再来"
    require_cmd pnpm "装 pnpm 后再来：npm i -g pnpm"
fi
require_cmd curl "Git Bash 自带 curl，检查一下 PATH"

if [ "$WANT_BACKEND" = 1 ]; then
    check_dependencies
fi

# --init-db 独立于服务选择：单独配 --frontend 时也应该真的建库
if [ "$OPT_INIT_DB" = 1 ]; then
    init_database
elif [ "$WANT_BACKEND" = 1 ]; then
    check_database
fi

if [ "$WANT_BACKEND" = 1 ]; then
    prepare_port "后端" "$BACKEND_PORT" \
        "http://localhost:${BACKEND_PORT}/v3/api-docs" 'java|javaw' || exit 1
    START_BACKEND=$SHOULD_START
else
    START_BACKEND=0
fi

if [ "$WANT_FRONTEND" = 1 ]; then
    prepare_port "前端" "$FRONTEND_PORT" \
        "http://localhost:${FRONTEND_PORT}/" 'node' || exit 1
    START_FRONTEND=$SHOULD_START
else
    START_FRONTEND=0
fi

trap cleanup EXIT
trap on_signal INT TERM

FAILED=0
if [ "$START_BACKEND" = 1 ]; then
    start_backend || FAILED=1
fi
if [ "$FAILED" = 0 ] && [ "$START_FRONTEND" = 1 ]; then
    start_frontend || FAILED=1
fi
[ "$FAILED" = 0 ] || exit 1

printf '\n%s┌──────────────────────────────────────────────┐%s\n' "$C_GREEN" "$C_RESET"
if [ "$WANT_FRONTEND" = 1 ]; then
    printf '%s│%s  前端      %shttp://localhost:%s/%s\n' "$C_GREEN" "$C_RESET" "$C_BLUE" "$FRONTEND_PORT" "$C_RESET"
fi
if [ "$WANT_BACKEND" = 1 ]; then
    printf '%s│%s  后端      %shttp://localhost:%s%s\n' "$C_GREEN" "$C_RESET" "$C_BLUE" "$BACKEND_PORT" "$C_RESET"
    printf '%s│%s  接口文档  %shttp://localhost:%s/swagger-ui.html%s\n' "$C_GREEN" "$C_RESET" "$C_BLUE" "$BACKEND_PORT" "$C_RESET"
fi
printf '%s└──────────────────────────────────────────────┘%s\n' "$C_GREEN" "$C_RESET"
printf '%s日志：%s\n' "$C_DIM" "$(log_dir_display)$C_RESET"
if [ "$START_BACKEND" = 1 ] || [ "$START_FRONTEND" = 1 ]; then
    printf '%s按 Ctrl+C 停止本次启动的服务%s\n\n' "$C_YELLOW" "$C_RESET"
fi

[ "$START_BACKEND" = 1 ]  && stream_logs "$BACKEND_LOG" "[后端]"
[ "$START_FRONTEND" = 1 ] && stream_logs "$FRONTEND_LOG" "[前端]"

# 只需盯着本次启动的进程；被复用的服务不归我们管，退出时也不能带走
WATCH_PIDS=("${STARTED_PIDS[@]}")
if [ ${#WATCH_PIDS[@]} -gt 0 ]; then
    while :; do
        for pid in "${WATCH_PIDS[@]}"; do
            if ! kill -0 "$pid" 2>/dev/null; then
                warn "有服务已退出，正在停止其余服务"
                exit 1
            fi
        done
        sleep 2
    done
else
    info "没有需要启动的服务，两个端口上的服务都是复用的，这里没有可守护的进程"
    exit 0
fi
