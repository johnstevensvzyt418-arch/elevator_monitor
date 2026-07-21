#!/bin/bash
# ============================================================
# 电梯监控系统 - 故障告警演示脚本 (Ubuntu/Linux 版本)
# 运行方式: chmod +x demo_alarms.sh && ./demo_alarms.sh
# 前置条件: Docker(Redis+MySQL), 后端(10008), Go前端(8080)
# ============================================================

set -o pipefail

# ============================================================
# 可配置路径 (按需修改)
# ============================================================
PROJECT_DIR="/home/ubuntu/elevator_monitor"
BACKEND_DIR="${PROJECT_DIR}/backend"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-SZTUbdi@1005}"
API="http://localhost:10008/api/v2/mnk"
TMP_DIR="/tmp"

# 确保项目目录存在
if [ ! -d "$PROJECT_DIR" ]; then
    echo "错误: 项目目录不存在: $PROJECT_DIR"
    echo "请修改脚本开头的 PROJECT_DIR 变量"
    exit 1
fi

# ============================================================
# ANSI 颜色
# ============================================================
C_BANNER='\033[36m'
C_ALARM='\033[33m'
C_PASS='\033[32m'
C_FAIL='\033[31m'
C_INFO='\033[90m'
C_WAIT='\033[33m'
C_STEP='\033[37m'
C_RESET='\033[0m'

# ============================================================
# 输出函数
# ============================================================
banner() {
    echo ""
    echo -e "${C_BANNER}$(printf '=%.0s' {1..62})${C_RESET}"
    echo -e "${C_BANNER}  $*${C_RESET}"
    echo -e "${C_BANNER}$(printf '=%.0s' {1..62})${C_RESET}"
}

section() {
    echo ""
    echo -e "${C_ALARM}  $(printf -- '-%.0s' {1..56})${C_RESET}"
    echo -e "${C_ALARM}  $*${C_RESET}"
    echo -e "${C_ALARM}  $(printf -- '-%.0s' {1..56})${C_RESET}"
}

step()   { echo -e "${C_STEP}  >> $*${C_RESET}"; }
ok()     { echo -e "${C_PASS}       [通过] $*${C_RESET}"; }
err()    { echo -e "${C_FAIL}       [失败] $*${C_RESET}"; }
info()   { echo -e "${C_INFO}       [i] $*${C_RESET}"; }
wait_msg(){ echo -e "${C_WAIT}  [... $* ...]${C_RESET}"; }

demo_pause() {
    local secs="$1"; shift
    [ -n "$*" ] && info "暂停 ${secs}s -- $*"
    sleep "$secs"
}

# ============================================================
# URL 编码 (使用 python3, Ubuntu 自带)
# ============================================================
urlencode() {
    python3 -c "import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))" "$1"
}

# ============================================================
# 协议发送 (同时显示 MQTT 原始报文格式)
# ============================================================
send_frame() {
    local devId="$1" ts="$2" target="$3" door="$4" dir="$5" floor="$6"
    local s1="0${target}00000000005100"
    local s2="0000000000${door}2100"
    local s3="${dir}${floor}000000005300"
    local s4="d000630000002200"
    local data="F${ts}/${devId}/${s1}${s2}${s3}${s4}"
    local timeStr="${ts:11:8}"

    # 显示 MQTT 原始报文 (模拟嵌入式设备通过 MQTT 发送的格式)
    echo -e "       ${C_INFO}[MQTT 报文] ${data}${C_RESET}"
    echo -e "       ${C_INFO}           设备=${devId} 时间=${timeStr} 长度=${#data}${C_RESET}"

    curl -s -X POST "$API" \
        --data-urlencode "data=$data" \
        --data-urlencode "time=$timeStr" \
        --data-urlencode "elevatorID=$devId" \
        -o /dev/null 2>/dev/null || err "HTTP 请求失败"
}

# ============================================================
# Redis 查询
# ============================================================
get_device_status() {
    local devId="$1"
    docker exec elevator-redis redis-cli HGET "elevator:status" "${devId,,}" 2>/dev/null
}

get_alarm_field() {
    local raw
    raw=$(get_device_status "$1")
    echo "$raw" | grep -oP '"Alarm":"([^"]*)"' | grep -oP '(?<="Alarm":")[^"]*' || echo ""
}

get_speed_field() {
    local raw
    raw=$(get_device_status "$1")
    echo "$raw" | grep -oP '"Speed":"([^"]*)"' | grep -oP '(?<="Speed":")[^"]*' || echo "?"
}

