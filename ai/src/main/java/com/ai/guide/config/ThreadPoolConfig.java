package com.ai.guide.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一线程池配置
 * 替代各 Service 中手动创建的 Executors，实现优雅关闭和集中管理
 */
@Configuration
public class ThreadPoolConfig {

    /** Redis 异步写入线程池（单线程，保证写入顺序） */
    @Bean(name = "redisAsyncExecutor")
    public ExecutorService redisAsyncExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "redis-async-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 并行检索线程池（Agentic RAG 多子问题并行） */
    @Bean(name = "parallelRagExecutor")
    public ExecutorService parallelRagExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> { Thread t = new Thread(r, "parallel-rag"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /** Rerank API 调用线程池（超时控制用） */
    @Bean(name = "rerankExecutor")
    public ExecutorService rerankExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> { Thread t = new Thread(r, "rerank-api"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /** 对话压缩线程池（异步摘要生成） */
    @Bean(name = "compressionExecutor")
    public ExecutorService compressionExecutor() {
        return new ThreadPoolExecutor(
                1, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20),
                r -> { Thread t = new Thread(r, "compress"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
