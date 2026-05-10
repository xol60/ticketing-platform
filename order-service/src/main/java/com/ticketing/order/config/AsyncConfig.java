package com.ticketing.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Dedicated thread pool for the two parallel guard checks that run at the
     * start of every order-creation request (event-open + queue-access).
     *
     * <p>Sizing rationale:
     * <ul>
     *   <li>Each check is a blocking HTTP call (~10–20 ms).</li>
     *   <li>Two checks run in parallel per request, so peak thread demand ≈ 2 × concurrent orders.</li>
     *   <li>Core = 10 covers ~5 simultaneous order requests without queuing.
     *       Max = 30 handles short bursts. Queue = 200 absorbs flash-sale spikes
     *       without dropping requests (callers block on {@code join()}, not the executor queue).</li>
     * </ul>
     *
     * <p>Kept separate from Kafka consumer threads so a spike in guard-check latency
     * (e.g. downstream service slowdown) cannot starve Kafka processing.
     */
    @Bean(name = "guardCheckExecutor")
    public Executor guardCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("guard-check-");
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }
}