reset_device() {
    local ld ev="${1,,}"
    docker exec elevator-redis redis-cli DEL "elevator:speedtrack:$ldev" 2>/dev/null
    docker exec elevator-redis redis-cli DEL "elevator:leveling:$ldev" 2>/dev/null
    docker exec elevator-redis redis-cli DEL "elevator:cumul:$ldev" 2>/dev/null
    docker exec elevator-redis redis-cli HDEL "elevator:status" "$ldev" 2>/dev/null
    docker exec elevator-redis redis-cli HDEL "elevator:status" "${ldev}:alarm" 2>/dev/null
}

get_epoch_for_time() {
    local t="$1" h m s
    h=${t:0:2}; m=${t:3:2}; s=${t:6:2}
    date -d "today $h:$m:$s" +%s 2>/dev/null || date +%s
}

preset_speed_track() {
    local devId="$1" floor="$2" baseTime="$3" offsetSec="$4"
    local ld ev="${devId,,}" epoch
    epoch=$(($(get_epoch_for_time "$baseTime") + offsetSec))
    docker exec elevator-redis redis-cli HSET "elevator:speedtrack:$ldev" \
        "lastFloor" "$floor" "lastTimeEpoch" "$epoch" "lastSpeed" "0.0" 2>/dev/null
    info "速度追踪预设: 楼层=$floor, 时间戳=$epoch"
}

# ============================================================
# 确认告警触发
# ============================================================
assert_alarm() {
    local devId="$1" pattern="$2" label="$3" frontendId="$4"
    local alarm speed feInfo=""
    alarm=$(get_alarm_field "$devId")
    speed=$(get_speed_field "$devId")
    [ -n "$frontendId" ] && feInfo=" [前端: $frontendId]"

    if echo "$alarm" | grep -qE "$pattern"; then
        ok "$label$feInfo -- 告警=$alarm, 速度=$speed"
        return 0
    else
        err "$label$feInfo -- 告警=$alarm, 速度=$speed"
        return 1
    fi
}

# ============================================================
# 前端镜像推送 (推送到 0024002b 供前端实时显示)
# ============================================================
publish_to_0024002b() {
    local alarmName="$1" alarmCodes="$2" floorVal="${3:-01}" speedVal="${4:-0.00m/s}"
    local doorVal="${5:-00}" dirVal="${6:-00}" passengerVal="${7:-00}"
    local distanceVal="${8:-0.00米}" timesVal="${9:-0次}"

    # === 同时发送 MNK 帧到后端触发 AI 推理 (修复: AI 得分不显示) ===
    # 将楼层号转为 MNK 协议 hex 编码
    local hexFloor
    case "$floorVal" in
        01) hexFloor="05" ;; 02) hexFloor="09" ;; 03) hexFloor="0d" ;; 04) hexFloor="11" ;;
        *)  hexFloor="05" ;;
    esac
    local targetHex="08"
    local ts="2020/11/10 $(date +%H:%M:%S)"
    local s1="0${targetHex}00000000005100"
    local s2="0000000000${doorVal}2100"
    local s3="${dirVal}${hexFloor}000000005300"
    local s4="d000630000002200"
    local mnkFrame="F${ts}/0024002b/${s1}${s2}${s3}${s4}"

    curl -s -X POST "$API" \
        --data-urlencode "data=$mnkFrame" \
        --data-urlencode "time=$(date +%H:%M:%S)" \
        --data-urlencode "elevatorID=0024002b" \
        -o /dev/null 2>/dev/null
    info "已触发 AI 推理: 0024002b"

    # 显示即将推送到前端的 JSON 数据
    echo -e "       ${C_INFO}[前端推送] Device=0024002b Alarm=${alarmCodes} Floor=${floorVal} Speed=${speedVal} Door=${doorVal}${C_RESET}"

    curl -s -G "http://localhost:8080/api" \
        --data-urlencode "device=0024002b" \
        --data-urlencode "status=01" \
        --data-urlencode "floor=$floorVal" \
        --data-urlencode "toFloor=无" \
        --data-urlencode "direction=$dirVal" \
        --data-urlencode "door=$doorVal" \
        --data-urlencode "passenger=$passengerVal" \
        --data-urlencode "speed=$speedVal" \
        --data-urlencode "alarm=$alarmCodes" \
        --data-urlencode "runtime=" \
        --data-urlencode "distance=$distanceVal" \
        --data-urlencode "times=$timesVal" \
        -o /dev/null 2>/dev/null

    info "已推送到前端 0024002b -- $alarmName (告警=$alarmCodes)"
}

