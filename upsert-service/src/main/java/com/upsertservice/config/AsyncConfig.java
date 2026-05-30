package com.upsertservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Defines named thread-pool executors for asynchronous operations.
 *
 * Pool sizing mirrors the connection pool sizes — there is no benefit in having
 * more threads than there are DB connections available to serve them.
 *
 *  writeTaskExecutor  — matches HikariPool-write max size (8)
 *  readTaskExecutor   — matches HikariPool-read  max size (20)
 *
 * Usage (optional, for explicitly async service calls):
 *   @Async("writeTaskExecutor")
 *   public CompletableFuture<CreateEntryResponse> createEntryAsync(...) { ... }
 *
 *   @Async("readTaskExecutor")
 *   public CompletableFuture<Page<TransactionEntry>> getEntriesAsync(...) { ... }
 *
 * Note: With virtual threads enabled (spring.threads.virtual.enabled=true),
 * standard synchronous request handling is already near-optimal for I/O-bound
 * DB calls. These executors are available for explicitly fire-and-forget
 * or parallel fan-out patterns.
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean(name = "writeTaskExecutor")
    public Executor writeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);               // mirrors HikariPool-write max
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("write-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "readTaskExecutor")
    public Executor readTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);              // mirrors HikariPool-read max
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("read-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
