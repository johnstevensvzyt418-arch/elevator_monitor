#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
====================================================================
电梯 MNK 协议 MQTT 报文监听 / 解析调试工具
====================================================================
用途：
  1. 订阅远程 EMQX Broker 的 /Elevator 主题，实时监听电梯上报的原始报文，
     并把每条报文解析成人类可读字段（楼层/方向/门/乘客/目标层等）。
  2. 支持离线解析：把抄录的原始 HEX 报文作为命令行参数传入即可解析。
  3. 输出内容包括：原始报文 + 各 HEX 段 + 字段含义 + 告警判定提示，
     对"优化电梯告警规则"很有实际意义（可观察真实设备各字段的取值模式）。

运行方式：
  # 实时监听（默认连远程 broker，环境变量可覆盖）
  python mqtt_packet_debug.py --listen

  # 只监听指定设备（deviceId 是报文第 22-29 位）
  python mqtt_packet_debug.py --listen --device 0024002b

  # 离线解析一条报文（手动抄录）
  python mqtt_packet_debug.py --raw "F2024/01/01 10:00:00/0024002b/000000000000517c 00000000000421b9 30050000000053c3 d00063000000220e"

  # 监听 + 把解析结果追加保存到 CSV（便于后续分析告警规律）
  python mqtt_packet_debug.py --listen --csv packets.csv

环境变量：
  MQTT_BROKER_URL   broker 地址（默认 tcp.sealosbja.site）
  MQTT_BROKER_PORT  端口（默认 35205）
  MQTT_USERNAME     用户名
  MQTT_PASSWORD     密码
  MQTT_TOPIC        订阅主题（默认 /Elevator）
  MQTT_CLIENT_ID    客户端ID（默认 elevator-packet-debug）