# ============================================================
# 显示 0024002b 实时状态 (来自嵌入式 MQTT 数据)
# ============================================================
show_0024002b_status() {
    local raw alarm speed floor door
    raw=$(docker exec elevator-redis redis-cli HGET "elevator:status" "0024002b" 2>/dev/null)
    if [ -n "$raw" ]; then
        alarm=$(echo "$raw" | grep -oP '"Alarm":"([^"]*)"' | grep -oP '(?<="Alarm":")[^"]*' || echo "")
        speed=$(echo "$raw" | grep -oP '"Speed":"([^"]*)"' | grep -oP '(?<="Speed":")[^"]*' || echo "?")
        floor=$(echo "$raw" | grep -oP '"Floor":"([^"]*)"' | grep -oP '(?<="Floor":")[^"]*' || echo "?")
        door=$(echo "$raw" | grep -oP '"Door":"([^"]*)"' | grep -oP '(?<="Door":")[^"]*' || echo "?")
        # 门状态解释: 00=关门到位 01=开门到位 02=关门中 03=开门中
        case "$door" in
            00) door="关门到位" ;; 01) door="开门到位" ;;
            02) door="关门中"   ;; 03) door="开门中"   ;; *) door="未知" ;;
        esac
        echo -e "       ${C_INFO}[0024002b 实时] 楼层=${floor} 速度=${speed} 门=${door} 告警=${alarm:-无}${C_RESET}"
    fi
}

# ============================================================
# 演示开始
# ============================================================
clear
banner "电梯监控系统 - 故障告警演示"
echo ""
echo "  时间     : $(date '+%Y-%m-%d %H:%M:%S')"
echo "  后端接口 : $API"
echo "  前端页面 : http://localhost:8080/show?id=0024002b"
echo ""
echo -e "  ${C_INFO}MQTT 报文格式: F + 19字节日期 + / + 8字节设备号 + / + 64字节HEX数据${C_RESET}"
echo -e "  ${C_INFO}示例: F2020/11/10 11:24:46/0024002b/000000000000517c...d00063000000220e${C_RESET}"
echo ""

# ============================================================
# 重启后端，清除告警冷却 (适配 Docker / 宿主机 两种部署)
# ============================================================
echo -e "  ${C_WAIT}=== 检查并重启后端 (清除告警冷却) ===${C_RESET}"

# 方式1: Docker 容器部署 → restart 容器
BACKEND_CONTAINER=$(docker ps --filter "publish=10008" --format "{{.Names}}" 2>/dev/null | head -1)
if [ -n "$BACKEND_CONTAINER" ]; then
    info "检测到 Docker 容器: $BACKEND_CONTAINER, 正在重启..."
    docker restart "$BACKEND_CONTAINER" > /dev/null 2>&1
    info "容器重启指令已发送, 等待后端就绪..."
    for i in $(seq 1 30); do
        if curl -s "http://localhost:10008/api/v2/status/0024002b" -o /dev/null 2>/dev/null; then
            ok "后端已就绪 (${i}s), 告警冷却已清除 (Docker restart)"
            break
        fi
        [ "$i" -eq 30 ] && { err "后端启动超时"; exit 1; }
        sleep 1
    done

# 方式2: 宿主机 Java 进程部署 → kill + 重启 JAR
elif pgrep -f "elevator-monitor" > /dev/null 2>&1; then
    OLD_PID=$(pgrep -f "elevator-monitor" | head -1)
    info "检测到宿主机进程 PID=$OLD_PID, 正在重启..."
    kill "$OLD_PID" 2>/dev/null
    sleep 3

    JAR_PATH="${BACKEND_DIR}/target/elevator-monitor-0.1.4-SNAPSHOT.jar"
    if [ ! -f "$JAR_PATH" ]; then
        err "JAR 文件不存在: $JAR_PATH"
        err "请先编译: cd $BACKEND_DIR && ./mvnw package -DskipTests"
        exit 1
    fi
    export JAVA_HOME MYSQL_PASSWORD
    DS_URL="jdbc:mysql://127.0.0.1:3307/elevator_monitor?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true"
    nohup "$JAVA_HOME/bin/java" -jar "$JAR_PATH" --spring.datasource.url="$DS_URL" > /tmp/elevator-backend.log 2>&1 &
    info "后端进程已启动 PID=$!"

    for i in $(seq 1 25); do
        if curl -s "http://localhost:10008/api/v2/status/0024002b" -o /dev/null 2>/dev/null; then
            ok "后端已就绪 (${i}s), 告警冷却已清除 (宿主机重启)"
            break
        fi
        [ "$i" -eq 25 ] && { err "后端启动超时"; exit 1; }
        sleep 1
    done

