package cn.edu.sztu.elevatormonitor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>taskExecutor (@Primary):</b> 默认异步线程池，RedisHandler / HistoryHandler 等
 *       未指定执行器的 {@code @Async} 方法使用此池，替代 Spring 默认的
 *       {@code SimpleAsyncTaskExecutor}（后者无限创建线程）</li>
 *   <li><b>alarmExecutor:</b> 告警专用有界线程池，防止 SimpleAsyncTaskExecutor 无限创建线程</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * 默认异步线程池 — @Primary，供 RedisHandler / HistoryHandler 等共用。
     *
     * <pre>
     * corePoolSize  = 4      — 常驻线程
     * maxPoolSize   = 8      — 峰值线程
     * queueCapacity = 200    — 缓冲队列
     * keepAlive     = 60s    — 空闲线程回收时间
     * </pre>
     *
     * 拒绝策略: {@link ThreadPoolExecutor.CallerRunsPolicy}
     * — 队列满时由调用线程执行，不丢弃任务。
     */
    @Primary
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        LOGGER.info("[Async] taskExecutor 初始化 (@Primary): core=4, max=8, queue=200, "
                        + "keepAlive=60s, rejection=CallerRunsPolicy, awaitTermination=30s");
        return executor;
    }

    /**
     * 告警专用线程池。
     *
     * <pre>
     * corePoolSize  = 4      — 常驻线程，应对常规告警量
     * maxPoolSize   = 16     — 峰值线程，应对告警风暴
     * queueCapacity = 500    — 缓冲队列，平滑突发流量
     * keepAlive     = 60s    — 超出核心数的线程空闲回收时间
     * </pre>
     *
     * 拒绝策略: {@link ThreadPoolExecutor.CallerRunsPolicy}
     * — 队列满时由调用线程执行，提供天然背压，避免丢弃告警。
     */
    @Bean("alarmExecutor")
    public ThreadPoolTaskExecutor alarmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("alarm-worker-");
        // 拒绝策略: 调用者线程执行 → 背压 + 不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭: 等待队列中任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        LOGGER.info("[Async] alarmExecutor 初始化: core={}, max={}, queue={}, keepAlive={}s, "
                        + "rejection=CallerRunsPolicy, awaitTermination=30s",
                4, 16, 500, 60);
        return executor;
    }

    /**
     * AI 时序专用单线程执行器。
     * 同一数据流必须严格按事件发布顺序进入滑动窗口，避免多线程重排时序。
     */
    @Bean("aiExecutor")
    public ThreadPoolTaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ai-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        LOGGER.info("[Async] aiExecutor 初始化: single-thread ordered queue=500");
        return executor;
    }
}
