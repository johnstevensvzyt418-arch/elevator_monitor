#!/usr/bin/env python3
# ============================================================
# Elevator Monitor — MQTT 桥接器
# 订阅远程 EMQX Broker 的 /Elevator 主题，转发到后端 HTTP API
#
# 环境变量:
#   MQTT_BROKER_URL    - MQTT Broker 地址
#   MQTT_BROKER_PORT   - MQTT Broker 端口
#   MQTT_USERNAME      - MQTT 用户名
#   MQTT_PASSWORD      - MQTT 密码（必填）
#   BACKEND_URL        - 后端 HTTP API
#
# 架构说明:
#   MQTT 网络线程 (loop_start) → 消息队列 → HTTP 工作线程
#   这样 HTTP 调用不会阻塞 MQTT 消息接收，避免延迟+跳帧。
# ============================================================

import os
import sys
import time
import queue
import threading
import urllib.request
import urllib.parse
import urllib.error

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("请先安装 paho-mqtt: pip install paho-mqtt")
    sys.exit(1)

# ---- 从环境变量读取配置 ----
BROKER_URL = os.getenv("MQTT_BROKER_URL", "tcp.sealosbja.site")
BROKER_PORT = int(os.getenv("MQTT_BROKER_PORT", "35205"))
MQTT_USERNAME = os.getenv("MQTT_USERNAME", "")
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD", "")
BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:10008/api/v2/mnk")
TOPIC = "/Elevator"

# 密码为空时给出明确警告（但不阻止启动，方便本地无认证调试）
if not MQTT_PASSWORD:
    print("[Bridge] ⚠️ 未设置 MQTT_PASSWORD 环境变量，将以无密码模式连接")

# 消息队列（MQTT 线程 → HTTP 工作线程）
# 单 worker 线程保证 FIFO 顺序，避免后端收到乱序报文导致前端跳帧。
MSG_QUEUE = queue.Queue(maxsize=500)

print(f"[Bridge] 配置:")
print(f"  MQTT Broker: {BROKER_URL}:{BROKER_PORT}")
print(f"  MQTT Topic:  {TOPIC}")
print(f"  Backend URL: {BACKEND_URL}")
print(f"  MQTT User:   {MQTT_USERNAME}")


def on_connect(client, userdata, flags, rc, props):
    if rc == 0:
        print(f"[Bridge] ✅ 已连接到 MQTT Broker (rc={rc})")
        client.subscribe(TOPIC, qos=1)
        print(f"[Bridge] 已订阅主题: {TOPIC}")
    else:
        print(f"[Bridge] ❌ 连接失败 (rc={rc})，5秒后自动重连...")


def on_disconnect(client, userdata, flags, rc, props):
    if rc != 0:
        print(f"[Bridge] ⚠️ 意外断开 (rc={rc})，将自动重连...")


def on_message(client, userdata, msg):
    """MQTT 回调 — 仅入队，立即返回，不阻塞 MQTT 网络循环。"""
    try:
        payload = msg.payload.decode().strip()
        # 标准化报文：后端 MNKParser 用固定偏移(substring(30))解析，假设
        # 头部30字符(F+时间含1空格+/+ID+/)后是连续 HEX。若设备/EMQX 分发了
        # 带空格的分段报文，必须清理 HEX 区域(第30字符起)的空格，否则后端
        # 段定位错乱导致解析失败/告警漏报。
        # 注意：只清理 HEX 区域空格，绝不碰头部（头部时间含 1 个空格）。
        if len(payload) > 30:
            payload = payload[:30] + payload[30:].replace(" ", "").replace("\t", "")
        # 非阻塞入队；队列满时丢弃最旧消息（背压保护）
        try:
            MSG_QUEUE.put_nowait(payload)
        except queue.Full:
            # 丢弃一条旧消息为新消息腾空间
            try:
                MSG_QUEUE.get_nowait()
                MSG_QUEUE.put_nowait(payload)
            except queue.Empty:
                pass
    except Exception as e:
        print(f"[Bridge] ❌ 入队异常: {e}")


def http_worker():
    """HTTP 工作线程 — 从队列取消息，同步转发到后端。"""
    while True:
        try:
            payload = MSG_QUEUE.get()
            if payload is None:
                break

            # 从报文中提取 deviceId (偏移21-28) 和设备时间 (偏移12-19)
            device_id = payload[21:29] if len(payload) >= 29 else "unknown"
            device_time = payload[12:20] if len(payload) >= 20 else time.strftime("%H:%M:%S")

            # 转发到后端 HTTP API
            data = urllib.parse.urlencode({
                "data": payload,
                "time": device_time,
                "elevatorID": device_id,
            }).encode()

            req = urllib.request.Request(BACKEND_URL, data=data, method="POST")
            with urllib.request.urlopen(req, timeout=5) as resp:
                result = resp.read().decode()
                print(f"[Bridge] device={device_id} → HTTP {result}")

        except urllib.error.URLError as e:
            print(f"[Bridge] device={device_id if 'device_id' in dir() else '?'} → HTTP ERROR: {e}")
        except Exception as e:
            print(f"[Bridge] ❌ 处理消息异常: {e}")
        finally:
            MSG_QUEUE.task_done()


def main():
    # 启动 HTTP 工作线程（单线程保证 FIFO 顺序，避免后端收到乱序报文）
    worker = threading.Thread(target=http_worker, name="http-worker", daemon=True)
    worker.start()
    print("[Bridge] HTTP 工作线程已启动")

    # 使用 MQTTv5 协议 + VERSION2 回调 API
    client = mqtt.Client(
        client_id="elevator-bridge",
        protocol=mqtt.MQTTv5,
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
    )
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message

    # 自动重连配置
    client.reconnect_delay_set(min_delay=1, max_delay=30)

    print(f"[Bridge] 正在连接到 {BROKER_URL}:{BROKER_PORT} ...")
    client.connect(BROKER_URL, BROKER_PORT, 60)

    # loop_start: MQTT 网络循环在后台线程运行，不阻塞主线程
    client.loop_start()

    print("[Bridge] 运行中，按 Ctrl+C 停止...")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[Bridge] 收到退出信号，正在清理...")
    finally:
        client.loop_stop()
        client.disconnect()
        # 通知工作线程退出
        MSG_QUEUE.put(None)
        worker.join(timeout=5)
        print("[Bridge] 已停止")


if __name__ == "__main__":
    main()