# 方式3: 后端已在运行，无需重启 (仅提示)
else
    if curl -s "http://localhost:10008/api/v2/status/0024002b" -o /dev/null 2>/dev/null; then
        info "后端已在运行。注意: 告警冷却可能未清除，若有告警不触发请手动重启后端容器"
    else
        err "后端未运行! 请先启动后端容器或进程"
        exit 1
    fi
fi

# ============================================================
# 清理 Redis: 只保留 0024002b
# ============================================================
info "清理 Redis 历史数据 (只保留 0024002b)..."
docker exec elevator-redis redis-cli HKEYS "elevator:status" 2>/dev/null | while read -r k; do
    k=$(echo "$k" | tr -d '\r')
    [ -z "$k" ] && continue
    [ "$k" = "0024002b" ] && continue
    docker exec elevator-redis redis-cli HDEL "elevator:status" "$k" 2>/dev/null
done

docker exec elevator-redis redis-cli KEYS "elevator:*" 2>/dev/null | while read -r k; do
    k=$(echo "$k" | tr -d '\r')
    [ -z "$k" ] && continue
    [ "$k" = "elevator:status" ] && continue
    echo "$k" | grep -q "0024002b" && continue
    docker exec elevator-redis redis-cli DEL "$k" 2>/dev/null
done
info "Redis 清理完成"

# ============================================================
# 预热: 为 0024002b 发送 12 帧 MNK 数据以触发 AI 推理
# AI 推理需要至少 10 个样本才能形成窗口并产生异常得分
# ============================================================
echo ""
echo -e "  ${C_WAIT}=== AI 推理预热 (为 0024002b 攒 12 帧) ===${C_RESET}"
info "AI 推理需要 10 个样本窗口, 正在发送 12 帧..."
for warm in $(seq 1 12); do
    warmTs="2020/11/10 08:$(printf '%02d' $((warm-1))):00"
    if [ $((warm % 2)) -eq 1 ]; then warmFloor="05"; else warmFloor="09"; fi
    send_frame "0024002b" "$warmTs" "8" "10" "30" "$warmFloor"
    sleep 0.4
done
info "预热完成, 等待 AI 推理窗口形成..."
sleep 4

aiResult=$(docker exec elevator-redis redis-cli HGET "elevator:ai_result" "0024002b" 2>/dev/null)
if [ -n "$aiResult" ]; then
    ok "AI 推理已就绪: 0024002b (窗口已形成, 前端将显示异常得分)"
else
    info "AI 推理窗口待形成, 得分将在演示过程中逐步出现"
fi

echo ""
echo "  前端告警指示灯对照:"
echo "    00-困人          01-超速运行      02-低速运行      03-冲顶"
echo "    04-蹲底          05-开门运行      06-开门载人运行  07-检修状态"
echo ""
echo "  演示目标: 8 个指示灯全部亮起 (03-冲顶 因协议限制通过 Redis 注入)"
echo ""

passCount=0
failCount=0
totalCount=0

# ============================================================
# Phase 1: Instant Alarms
# ============================================================
banner "第一阶段  即时告警 (6项测试, 约 45 秒)"

# ---- Alarm 1: FLOOR_JUMP ----
((totalCount++))
section "告警 1/8 : 楼层跳变 FLOOR_JUMP (严重)"
echo "  触发条件  : 楼层从 01F 跳到 04F (差值3层, 超过阈值2层)"
echo "  前端显示  : alarm_04 (蹲底)"
echo ""

D="demofj01"
reset_device "$D"
step "步骤1: 建立基准 -- 楼层 01F, 关门到位, 平层状态"
send_frame "$D" "2020/11/10 09:00:00" "1" "10" "30" "05"
demo_pause 2 "等待处理完成"

step "步骤2: 发送跳变 -- 楼层 04F (触发 FLOOR_JUMP + SPEED_ABNORMAL)"
send_frame "$D" "2020/11/10 09:00:02" "8" "10" "30" "11"
demo_pause 3 "等待告警评估"

if assert_alarm "$D" "FLOOR_JUMP" "FLOOR_JUMP" "alarm_04"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "楼层跳变" "FLOOR_JUMP,SPEED_ABNORMAL" "04" "4.20m/s" "00" "00" "01" "8.40米" "2次"
show_0024002b_status
demo_pause 3 "讲解间隙 -- 向观众解释此告警"