====================================================================
"""

import os
import sys
import time
import csv
import json
import argparse
from datetime import datetime

# ==================== MQTT 配置 ====================
BROKER_URL = os.getenv("MQTT_BROKER_URL", "tcp.sealosbja.site")
BROKER_PORT = int(os.getenv("MQTT_BROKER_PORT", "35205"))
MQTT_USERNAME = os.getenv("MQTT_USERNAME", "")
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD", "")
MQTT_TOPIC = os.getenv("MQTT_TOPIC", "/Elevator")
MQTT_CLIENT_ID = os.getenv("MQTT_CLIENT_ID", "elevator-packet-debug")

# ==================== MNK 协议常量（与后端 MNKParser 一致） ====================
# 信号标识（每个 HEX 段最后 2 字节）
SIG_INNER_CALL = "51"  # 内招/目标楼层
SIG_DOOR = "21"        # 开关门
SIG_RUN = "53"         # 运行(方向+楼层)
SIG_RESERVED = "22"    # 保留

# 目标楼层映射（DestFloorConstant，与后端一致）
DEST_FLOOR_MAP = {
    "0": "无", "1": "1", "2": "2", "4": "3", "8": "4",
    "3": "1、2", "5": "1、3", "9": "1、4",
    "6": "2、3", "a": "2、4", "c": "3、4",
    "7": "1、2、3", "b": "1、2、4", "d": "1、3、4", "e": "2、3、4",
}

# 门状态（seg2[10..12]）
def parse_door(sub_s2):
    if sub_s2 == "04":
        return "01", "开门到位"
    if sub_s2 == "10":
        return "00", "关门到位"
    if sub_s2 == "00":
        return "", "开关门中(需结合历史状态)"
    return None, "非法开关门信号 " + sub_s2

# 方向（seg3[0..2]）
def parse_direction(dit):
    if dit in ("30", "31", "36", "37"):
        return "00", "平层/停止"
    if dit == "34":
        return "01", "上行"
    if dit == "35":
        return "02", "下行"
    if dit == "38":
        return "03", "故障(0x80硬件故障)"
    return None, "非法方向 " + dit

# 当前楼层（seg3[2..4]）
CUR_FLOOR_MAP = {"05": "01", "09": "02", "0d": "03", "11": "04"}


def parse_packet(raw_data):
    """解析一条 MNK 报文，返回结构化字典。与后端 MNKParser 逻辑保持一致。"""
    result = {
        "raw": raw_data,
        "ok": False,
        "error": None,
        "fields": {},
        "segments": {},
    }
    if not raw_data:
        result["error"] = "报文为空"
        return result

    raw = raw_data.strip()
    # 兼容两种格式：带空格分段(可读) 或 无空格连续串(设备直发)
    # 注意：头部固定 30 字符 (F + 19字符时间含1个空格 + / + 8字符ID + /)，
    #       后端 MNKParser 用 substring(30) 跳过头部的空格。因此只清理
    #       HEX 区域(第30字符起)的空格，绝不碰头部（否则破坏偏移）。
    header = raw[:30]
    hex_part = raw[30:].replace(" ", "").replace("\t", "")
    raw_no_space = header + hex_part
    if len(hex_part) < 64:
        result["error"] = "HEX区域长度不足(期望>=64, 实际=%d)" % len(hex_part)
        return result

    data = raw_no_space.lower()

    # ---- 1. 标准化 HEX 数据（兼容 92 字符非标准格式） ----
    hex_data = data[30:]
    if len(hex_data) < 64:
        pos53 = hex_data.find(SIG_RUN)
        if pos53 > 0:
            expected_pos = 46
            if pos53 < expected_pos:
                missing = expected_pos - pos53
                hex_data = hex_data[:pos53] + "0" * missing + hex_data[pos53:]
        while len(hex_data) < 64:
            hex_data += "0"
        data = data[:30] + hex_data

    # ---- 2. 时间 + 设备ID ----
    timestamp = data[1:20]
    device_id = data[21:29]
    result["fields"]["timestamp"] = timestamp
    result["fields"]["device_id"] = device_id

    # ---- 3. 按标识符定位各段（协议允许 4 段乱序） ----
    seg1 = data[30:46]
    seg2 = data[46:62]
    seg3 = data[62:78]
    seg4 = data[78:94]

    for i in range(30, 94, 16):
        m14 = data[i + 14:i + 16]
        m12 = data[i + 12:i + 14] if i + 14 <= len(data) else ""
        if SIG_INNER_CALL in (m14, m12):
            seg1 = data[i:i + 16]
        elif SIG_DOOR in (m14, m12):
            seg2 = data[i:i + 16]
        elif SIG_RUN in (m14, m12):
            seg3 = data[i:i + 16]
        elif SIG_RESERVED in (m14, m12):
            seg4 = data[i:i + 16]

    result["segments"] = {
        "seg1_inner": seg1,
        "seg2_door": seg2,
        "seg3_run": seg3,
        "seg4_reserved": seg4,
    }

    # ---- 4. 门状态 ----
    sub_s2 = seg2[10:12]
    door_code, door_desc = parse_door(sub_s2)
    result["fields"]["door_raw"] = sub_s2
    result["fields"]["door_code"] = door_code
    result["fields"]["door_desc"] = door_desc

    # ---- 5. 目标楼层（内招） ----
    tgt_char = seg1[1]
    target_floor = DEST_FLOOR_MAP.get(tgt_char, "未知(%s)" % tgt_char)
    result["fields"]["inner_call_char"] = tgt_char
    result["fields"]["target_floor"] = target_floor

    # ---- 6. 方向 ----
    dit = seg3[0:2]
    direction, direction_desc = parse_direction(dit)
    result["fields"]["direction_raw"] = dit
    result["fields"]["direction_code"] = direction
    result["fields"]["direction_desc"] = direction_desc

    # ---- 7. 当前楼层 ----
    cur_hex = seg3[2:4]
    cur_floor = CUR_FLOOR_MAP.get(cur_hex, "未知(%s)" % cur_hex)
    result["fields"]["floor_hex"] = cur_hex
    result["fields"]["current_floor"] = cur_floor

    # ---- 8. 乘客推断（与后端 inferPassenger 一致） ----
    # 开门到位(01)+无内招→乘客离开；门未开+有内招→有乘客
    if door_code == "01":
        passenger = "00" if target_floor == "无" else "01"
    else:
        passenger = "01" if target_floor != "无" else "00"
    result["fields"]["passenger"] = passenger
    result["fields"]["passenger_desc"] = "有乘客" if passenger == "01" else "无乘客"

    # ---- 9. 告警判定提示（与后端规则一致，用于优化告警） ----
    alarm_hints = infer_alarm_hints(result["fields"])
    result["fields"]["alarm_hints"] = alarm_hints

    result["ok"] = True
    return result


def infer_alarm_hints(f):
    """根据字段值给出可能触发的告警提示（与后端 10 条规则一致）。"""
    hints = []
    cur = f.get("current_floor", "")
    target = f.get("target_floor", "")
    door = f.get("door_code", "")
    direction = f.get("direction_code", "")
    passenger = f.get("passenger", "")

    def floor_equal(c, t):
        if not c or not t or t == "无":
            return False
        for part in t.split("、"):  # 组合内召
            try:
                if int(c) == int(part.strip()):
                    return True
            except ValueError:
                if c == part.strip():
                    return True
        return False

    # 困人：平层+有乘客+门未开
    if floor_equal(cur, target) and passenger == "01" and door != "01":
        hints.append("⚠ 困人风险(LEVELING_TIMEOUT): 平层+有乘客+门未开，持续5s触发")

    # 开门运行：方向非00且非03 + 速度>0.3 + 门开 + 当前≠目标 + 楼层变化
    if direction not in ("00", "03") and door == "01" and not floor_equal(cur, target):
        hints.append("⚠ 开门运行风险(DOOR_OPEN_RUNNING): 运行中门开(需楼层变化+速度>0.3)")

    # 门超时：门非关门状态持续过久
    if door != "00" and door != "":
        hints.append("⚠ 门超时风险(DOOR_OPEN_TOO_LONG): 门非关门持续20s触发")

    # 硬件故障
    if direction == "03":
        hints.append("⚠ 硬件故障(HARDWARE_FAULT): 方向码03")

    if not hints:
        hints.append("✓ 无告警提示")
    return hints


def format_packet(result):
    """把解析结果格式化为易读的多行文本。"""
    if not result["ok"]:
        return "❌ 解析失败: %s\n原始: %s" % (result["error"], result.get("raw", ""))

    f = result["fields"]
    lines = []
    lines.append("=" * 74)
    lines.append("📟 设备 %s  @ %s" % (f["device_id"], f["timestamp"]))
    lines.append("-" * 74)
    lines.append("  原始报文 : %s" % result["raw"])
    lines.append("  seg1 内招: %s  → 目标楼层 = %s" % (result["segments"]["seg1_inner"], f["target_floor"]))
    lines.append("  seg2 开关门: %s → %s (%s)" % (
        result["segments"]["seg2_door"], f["door_desc"], f["door_code"]))
    lines.append("  seg3 运行 : %s → 方向 %s (%s), 楼层 %s" % (
        result["segments"]["seg3_run"], f["direction_desc"], f["direction_code"], f["current_floor"]))
    lines.append("  seg4 保留 : %s" % result["segments"]["seg4_reserved"])
    lines.append("  乘客     : %s" % f["passenger_desc"])
    lines.append("  告警提示 : %s" % "; ".join(f["alarm_hints"]))
    lines.append("=" * 74)
    return "\n".join(lines)


def append_csv(csv_path, result):
    """把解析结果追加到 CSV（便于后续分析告警规律）。"""
    if not result["ok"]:
        return
    f = result["fields"]
    row = {
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "device_id": f["device_id"],
        "device_time": f["timestamp"],
        "current_floor": f["current_floor"],
        "target_floor": f["target_floor"],
        "direction": f["direction_code"],
        "door": f["door_code"],
        "passenger": f["passenger"],
        "alarm_hints": "; ".join(f["alarm_hints"]),
        "raw": result["raw"],
    }
    file_exists = os.path.isfile(csv_path)
    with open(csv_path, "a", newline="", encoding="utf-8-sig") as fp:
        writer = csv.DictWriter(fp, fieldnames=list(row.keys()))
        if not file_exists:
            writer.writeheader()
        writer.writerow(row)


# ==================== MQTT 实时监听 ====================
def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        print(f"[MQTT] ✅ 已连接 {BROKER_URL}:{BROKER_PORT}")
        client.subscribe(MQTT_TOPIC, qos=1)
        print(f"[MQTT] 已订阅: {MQTT_TOPIC}  (Ctrl+C 停止)")
    else:
        print(f"[MQTT] ❌ 连接失败 rc={reason_code}")


def on_message(client, userdata, msg):
    try:
        userdata = userdata or {}
        payload = msg.payload.decode("utf-8", errors="replace").strip()
        # 命令回执 JSON 直接打印原始内容
        if msg.topic.endswith("/command/up") or payload.startswith("{"):
            print(f"\n[Topic] {msg.topic}\n{payload}")
            return
        result = parse_packet(payload)
        print(format_packet(result))
        csv_path = userdata.get("csv")
        if csv_path:
            append_csv(csv_path, result)
    except Exception as e:
        print(f"[MQTT] 消息处理异常: {e}")


def listen(device_filter=None, csv_path=None):
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        print("请先安装 paho-mqtt: pip install paho-mqtt")
        sys.exit(1)

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=MQTT_CLIENT_ID,
        userdata={"csv": csv_path},
    )
    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.on_connect = on_connect
    client.on_message = on_message

    print(f"[MQTT] 监听配置:")
    print(f"  Broker : {BROKER_URL}:{BROKER_PORT}")
    print(f"  Topic  : {MQTT_TOPIC}")
    print(f"  User   : {MQTT_USERNAME or '(无)'}")
    if device_filter:
        print(f"  设备过滤: {device_filter}")
    if csv_path:
        print(f"  CSV保存: {csv_path}")

    try:
        client.connect(BROKER_URL, BROKER_PORT, keepalive=60)
        client.loop_forever()
    except KeyboardInterrupt:
        print("\n[MQTT] 已停止")
        client.disconnect()
    except Exception as e:
        print(f"[MQTT] 连接失败: {e}")
        print("请检查 MQTT_BROKER_URL / MQTT_BROKER_PORT / MQTT_USERNAME / MQTT_PASSWORD 环境变量")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="电梯 MNK 协议 MQTT 报文监听/解析工具")
    parser.add_argument("--listen", action="store_true", help="实时监听 MQTT 报文")
    parser.add_argument("--device", default=None, help="只显示指定设备ID(报文22-29位)的报文")
    parser.add_argument("--csv", default=None, help="把解析结果追加保存到指定 CSV 文件")
    parser.add_argument("--raw", default=None, help="离线解析一条原始报文")
    args = parser.parse_args()

    if args.raw:
        result = parse_packet(args.raw)
        print(format_packet(result))
        if args.csv:
            append_csv(args.csv, result)
            print(f"[CSV] 已保存到 {args.csv}")
        return

    if args.listen:
        listen(device_filter=args.device, csv_path=args.csv)
        return

    parser.print_help()
    print("\n示例:")
    print("  实时监听:    python mqtt_packet_debug.py --listen")
    print("  监听指定设备: python mqtt_packet_debug.py --listen --device 0024002b")
    print("  监听+存CSV:  python mqtt_packet_debug.py --listen --csv packets.csv")
    print("  离线解析:    python mqtt_packet_debug.py --raw \"F2024/01/01 10:00:00/0024002b/000000000000517c 00000000000421b9 30050000000053c3 d00063000000220e\"")


if __name__ == "__main__":
    main()
