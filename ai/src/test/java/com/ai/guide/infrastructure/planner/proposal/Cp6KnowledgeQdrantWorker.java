package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** A genuinely separate lock domain: the parent JVM mutates while this JVM embeds. */
public final class Cp6KnowledgeQdrantWorker {
    public static void main(String[] args) throws Exception {
        var target = new Cp6InfrastructureTarget(System.getenv());
        Path run = Path.of(args[3]);
        Runnable gate = () -> {
            try {
                Files.writeString(run.resolve("ready"), "embedding gated");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!Files.exists(run.resolve("release"))) {
                    if (System.nanoTime() > until) throw new IllegalStateException("Embedding gate timed out");
                    Thread.sleep(20);
                }
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        try (var fixture = new Cp6KnowledgeQdrantFixture(args[1], gate)) {
            var jdbc = new JdbcTemplate(target.dataSource(args[0]));
            var store = new JdbcKnowledgeDocumentStoreAdapter(jdbc);
            var service = KnowledgeTestPorts.service(store, fixture.adapter(fixture.cache(), store));
            try {
                service.triggerSync(args[2]);
                throw new AssertionError("Stale sync incorrectly reported success");
            } catch (RuntimeException expected) {
                String message = expected.getMessage() == null ? "" : expected.getMessage();
                if (!message.contains("同步期间文档已修改或删除")
                        && !message.contains("同步期间文档已修改或由其他实例接手")) throw expected;
                System.out.println("CP6_STALE_PUBLICATION_REJECTED");
            }
        }
    }
}