# ---- Alarm 2: DOOR_OPEN_RUNNING ----
((totalCount++))
section "告警 2/8 : 开门运行 DOOR_OPEN_RUNNING (严重)"
echo "  触发条件  : 电梯门未关闭(01) 同时处于上行状态(01)"
echo "  前端显示  : alarm_05 (开门运行) + alarm_06 (开门载人运行)"
echo "  说明      : 设置目标楼层(非无) → 判定有乘客 → alarm_06 同时亮"
echo ""

D="demodor2"
reset_device "$D"
step "发送: 门=开门到位 + 方向=上行 + 目标楼层=2F (有内召 → 有乘客)"
send_frame "$D" "2020/11/10 09:01:00" "2" "04" "34" "05"
demo_pause 3 "等待告警评估"

if assert_alarm "$D" "DOOR_OPEN_RUNNING" "DOOR_OPEN_RUNNING" "alarm_05"; then ((passCount++)); else ((failCount++)); fi

info "检查 alarm_06 (开门载人运行): 需 Passenger=01 且 Alarm 含 DOOR_OPEN_RUNNING"
raw=$(get_device_status "$D")
if echo "$raw" | grep -q '"Passenger":"01"'; then
    ok "alarm_06 (开门载人运行) -- Passenger=01 确认, 前端 alarm_06 应亮"
else
    err "alarm_06 (开门载人运行) -- Passenger 不为 01"
fi
publish_to_0024002b "开门运行+载人" "DOOR_OPEN_RUNNING" "01" "0.00m/s" "01" "01" "01" "0.00米" "1次"
show_0024002b_status
demo_pause 3 "讲解间隙 -- 确认 alarm_05 和 alarm_06 均亮"

# ---- Alarm 3: DIRECTION_MISMATCH ----
((totalCount++))
section "告警 3/8 : 方向不一致 DIRECTION_MISMATCH (警告)"
echo "  触发条件  : 方向=上行(01), 但楼层从 03F 降到 01F"
echo "  前端显示  : alarm_07 (检修状态/异常)"
echo ""

D="demodm03"
reset_device "$D"
step "步骤1: 建立基准 -- 楼层 03F, 方向上行"
send_frame "$D" "2020/11/10 09:02:00" "4" "10" "34" "0d"
demo_pause 2 "等待处理完成"

step "步骤2: 楼层降至 01F, 方向仍为上行 (矛盾触发告警)"
send_frame "$D" "2020/11/10 09:02:02" "1" "10" "34" "05"
demo_pause 3 "等待告警评估"

if assert_alarm "$D" "DIRECTION_MISMATCH" "DIRECTION_MISMATCH" "alarm_07"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "方向不一致" "DIRECTION_MISMATCH" "01" "2.80m/s" "00" "01" "00" "5.60米" "2次"
show_0024002b_status
demo_pause 3 "讲解间隙"

# ---- Alarm 4: SPEED_ABNORMAL ----
((totalCount++))
section "告警 4/8 : 超速运行 SPEED_ABNORMAL (严重)"
echo "  触发条件  : 3层楼 / 1秒 = 8.40 m/s (超过 3.0 m/s 上限)"
echo "  前端显示  : alarm_01 (超速运行)"
echo ""

D="demosa04"
reset_device "$D"
preset_speed_track "$D" "01" "09:03:00" -1
step "发送: 楼层 01F 到 04F, 方向上行 (速度 = 3层 x 2.8m / 1s = 8.40 m/s)"
send_frame "$D" "2020/11/10 09:03:00" "8" "10" "34" "11"
demo_pause 3 "等待告警评估"

if assert_alarm "$D" "SPEED_ABNORMAL" "SPEED_ABNORMAL" "alarm_01"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "超速运行" "SPEED_ABNORMAL" "04" "8.40m/s" "00" "01" "00" "8.40米" "1次"
show_0024002b_status
demo_pause 3 "讲解间隙"

# ---- Alarm 5: LOW_SPEED ----
((totalCount++))
section "告警 5/8 : 低速运行 LOW_SPEED (警告)"
echo "  触发条件  : 1层楼 / 100秒 = 0.028 m/s (低于 0.1 m/s 下限)"
echo "  前端显示  : alarm_02 (低速运行)"
echo ""

D="demols05"
reset_device "$D"
preset_speed_track "$D" "01" "09:04:00" -100
step "发送: 楼层 01F 到 02F, 方向上行 (速度 = 1层 x 2.8m / 100s = 0.028 m/s)"
send_frame "$D" "2020/11/10 09:04:00" "2" "10" "34" "09"
demo_pause 3 "等待告警评估"

