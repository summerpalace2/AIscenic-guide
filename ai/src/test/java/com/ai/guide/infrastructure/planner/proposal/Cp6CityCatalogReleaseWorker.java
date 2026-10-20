package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Separate-JVM worker for release-version anti-rollback acceptance tests. */
public final class Cp6CityCatalogReleaseWorker {
    private Cp6CityCatalogReleaseWorker() {}

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("CP6_IT_ENABLE"))) throw new IllegalStateException("Probe not enabled");
        if (args.length != 5) throw new IllegalArgumentException("Expected schema, directory, ready, start, expected rejection cause (empty for success)");
        var target = new Cp6InfrastructureTarget(System.getenv());
        var jdbc = new JdbcTemplate(target.dataSource(args[0]));
        Path ready = Path.of(args[2]);
        Path start = Path.of(args[3]);
        Files.createFile(ready);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(start)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("City catalog release start barrier timed out");
            Thread.sleep(20);
        }
        int count;
        try {
            count = new CityAttractionCatalogImporter(Path.of(args[1])).refresh(jdbc, false);
        } catch (RuntimeException failure) {
            String expectedCause = args[4];
            if (expectedCause.isBlank()) throw failure;
            String causes = causeMessages(failure);
            if (!causes.contains(expectedCause))
                throw new IllegalStateException("Unexpected release rejection; expected cause containing: " + expectedCause, failure);
            System.out.println("CP6_CITY_CATALOG_RELEASE_REJECTED " + expectedCause);
            return;
        }
        if (!args[4].isBlank()) throw new IllegalStateException("Expected rejection containing: " + args[4] + " but import succeeded");
        long revision = jdbc.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1", Long.class);
        System.out.println("CP6_CITY_CATALOG_RELEASE_ACCEPTED " + count + " REVISION " + revision);
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
