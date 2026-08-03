#!/bin/bash
# ============================================================
# 困人告警模拟脚本 v3 — 设备 0024002b （4F→2F + AI异常）
#
# 用法:
#   chmod +x simulate_trapped.sh
#   ./simulate_trapped.sh                      # 默认 localhost:10008
#   ./simulate_trapped.sh 192.168.1.100:10008  # 指定后端地址
#
# 模拟流程:
#   Pre-clear      : 清除旧告警标记
#   Phase-Move(4s) : 4F 关门下行 → 3F → 2F 平层到达
#   Phase-Trap(20s): 2F 开门中 + 有乘客 → 困人计时(5s) → 告警
#                    发送 20+ 条报文供 AI 收集≥10 样本推理
#   Phase-Open     : 开门到位 + 清除内招 → 困人/乘客解除
# ============================================================

set -e

BACKEND_HOST="${1:-localhost:10008}"
BACKEND_URL="http://${BACKEND_HOST}/api/v2/mnk"
DEVICE_ID="0024002b"
INTERVAL_SEC="${2:-1}"

# ==== seg1 内招/目标楼层 ====
SEG1_TARGET2="020000000000517c"
SEG1_NONE="000000000000517c"

# ==== seg2 开关门 ====
SEG2_CLOSED="00000000001021b9"
SEG2_TRANSITION="00000000000021b9"
SEG2_OPEN="00000000000421b9"

# ==== seg3 运行方向+楼层 ====
SEG3_4F_STOP="30110000000053c3"
SEG3_4F_DOWN="35110000000053c3"
SEG3_3F_DOWN="350d0000000053c3"
SEG3_2F_DOWN="35090000000053c3"
SEG3_2F_STOP="30090000000053c3"
SEG3_1F_STOP="30050000000053c3"

# ==== seg4 ====
SEG4="d00063000000220e"

MOVE_MSGS=7
TRAP_SECS=15
OPEN_SECS=8
PRECLEAR_MSGS=1
MOVE_END_COUNT=$((PRECLEAR_MSGS + MOVE_MSGS))

# ============================================================
# Pre-clear: 4F平层+有乘客+关门 → AI特征与Move-1一致
# curFloor=4F ≠ targetFloor=2F → 困人计时不启动
# ============================================================
NOW=$(date +"%Y/%m/%d %H:%M:%S")
TIME_ONLY=$(date +"%H:%M:%S")
NORMAL_DATA="F${NOW}/${DEVICE_ID}/${SEG1_TARGET2}${SEG2_CLOSED}${SEG3_4F_STOP}${SEG4}"

echo "[Pre] 清除旧告警标记..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${BACKEND_URL}" \
    --data-urlencode "data=${NORMAL_DATA}" \
    --data-urlencode "time=${TIME_ONLY}" \
    --data-urlencode "elevatorID=${DEVICE_ID}" \
    --connect-timeout 3 --max-time 5 2>/dev/null || echo "000")
if [ "$HTTP_CODE" = "200" ]; then echo "[Pre] ✓ 已清除"; else echo "[Pre] ✗ 失败 HTTP ${HTTP_CODE}"; fi
sleep 1

# ============================================================
# Header
# ============================================================
echo "╔══════════════════════════════════════════════════════════╗"
echo "║     🛗 困人告警模拟 v3 — 4F→2F + AI异常检测            ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  设备: ${DEVICE_ID}  后端: ${BACKEND_URL}  间隔: ${INTERVAL_SEC}s"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  Move  ~7s  : 4F平层→下行→3F→2F到达(供AI采集正常样本)  ║"
echo "║  Trap ~15s  : 2F开门中+有乘客 → 困人(5s) → AI得分↑    ║"
echo "║  Open   ~   : 开门到位+无内招 → 困人解除 乘客离开       ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo "  ★ AI窗口=10样本, Move期采集~8条, Trap期逐步替换→得分攀升"
echo ""

COUNT=0
START_TIME=$(date +%s)
TRAP_START=0