if assert_alarm "$D" "LOW_SPEED" "LOW_SPEED" "alarm_02"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "低速运行" "LOW_SPEED" "02" "0.03m/s" "00" "01" "00" "2.80米" "1次"
show_0024002b_status
demo_pause 3 "讲解间隙 -- 准备进入定时告警阶段"

# ---- Special: OVER_TOP (Redis 注入) ----
((totalCount++))
section "补充演示 : 冲顶 OVER_TOP (严重, Redis 注入模拟)"
echo "  触发条件  : 电梯上行超过最高楼层 (floor > 30, 方向=上行)"
echo "  前端显示  : alarm_03 (冲顶)"
echo "  说明      : 当前协议仅支持1-4F, 本告警通过 Redis 直接注入演示"
echo ""

D_OT="demoovtop"
L_OT="${D_OT,,}"
reset_device "$D_OT"

# 构造 JSON (使用 python3 确保编码正确)
overTopJson=$(python3 -c "
import json
obj = {
    'Device': '$L_OT', 'Status': '01', 'Floor': '31', 'ToFloor': '无',
    'Direction': '01', 'Door': '00', 'Passenger': '00', 'Speed': '5.00m/s',
    'Alarm': 'OVER_TOP', 'Runtime': '', 'Distance': '0.00米', 'Times': '1次'
}
print(json.dumps(obj, ensure_ascii=False))
")

step "Redis 注入: HSET + PUBLISH"
tmpOT="$TMP_DIR/.demo_ot_tmp.json"
echo "$overTopJson" > "$tmpOT"

# 用 cat 管道避免 shell 转义
cat "$tmpOT" | docker exec -i elevator-redis redis-cli -x HSET "elevator:status" "$L_OT" 2>/dev/null
cat "$tmpOT" | docker exec -i elevator-redis redis-cli -x PUBLISH "elevator:status" 2>/dev/null
rm -f "$tmpOT"

# 自检
info "自检: 验证 OVER_TOP 注入结果..."
rawCheck=$(docker exec elevator-redis redis-cli HGET "elevator:status" "$L_OT" 2>/dev/null)
if echo "$rawCheck" | grep -q "OVER_TOP"; then
    ok "OVER_TOP 注入成功, 前端 alarm_03 (冲顶) 应亮起"
    ((passCount++))
else
    err "OVER_TOP 注入后验证失败! Redis 原始数据: $rawCheck"
    ((failCount++))
fi
demo_pause 3 "等待前端确认 alarm_03 亮灯"
publish_to_0024002b "冲顶" "OVER_TOP" "31" "5.00m/s" "00" "01" "00" "0.00米" "1次"
show_0024002b_status
demo_pause 3 "讲解间隙 -- 解释冲顶告警的触发逻辑"

# ============================================================
# Phase 2: Timed Alarms
# ============================================================
banner "第二阶段  定时告警 (3项测试, 约 70 秒)"
echo ""
echo "  同时启动 3 个计时器以节省演示时间。"
echo "  各告警阈值不同, 将在不同的时间点进行检查。"
echo ""

TS_BASE="2020/11/10"

# ---- Alarm 6: DOOR_OPEN_TOO_LONG (20s) ----
((totalCount++))
section "告警 6/8 : 门超时未关 DOOR_OPEN_TOO_LONG (警告, 阈值 20s)"
echo "  触发条件  : 电梯门持续开启超过 20 秒未关闭"
echo "  前端显示  : alarm_05 (开门运行)"
echo ""
D6="demodot6"
reset_device "$D6"
send_frame "$D6" "$TS_BASE 09:10:00" "1" "04" "30" "05"
info "计时器已启动: DOOR_OPEN_TOO_LONG -- 需等待 22s"
demo_pause 1 "短暂间隔"

# ---- Alarm 7: LEVELING_TIMEOUT (30s) ----
((totalCount++))
section "告警 7/8 : 平层超时 / 困人 ALARM_FIELD (警告, 阈值 30s)"
echo "  触发条件  : 电梯平层开门状态持续超过 30 秒 (困人检测)"
echo "  前端显示  : alarm_00 (困人)"
echo ""
D7="demoaf07"
reset_device "$D7"
send_frame "$D7" "$TS_BASE 09:10:00" "1" "04" "30" "05"
info "计时器已启动: LEVELING_TIMEOUT -- 需等待 32s"
demo_pause 1 "短暂间隔"

# ---- Alarm 8: LONG_IDLE (60s) ----
((totalCount++))
section "告警 8/8 : 长时间停滞 LONG_IDLE (警告, 阈值 60s)"
echo "  触发条件  : 电梯处于非平层状态超过 60 秒未移动"
echo "  前端显示  : alarm_07 (检修状态)"
echo ""
D8="demoli08"
reset_device "$D8"
send_frame "$D8" "$TS_BASE 09:10:00" "1" "10" "34" "05"
info "计时器已启动: LONG_IDLE -- 需等待 62s"

# ---- Wait for DOOR_OPEN_TOO_LONG (22s) ----
echo ""
wait_msg "等待 20s 达到 DOOR_OPEN_TOO_LONG 阈值"
sleep 20

echo ""
step "检查: 触发 DOOR_OPEN_TOO_LONG (已过 22s)"
send_frame "$D6" "$TS_BASE 09:10:24" "1" "04" "30" "05"
demo_pause 3 "等待告警评估"
if assert_alarm "$D6" "DOOR_OPEN_TOO_LONG" "DOOR_OPEN_TOO_LONG" "alarm_05"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "门超时未关" "DOOR_OPEN_TOO_LONG" "01" "0.00m/s" "01" "00" "00" "0.00米" "2次"

# ---- Wait for LEVELING_TIMEOUT (total 32s) ----
wait_msg "再等待 10s 达到 LEVELING_TIMEOUT 阈值"
sleep 10

echo ""
step "检查: 触发 LEVELING_TIMEOUT (已过 32s)"
send_frame "$D7" "$TS_BASE 09:10:34" "1" "04" "30" "05"
demo_pause 3 "等待告警评估"
if assert_alarm "$D7" "ALARM_FIELD|LEVELING_TIMEOUT" "ALARM_FIELD / LEVELING" "alarm_00"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "平层超时/困人" "LEVELING_TIMEOUT" "01" "0.00m/s" "01" "00" "01" "0.00米" "2次"

# ---- Wait for LONG_IDLE (total 62s) ----
wait_msg "再等待 28s 达到 LONG_IDLE 阈值"
sleep 28

echo ""
step "检查: 触发 LONG_IDLE (已过 62s)"
send_frame "$D8" "$TS_BASE 09:11:04" "1" "10" "34" "05"
demo_pause 3 "等待告警评估"
if assert_alarm "$D8" "LONG_IDLE" "LONG_IDLE" "alarm_07"; then ((passCount++)); else ((failCount++)); fi
publish_to_0024002b "长时间停滞" "LONG_IDLE" "01" "0.00m/s" "00" "01" "00" "0.00米" "2次"

# ============================================================
# Demo Report
# ============================================================
banner "演示报告"
echo ""

passColor="$C_PASS"
[ "$passCount" -ne "$totalCount" ] && passColor="$C_FAIL"
echo -e "  测试结果: ${passColor}${passCount} / ${totalCount} 通过${C_RESET}"
echo ""

echo -e "  ${C_BANNER}详细状态:${C_RESET}"
printf "  %s\n" "----------------------------------------------------------------------------"
printf "  %-20s %-6s %-9s %-26s %s\n" "告警名称" "级别" "前端灯" "触发条件" "结果"
printf "  %s\n" "----------------------------------------------------------------------------"

# 逐个设备检查并输出 (使用数组)
report_names=("FLOOR_JUMP" "DOOR_OPEN_RUNNING" "DIRECTION_MISMATCH" "SPEED_ABNORMAL" "LOW_SPEED" "OVER_TOP (注入)" "DOOR_OPEN_TOO_LONG" "LEVELING_TIMEOUT" "LONG_IDLE")
report_devs=("demofj01" "demodor2" "demodm03" "demosa04" "demols05" "demoovtop" "demodot6" "demoaf07" "demoli08")
report_levels=("严重" "严重" "警告" "严重" "警告" "严重" "警告" "警告" "警告")
report_fes=("alarm_04" "alarm_05" "alarm_07" "alarm_01" "alarm_02" "alarm_03" "alarm_05" "alarm_00" "alarm_07")
report_conds=("楼层跳变超过2层" "电梯开门时运行(+alarm_06载人)" "方向与楼层变化矛盾" "超速超过 3.0 m/s" "速度低于 0.1 m/s" "冲顶(协议限制,Redis模拟)" "开门超过20秒" "平层开门超30秒(困人)" "非平层停滞超60秒")

for i in "${!report_names[@]}"; do
    alarm=$(get_alarm_field "${report_devs[$i]}")
    speed=$(get_speed_field "${report_devs[$i]}")
    ldev="${report_devs[$i],,}"

    matchPat="${report_names[$i]}"
    [ "${report_names[$i]}" = "LEVELING_TIMEOUT" ] && matchPat="ALARM_FIELD|LEVELING_TIMEOUT"

    icon="[通过]"
    color="$C_PASS"
    if ! echo "$alarm" | grep -qE "$matchPat"; then
        icon="[失败]"
        color="$C_FAIL"
    fi

    printf "  %-20s %-6s %-9s %-26s " "${report_names[$i]}" "${report_levels[$i]}" "${report_fes[$i]}" "${report_conds[$i]}"
    echo -e "${color}${icon}${C_RESET}"
    info "       设备=$ldev, 速度=$speed, 告警=$alarm"
done

printf "  %s\n" "----------------------------------------------------------------------------"
echo ""

echo -e "  ${C_BANNER}前端快捷访问链接:${C_RESET}"
for i in "${!report_names[@]}"; do
    ldev="${report_devs[$i],,}"
    info "http://localhost:8080/show?id=$ldev  --  ${report_names[$i]}"
done

echo ""
echo -e "  ${C_WAIT}备注:${C_RESET}"
echo "    - 演示前已自动重启后端清除告警冷却 (300s cooldown in-memory)"
echo "    - alarm_03 (冲顶): 因协议仅支持1-4F, 通过 Redis 直接注入模拟演示"
echo "    - alarm_06 (开门载人): 由 DOOR_OPEN_RUNNING + 有乘客 自动触发"
echo "    - 所有设备号会被 MNK 协议解析器转为小写"

banner "演示结束 -- 谢谢观看"
echo ""

# ============================================================
# Grand Finale: 设备 0024002b 全灯亮起
# ============================================================
section "终场展示 : 设备 0024002b 全部告警灯同时亮起"
echo "  说明: 将全部8种告警注入 0024002b, 供前端统一确认"
echo ""

ALL_ALARMS="LEVELING_TIMEOUT,SPEED_ABNORMAL,LOW_SPEED,OVER_TOP,FLOOR_JUMP,DOOR_OPEN_RUNNING,DOOR_OPEN_TOO_LONG,LONG_IDLE,DEVICE_OFFLINE"

step "方式1: 通过 Go API 推送 (实时 WebSocket)"
publish_to_0024002b "全部告警终场" "$ALL_ALARMS" "99" "9.99m/s" "01" "01" "01" "9999.99米" "9999次"

step "方式2: 通过 Redis HSET 持久化"
finalJson=$(python3 -c "
import json
obj = {
    'Device': '0024002b', 'Status': '01', 'Floor': '99', 'ToFloor': 'ALL',
    'Direction': '01', 'Door': '01', 'Passenger': '01', 'Speed': '9.99m/s',
    'Alarm': '$ALL_ALARMS', 'Runtime': 'DEMO', 'Distance': '9999.99米', 'Times': '9999次'
}
print(json.dumps(obj, ensure_ascii=False))
")
tmpFile="$TMP_DIR/.demo_finale_tmp.json"
echo "$finalJson" > "$tmpFile"
cat "$tmpFile" | docker exec -i elevator-redis redis-cli -x HSET "elevator:status" "0024002b" 2>/dev/null
rm -f "$tmpFile"

ok "已发布! 前端 http://localhost:8080/show?id=0024002b 应显示8灯全亮"
info "前端确认: 00-困人 01-超速 02-低速 03-冲顶 04-蹲底 05-开门 06-开门载人 07-检修"
demo_pause 5 "终场确认 -- 全部8灯亮起"

# ============================================================
# 清理: 删除演示设备 (只保留 0024002b)
# ============================================================
info "清理演示设备 Redis 数据..."
docker exec elevator-redis redis-cli KEYS "elevator:*" 2>/dev/null | while read -r k; do
    k=$(echo "$k" | tr -d '\r')
    [ -z "$k" ] && continue
    [ "$k" = "elevator:status" ] && continue
    echo "$k" | grep -q "0024002b" && continue
    docker exec elevator-redis redis-cli DEL "$k" 2>/dev/null
done
docker exec elevator-redis redis-cli HKEYS "elevator:status" 2>/dev/null | while read -r k; do
    k=$(echo "$k" | tr -d '\r')
    [ -z "$k" ] && continue
    [ "$k" = "0024002b" ] && continue
    docker exec elevator-redis redis-cli HDEL "elevator:status" "$k" 2>/dev/null
done
info "清理完成, 仅保留 0024002b"

echo ""
