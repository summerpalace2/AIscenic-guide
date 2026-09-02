package com.ai.guide.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.ArrayBlockingQueue;
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

    /**
     * 规划链路模型调用线程池。
     *
     * 意图识别和行程叙事都属于同步等待模型结果的请求内任务，不能共用
     * ForkJoinPool，也不能与 RAG 检索、路线请求混在一起。使用有界队列和
     * AbortPolicy，过载时让上层快速执行确定性/模板降级，而不是继续占满
     * Web 请求线程和内存。
     */
    @Bean(name = "plannerLlmExecutor", destroyMethod = "shutdown")
    public ExecutorService plannerLlmExecutor(
            @Value("${planner.llm.executor-core-size:2}") int configuredCoreSize,
            @Value("${planner.llm.executor-max-size:4}") int configuredMaxSize,
            @Value("${planner.llm.executor-queue-capacity:16}") int configuredQueueCapacity) {
        int coreSize = Math.max(1, Math.min(8, configuredCoreSize));
        int maxSize = Math.max(coreSize, Math.min(16, configuredMaxSize));
        int queueCapacity = Math.max(4, Math.min(128, configuredQueueCapacity));
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "planner-llm");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 知识库文档向量化执行器。
     *
     * 文档解析、切片、Embedding 和 Qdrant 写入都可能持续较长时间，不能在
     * 上传请求里裸建线程。独立的有界队列用于吸收短时波峰；队列满时快速
     * 拒绝并由文档服务写入 failed，管理员可以通过同步接口重试。
     */
    @Bean(name = "knowledgeIndexExecutor", destroyMethod = "shutdown")
    public ExecutorService knowledgeIndexExecutor(
            @Value("${knowledge.index.executor-core-size:1}") int configuredCoreSize,
            @Value("${knowledge.index.executor-max-size:2}") int configuredMaxSize,
            @Value("${knowledge.index.executor-queue-capacity:8}") int configuredQueueCapacity) {
        int coreSize = Math.max(1, Math.min(4, configuredCoreSize));
        int maxSize = Math.max(coreSize, Math.min(8, configuredMaxSize));
        int queueCapacity = Math.max(2, Math.min(64, configuredQueueCapacity));
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "knowledge-index");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
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

    /**
     * 高德路线批处理专用线程池。
     *
     * 每个规划请求可能包含多个路线段。它们不能进入公共 ForkJoinPool，
     * 否则路线等待会占住 LLM、摘要等其它 CompletableFuture 任务的线程。
     * 有界队列 + CallerRunsPolicy 用调用方线程提供背压，避免请求量上升时
     * 在内存中无限堆积路线任务。
     */
    @Bean(name = "amapHydrationExecutor", destroyMethod = "shutdown")
    public ExecutorService amapHydrationExecutor(
            @Value("${amap.web-service.hydration-concurrency:2}") int concurrency,
            @Value("${amap.web-service.hydration-queue-capacity:16}") int queueCapacity) {
        int threads = Math.max(1, Math.min(8, concurrency));
        int queue = Math.max(4, Math.min(256, queueCapacity));
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "amap-hydration");
                    t.setDaemon(true);
                    return t;
                },
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

    /**
     * 情绪分类线程池；情绪统计属于旁路任务，不能占用对话主链路线程。
     * 使用拒绝策略而不是 CallerRunsPolicy，避免队列满时反向阻塞 SSE 请求。
     */
    @Bean(name = "emotionExecutor")
    public ExecutorService emotionExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> { Thread t = new Thread(r, "emotion-ai"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
