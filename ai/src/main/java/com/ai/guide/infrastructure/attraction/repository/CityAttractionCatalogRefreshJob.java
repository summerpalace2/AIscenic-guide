package com.ai.guide.infrastructure.attraction.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Enabled explicitly for installations with an external managed city directory. */
@Component
@ConditionalOnProperty(name = "attractions.catalog.refresh-enabled", havingValue = "true")
public class CityAttractionCatalogRefreshJob {
    private static final Logger log = LoggerFactory.getLogger(CityAttractionCatalogRefreshJob.class);
    private final JdbcTemplate jdbc;
    private final CityAttractionCatalogImporter importer;

    public CityAttractionCatalogRefreshJob(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc,
                                           CityAttractionCatalogImporter importer) {
        this.jdbc = jdbc;
        this.importer = importer;
    }

    @Scheduled(fixedDelayString = "${attractions.catalog.refresh-interval-ms:60000}", initialDelayString = "${attractions.catalog.refresh-interval-ms:60000}")
    public void refresh() {
        try {
            boolean sqlite = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                    connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite"));
            importer.refresh(jdbc, sqlite);
        } catch (RuntimeException failure) {
            log.error("城市景点目录刷新失败，继续使用原快照", failure);
        }
    }
}
