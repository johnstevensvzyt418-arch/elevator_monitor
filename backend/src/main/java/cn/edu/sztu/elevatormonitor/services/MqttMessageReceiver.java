package cn.edu.sztu.elevatormonitor.services;

import cn.edu.sztu.elevatormonitor.application.MNKApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT 消息接收服务 — 将嵌入式设备通过 MQTT 上报的电梯数据
 * 路由到 {@link MNKApplicationService} 统一处理。
 *
 * <h3>MQTT Topic 约定（按设备接入指南）</h3>
 * <pre>
 *   /Elevator                         → payload = MNK 94字节HEX, deviceId 从报文提取
 *   /elevator/{deviceId}/command/up   → payload = 命令回执JSON, deviceId 从 topic 提取
 * </pre>
 *
 * <h3>处理链路</h3>
 * <pre>
 *   MQTT Message → 提取 payload + deviceId + time
 *                → MNKApplicationService.handleDataReport()
 *                → (与 HTTP POST 完全相同的事件驱动管道)
 * </pre>
 *
 * @author mqtt-integration
 * @since 0.3.0
 */
@Service
public class MqttMessageReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqttMessageReceiver.class);

    /** 从 topic 中提取 deviceId: /elevator/{deviceId}/command/up */
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("/elevator/(\\w+)/command/up");

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MNKApplicationService mnkApplicationService;
    private final StringRedisTemplate stringRedisTemplate;

    public MqttMessageReceiver(MNKApplicationService mnkApplicationService,
                               StringRedisTemplate stringRedisTemplate) {
        this.mnkApplicationService = mnkApplicationService;
        this.stringRedisTemplate = stringRedisTemplate;
        LOGGER.info("[MQTT-Receiver] 初始化完成, 已注入 MNKApplicationService + StringRedisTemplate");
    }

    /**
     * 接收 MQTT 消息，解析后路由到 MNK 处理管道。
     *
     * <p>@ServiceActivator 绑定到 mqttInputChannel，由 Spring Integration 自动调用。</p>
     *
     * @param message MQTT 消息 (payload = MNK HEX 字符串)
     */
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMqttMessage(Message<?> message) {
        try {
            String topic = (String) message.getHeaders().get("mqtt_receivedTopic");

            // ---- 兼容 String 和 byte[] 两种 payload 类型 ----
            // EMQX 5.x 默认以 UTF-8 文本形式投递消息, Spring Integration 可能解析为 String
            Object rawPayload = message.getPayload();
            String payload;
            if (rawPayload instanceof byte[]) {
                payload = new String((byte[]) rawPayload, StandardCharsets.UTF_8).trim();
            } else if (rawPayload instanceof String) {
                payload = ((String) rawPayload).trim();
            } else {
                LOGGER.warn("[MQTT-Receiver] 未知payload类型: {}, topic={}",
                        rawPayload.getClass().getName(), topic);
                return;
            }

            if (payload.isEmpty()) {
                LOGGER.warn("[MQTT-Receiver] 收到空消息, topic={}", topic);
                return;
            }

            LOGGER.info("[MQTT-Receiver] 收到MQTT消息: topic={}, len={}, payload={}",
                    topic, payload.length(),
                    payload.length() <= 120 ? payload : payload.substring(0, 120) + "...");

            // ---- 路由: 命令回执 vs 设备状态上报 ----
            if (topic != null && topic.contains("/command/up")) {
                handleCommandResponse(topic, payload);
                return;
            }

            // ---- 设备状态上报 (/Elevator) ----
            String deviceId = extractDeviceId(topic, payload);
            if (deviceId == null || deviceId.isEmpty()) {
                LOGGER.warn("[MQTT-Receiver] 无法提取deviceId, topic={}", topic);
                return;
            }

            // ---- 2. 优先从 payload 提取设备时间 (MNK协议 data[1..20] = yyyy/MM/dd HH:mm:ss) ----
            String reportTime = extractDeviceTime(payload);

            // ---- 3. 路由到统一处理管道 ----
            int result = mnkApplicationService.handleDataReport(payload, reportTime, deviceId);

            LOGGER.info("[MQTT-Receiver] 处理完成: topic={}, deviceId={}, result={}", topic, deviceId, result);

        } catch (Exception e) {
            LOGGER.error("[MQTT-Receiver] 消息处理异常", e);
        }
    }

    /**
     * 处理设备命令回执 (/elevator/{deviceId}/command/up)。
     * Payload 格式: {payload:[type, floor, result]}
     */
    private void handleCommandResponse(String topic, String payload) {
        Matcher m = DEVICE_ID_PATTERN.matcher(topic);
        String deviceId = m.find() ? m.group(1) : "unknown";
        LOGGER.info("[MQTT-Receiver] 命令回执: deviceId={}, payload={}", deviceId, payload);
        // TODO: 将命令回执写入 Redis/MySQL，供上层业务查询
        try {
            stringRedisTemplate.opsForHash().put("elevator:command_results", deviceId, payload);
            stringRedisTemplate.convertAndSend("elevator:command_results", 
                    "{\"deviceId\":\"" + deviceId + "\",\"payload\":" + payload + "}");
        } catch (Exception e) {
            LOGGER.error("[MQTT-Receiver] 命令回执保存失败: deviceId={}", deviceId, e);
        }
    }

    /**
     * 从 MNK 协议 payload 中提取设备采集时间。
     * MNK 协议格式: Fyyyy/MM/dd HH:mm:ss/...  时间位于 data[1..20]
     * 若提取失败，退化为服务器当前时间。
     */
    private String extractDeviceTime(String payload) {
        try {
            if (payload != null && payload.length() >= 20 && payload.charAt(0) == 'F') {
                // 提取 yyyy/MM/dd HH:mm:ss → 转为 HH:mm:ss
                String dateTime = payload.substring(1, 20); // "2020/11/10 11:24:46"
                if (dateTime.length() >= 17) {
                    return dateTime.substring(11, 19); // "11:24:46"
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[MQTT-Receiver] 设备时间提取失败，使用服务器时间", e);
        }
        return LocalDateTime.now().format(DT_FMT);
    }

    /**
     * 从 MQTT Topic 或报文中提取设备ID。
     */
    private String extractDeviceId(String topic, String payload) {
        // 方式A: 从 topic 中提取 elevator/{deviceId}/data
        if (topic != null) {
            Matcher m = DEVICE_ID_PATTERN.matcher(topic);
            if (m.find()) {
                return m.group(1);
            }
        }

        // 方式B: 从 MNK 协议报文中提取 (偏移21-28为设备ID)
        if (payload != null && payload.length() >= 29) {
            try {
                return payload.substring(21, 29).trim();
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
