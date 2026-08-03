#!/bin/bash
# ============================================================
# 困人告警模拟脚本 v2 — 设备 0024002b
#
# 用法:
#   chmod +x simulate_trapped.sh
#   ./simulate_trapped.sh                      # 默认 localhost:10008
#   ./simulate_trapped.sh 192.168.1.100:10008  # 指定后端地址
#
# 模拟流程:
#   Phase 0 (0~1s)  : 关门到位, 建立门状态基线 (lastDoor="00")
#   Phase 1 (1~11s) : 开门中 + 有乘客 + 平层 → 困人计时启动
#                     ~6s 后告警触发, 持续 ~10s
#   Phase 2 (11s~)  : 开门到位 → 困人条件解除, 告警灯熄灭
# ============================================================

set -e

# ---- 配置 ----
BACKEND_HOST="${1:-localhost:10008}"
BACKEND_URL="http://${BACKEND_HOST}/api/v2/mnk"
DEVICE_ID="0024002b"
INTERVAL_SEC="${2:-1}"

# ---- MNK 协议报文段 ----
SEG1="020000000000517c"       # 内招: charAt(1)='2' → 目标楼层=2 → 有乘客
SEG1_EMPTY="000000000000517c"  # 无内招(charAt(1)='0') → 目标楼层=无 → 乘客离开
SEG3="30090000000053c3"       # 运行: [0:2]="30"→平层, [2:4]="09"→2F
SEG4="d00063000000220e"       # 保留

# seg2 三种门状态
SEG2_CLOSED="00000000001021b9"     # [10:12]="10" → 关门到位 (door="00")
SEG2_TRANSITION="00000000000021b9" # [10:12]="00" → 修正为"03"开门中
SEG2_OPEN="00000000000421b9"       # [10:12]="04" → 开门到位 (door="01")

PHASE1_DURATION=15   # 困人阶段持续秒数（5s计时 + 10s告警）
PHASE0_MSGS=1        # 基线阶段消息数

# ---- 预清理：发送正常报文强制清除旧告警标记 ----
SEG1_NORMAL="000000000000517c"   # 无内招(charAt(1)='0') → 无乘客
SEG2_NORMAL="00000000001021b9"   # 关门到位
SEG3_NORMAL="30050000000053c3"   # 平层 + 1F

NOW=$(date +"%Y/%m/%d %H:%M:%S")
TIME_ONLY=$(date +"%H:%M:%S")
NORMAL_DATA="F${NOW}/${DEVICE_ID}/${SEG1_NORMAL}${SEG2_NORMAL}${SEG3_NORMAL}${SEG4}"

echo "[Pre] 发送正常报文清除旧告警标记..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${BACKEND_URL}" \
    --data-urlencode "data=${NORMAL_DATA}" \
    --data-urlencode "time=${TIME_ONLY}" \
    --data-urlencode "elevatorID=${DEVICE_ID}" \
    --connect-timeout 3 --max-time 5 2>/dev/null || echo "000")
if [ "$HTTP_CODE" = "200" ]; then
    echo "[Pre] ✓ 旧标记已清除"
else
    echo "[Pre] ✗ 清除失败 HTTP ${HTTP_CODE}"
fi
sleep 1

echo "╔══════════════════════════════════════════════════════╗"
echo "║       🛗 困人告警模拟脚本 (Bash) v2                 ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  设备ID    : ${DEVICE_ID}                            "
echo "║  后端地址  : ${BACKEND_URL}"
echo "║  发送间隔  : ${INTERVAL_SEC}s                         "
echo "╠══════════════════════════════════════════════════════╣"
echo "║  Phase 0 ~1s : 关门到位（建立基线）                   ║"
echo "║  Phase 1 ~10s: 开门中 + 有乘客 + 平层 → 困人!        ║"
echo "║  Phase 2   ~ : 开门到位 → 困人解除, 灯熄灭           ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "[*] 开始发送报文..."
echo "    ~7s  后前端 alarm_00(困人) ☉ 亮起"
echo "    ~12s 后门打开, 困人告警解除 ○"
echo "    按 Ctrl+C 提前停止"
echo ""

COUNT=0
START_TIME=$(date +%s)
PHASE1_START=0

while true; do
    COUNT=$((COUNT + 1))
    NOW=$(date +"%Y/%m/%d %H:%M:%S")
    TIME_ONLY=$(date +"%H:%M:%S")
    ELAPSED=$(($(date +%s) - START_TIME))

    # ---- 选择当前阶段的 seg1(内招) 和 seg2(门状态) ----
    if [ $COUNT -le $PHASE0_MSGS ]; then
        CURRENT_SEG1="$SEG1"
        CURRENT_SEG2="$SEG2_CLOSED"
        PHASE="Phase 0"
        DOOR_LABEL="关门到位"
    elif [ $ELAPSED -lt $((PHASE0_MSGS * INTERVAL_SEC + PHASE1_DURATION)) ]; then
        if [ $PHASE1_START -eq 0 ]; then
            PHASE1_START=$(date +%s)
        fi
        CURRENT_SEG1="$SEG1"
        CURRENT_SEG2="$SEG2_TRANSITION"
        PHASE="Phase 1"
        DOOR_LABEL="开门中"
    else
        CURRENT_SEG1="$SEG1_EMPTY"
        CURRENT_SEG2="$SEG2_OPEN"
        PHASE="Phase 2"
        DOOR_LABEL="开门到位(解除)"
    fi

    # 拼接完整 94 字符报文
    RAW_DATA="F${NOW}/${DEVICE_ID}/${CURRENT_SEG1}${CURRENT_SEG2}${SEG3}${SEG4}"

    # 发送 HTTP POST
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BACKEND_URL}" \
        --data-urlencode "data=${RAW_DATA}" \
        --data-urlencode "time=${TIME_ONLY}" \
        --data-urlencode "elevatorID=${DEVICE_ID}" \
        --connect-timeout 3 \
        --max-time 5 2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then
        STATUS="✓"
    else
        STATUS="✗ HTTP ${HTTP_CODE}"
    fi

    # ---- 状态提示 ----
    if [ $PHASE1_START -gt 0 ]; then
        PHASE1_ELAPSED=$(($(date +%s) - PHASE1_START))
    else
        PHASE1_ELAPSED=0
    fi

    case "$PHASE" in
        "Phase 0")
            HINT="🔧 建立基线: 关门到位 → Redis lastDoor=\"00\"" ;;
        "Phase 1")
            if [ $PHASE1_ELAPSED -lt 5 ]; then
                HINT="⏳ 困人计时中... (${PHASE1_ELAPSED}s/5s) | 门:${DOOR_LABEL}"
            elif [ $PHASE1_ELAPSED -lt 7 ]; then
                HINT="🔴 困人告警触发! 检查前端灯(alarm_00) | 门:${DOOR_LABEL}"
            else
                HINT="🔴 困人告警持续中 (已${PHASE1_ELAPSED}s) | 门:${DOOR_LABEL}"
            fi ;;
        "Phase 2")
            HINT="✅ 门已打开 → 困人解除, 告警灯应熄灭 ○ | 门:${DOOR_LABEL}" ;;
    esac

    printf "[#%04d] %s | %ss | %s | %s\n" "$COUNT" "$STATUS" "$ELAPSED" "$PHASE" "$HINT"

    sleep "$INTERVAL_SEC"
done