while true; do
    COUNT=$((COUNT + 1))
    NOW=$(date +"%Y/%m/%d %H:%M:%S")
    TIME_ONLY=$(date +"%H:%M:%S")
    ELAPSED=$(($(date +%s) - START_TIME))

    # ==== 选择当前阶段 ====
    if [ $COUNT -le $PRECLEAR_MSGS ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_4F_STOP"
        PHASE="Pre"; HINT="🔧 预清理(4F有乘客, 与Move特征一致)"
    elif [ $COUNT -le $((PRECLEAR_MSGS + 1)) ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_4F_STOP"
        PHASE="Move"; HINT="🛑 4F平层, 内招2F | 门:关门到位 | AI采集中"
    elif [ $COUNT -le $((PRECLEAR_MSGS + 2)) ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_4F_DOWN"
        PHASE="Move"; HINT="🚀 4F 关门下行 → 目标2F | 门:关门到位"
    elif [ $COUNT -le $((PRECLEAR_MSGS + 3)) ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_4F_DOWN"
        PHASE="Move"; HINT="⬇  4F持续下行中 | 门:关门到位 | AI采集中"
    elif [ $COUNT -le $((PRECLEAR_MSGS + 4)) ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_3F_DOWN"
        PHASE="Move"; HINT="⬇  经过3F, 持续下行 | 门:关门到位"
    elif [ $COUNT -le $((PRECLEAR_MSGS + 5)) ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_3F_DOWN"
        PHASE="Move"; HINT="⬇  3F持续下行中 | 门:关门到位 | AI采集中"
    elif [ $COUNT -le $((PRECLEAR_MSGS + 6)) ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_2F_DOWN"
        PHASE="Move"; HINT="⬇  到达2F, 减速中 | 门:关门到位"
    elif [ $COUNT -le $MOVE_END_COUNT ]; then
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_2F_STOP"
        PHASE="Move"; HINT="🛑 2F平层停止, 关门到位 → 门基线 | AI缓冲~8条"
    elif [ $ELAPSED -lt $((MOVE_END_COUNT * INTERVAL_SEC + TRAP_SECS)) ]; then
        if [ $TRAP_START -eq 0 ]; then TRAP_START=$(date +%s); fi
        CURRENT_SEG1="$SEG1_TARGET2"; CURRENT_SEG2="$SEG2_TRANSITION"; CURRENT_SEG3="$SEG3_2F_STOP"
        PHASE="Trap"
        TRAP_ELAPSED=$(($(date +%s) - TRAP_START))
        if [ $TRAP_ELAPSED -lt 5 ]; then
            HINT="⏳ 困人计时... (${TRAP_ELAPSED}s/5s) | 门:开门中 | 🤖得分↑"
        elif [ $TRAP_ELAPSED -lt 7 ]; then
            HINT="🔴 困人告警触发! alarm_00 | 门:开门中 | 🤖得分>阈值"
        else
            HINT="🔴 困人持续+ 🤖AI异常 (已${TRAP_ELAPSED}s) | 门:开门中"
        fi
    else
        # 困人解除阶段：先开门让乘客离开(约 OPEN_SECS 秒)，随后自动关门恢复常态。
        # 若门一直保持打开，会超过 door-open 阈值(20s)触发"门超时"告警，
        # 前端把它显示成"开门运行"灯，让解除阶段看起来不正常。
        if [ $ELAPSED -lt $((MOVE_END_COUNT * INTERVAL_SEC + TRAP_SECS + OPEN_SECS)) ]; then
            CURRENT_SEG1="$SEG1_NONE"; CURRENT_SEG2="$SEG2_OPEN"; CURRENT_SEG3="$SEG3_2F_STOP"
            PHASE="Open"
            HINT="✅ 门打开+无内招 → 困人解除 乘客离开 | 门:开门到位"
        else
            CURRENT_SEG1="$SEG1_NONE"; CURRENT_SEG2="$SEG2_CLOSED"; CURRENT_SEG3="$SEG3_2F_STOP"
            PHASE="Idle"
            HINT="🏠 关门到位 正常运行 | 门:关门到位"
        fi
    fi

    RAW_DATA="F${NOW}/${DEVICE_ID}/${CURRENT_SEG1}${CURRENT_SEG2}${CURRENT_SEG3}${SEG4}"

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BACKEND_URL}" \
        --data-urlencode "data=${RAW_DATA}" \
        --data-urlencode "time=${TIME_ONLY}" \
        --data-urlencode "elevatorID=${DEVICE_ID}" \
        --connect-timeout 3 --max-time 5 2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then STATUS="✓"; else STATUS="✗ HTTP ${HTTP_CODE}"; fi

    printf "[#%04d] %s | %ss | %s | %s\n" "$COUNT" "$STATUS" "$ELAPSED" "$PHASE" "$HINT"
    sleep "$INTERVAL_SEC"
done
