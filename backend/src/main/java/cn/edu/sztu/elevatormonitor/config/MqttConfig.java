package cn.edu.sztu.elevatormonitor.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * MQTT 配置 — 连接远程 EMQX Broker，接收嵌入式设备通过 MQTT 协议上报的电梯数据。
 *
 * <h3>架构</h3>
 * <pre>
 *   嵌入式设备 → EMQX Broker (tcp.sealosbja.site:35205)
 *     → MqttPahoMessageDrivenChannelAdapter (订阅 /Elevator)
 *     → mqttInputChannel → MqttMessageReceiver (路由到 MNKApplicationService)
 * </pre>
 *
 * <h3>Topic 约定 (按设备接入指南)</h3>
 * <pre>
 *   /Elevator                         — 设备 → 平台: 电梯状态上报(订阅)
 *   /elevator/{deviceId}/command/up   — 设备 → 平台: 命令回执(订阅)
 *   /elevator/{deviceId}/command/down — 平台 → 设备: 派梯命令(发布)
 * </pre>
 *
 * @author mqtt-integration
 * @since 0.3.0
 */
@Configuration
public class MqttConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqttConfig.class);

    @Value("${mqtt.broker.url:tcp://tcp.sealosbja.site:35205}")
    private String brokerUrl;

    @Value("${mqtt.client.id:elevator-monitor-backend}")
    private String clientId;

    /** 设备上报主题 (设备→平台): /Elevator */
    @Value("${mqtt.topic:/Elevator}")
    private String topic;

    /** 命令回执主题 (设备→平台), 通配符匹配所有设备 */
    @Value("${mqtt.command-up-topic:/elevator/+/command/up}")
    private String commandUpTopic;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Value("${mqtt.qos:1}")
    private int qos;

    /**
     * MQTT 客户端工厂。
     */
    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{brokerUrl});
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
        }
        if (password != null && !password.isEmpty()) {
            options.setPassword(password.toCharArray());
        }
        factory.setConnectionOptions(options);
        LOGGER.info("[MQTT] 客户端工厂已配置, broker={}, clientId={}", brokerUrl, clientId);
        return factory;
    }

    /**
     * MQTT 消息处理线程池 — 供 @Async 注解使用，将消息处理从 MQTT 接收线程剥离。
     */
    @Bean("mqttExecutor")
    public ThreadPoolTaskExecutor mqttExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mqtt-msg-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        LOGGER.info("[MQTT] mqttExecutor 初始化: core=2, max=4, queue=200");
        return executor;
    }

    /**
     * MQTT 入站通道 — 使用 DirectChannel。
     * MQTT 消息通过 @Async("mqttExecutor") 在 MqttMessageReceiver 中异步处理。
     */
    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    /**
     * MQTT 消息驱动适配器 — 订阅设备上报 + 命令回执主题，路由到 mqttInputChannel。
     *
     * <p><b>禁用开关 (2026-08-03):</b> 通过 {@code mqtt.enabled=false} 可关闭后端直连 MQTT。
     * 若改用独立的 mqtt_bridge.py 转发（docker compose --profile bridge），
     * 必须关闭本订阅，否则同一条设备报文会被后端 MQTT 与 bridge 双重处理，
     * 导致速度计算被除零放大（误报超速）、运行次数翻倍。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MessageProducer mqttInbound() {
        // 合并订阅: 设备上报 + 命令回执
        String[] topics = {topic, commandUpTopic};
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId, mqttClientFactory(), topics);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(qos);
        adapter.setOutputChannel(mqttInputChannel());
        LOGGER.info("[MQTT] 适配器已创建, topic={}, qos={}", topic, qos);
        return adapter;
    }
}
