package cn.edu.sztu.elevatormonitor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 事件总线配置 — 支持异步事件处理与线程池隔离。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>{@code eventMulticaster} 使用独立线程池，确保事件发布不阻塞 Controller 线程</li>
 *   <li>异步 Listener（标注 {@code @Async}）使用各自的线程池隔离</li>
 *   <li>同步 Listener（未标注 {@code @Async}）在执行器内顺序执行</li>
 * </ul>
 *
 * <h3>线程池规划</h3>
 * <table>
 *   <tr><th>线程池</th><th>用途</th><th>core/max</th></tr>
 *   <tr><td>eventExecutor</td><td>事件广播</td><td>2/8</td></tr>
 *   <tr><td>alarmExecutor</td><td>告警评估（已有）</td><td>4/16</td></tr>
 *   <tr><td>SimpleAsyncTaskExecutor</td><td>History/Redis @Async</td><td>Spring 默认</td></tr>
 * </table>
 *
 * @author architecture-v2
 * @since 0.2.0
 */
@Configuration
public class EventConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventConfig.class);

    /**
     * 事件广播线程池。
     * 核心线程 2，最大 8，队列 2000，拒绝策略 CallerRunsPolicy（背压不丢事件）。
     */
    @Bean("eventExecutor")
    public ThreadPoolTaskExecutor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("event-bus-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        LOGGER.info("[EventConfig] eventExecutor 初始化: core=2, max=8, queue=2000");
        return executor;
    }

    /**
     * 应用事件多播器 — 保持同步广播，由 Listener 自己通过 {@code @Async} 控制异步。
     *
     * <p><b>修复说明 (2026-06-25):</b>
     * 原先设置了 {@code setTaskExecutor(eventExecutor())} 导致多播器异步调度事件，
     * 与 {@code @EventListener} + {@code @Async} 产生双重异步冲突，
     * 造成 {@code @EventListener} 方法静默未触发。
     * 现移除了 TaskExecutor，恢复同步广播，异步交由各 Listener 的 {@code @Async} 独立控制。</p>
     */
    @Bean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    public ApplicationEventMulticaster applicationEventMulticaster() {
        return new SimpleApplicationEventMulticaster();
    }
}
