package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/** Separate-JVM worker for the opt-in city catalog PostgreSQL acceptance probe. */
public final class Cp6CityCatalogWorker {
    private Cp6CityCatalogWorker() {}

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("CP6_IT_ENABLE"))) throw new IllegalStateException("Probe not enabled");
        if (args.length != 4) throw new IllegalArgumentException("Expected schema, directory, ready file, start file");
        var target = new Cp6InfrastructureTarget(System.getenv());
        var jdbc = new JdbcTemplate(target.dataSource(args[0]));
        Path ready = Path.of(args[2]);
        Path start = Path.of(args[3]);
        Files.createFile(ready);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(start)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("City catalog start barrier timed out");
            Thread.sleep(20);
        }
        int count = new CityAttractionCatalogImporter(Path.of(args[1])).refresh(jdbc, false);
        long revision = jdbc.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1", Long.class);
        System.out.println("CP6_CITY_CATALOG_IMPORTED " + count + " REVISION " + revision);
    }
}
