package com.finance.command.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

@Configuration
public class VirtualThreadConfig {

    /**
     * @Primary so {@code @Async} resolves here instead of falling back to
     * SimpleAsyncTaskExecutor: with the scheduler bean also present,
     * AnnotationAsyncExecutionInterceptor cannot pick a unique TaskExecutor.
     * All our @Async work is fan-out I/O (cache evictions, notifications), so
     * virtual threads are the right executor.
     */
    @Bean
    @Primary
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new VirtualThreadTaskExecutor();
    }
}
