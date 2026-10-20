package com.ai.guide.infrastructure.dining.repository;

import com.ai.guide.domain.dining.gateway.DiningVenueCatalogPort;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Reads the packaged, replaceable dining seed asset for cold-start fallback and tests. */
public class PackagedDiningVenueCatalogAdapter implements DiningVenueCatalogPort {
    private static final Logger log = LoggerFactory.getLogger(PackagedDiningVenueCatalogAdapter.class);
    private final List<DiningVenue> venues;

    public PackagedDiningVenueCatalogAdapter(ObjectMapper objectMapper) {
        List<DiningVenue> loaded;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("dining_venues.json")) {
            if (input == null) {
                loaded = List.of();
            } else {
                List<Map<String, Object>> rows = objectMapper.readValue(input, new TypeReference<>() {});
                loaded = rows.stream().map(row -> JdbcDiningVenueCatalogAdapter.mapRow(row, objectMapper)).toList();
            }
        } catch (Exception error) {
            log.warn("Packaged dining catalog could not be loaded: {}", error.getMessage());
            loaded = List.of();
        }
        venues = loaded;
    }

    @Override public List<DiningVenue> findAll() { return venues; }
}
