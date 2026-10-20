package com.ai.guide.testing;

import com.ai.guide.domain.memory.port.MemoryRepositoryPort;
import com.ai.guide.domain.memory.service.MemoryPreferenceProjector;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class MemoryTestFixtures {
    private MemoryTestFixtures() {}

    public static TravelMemoryService service(MemoryRepositoryPort repository) {
        return service(repository, null);
    }

    public static TravelMemoryService service(MemoryRepositoryPort repository, PreferencesService preferences) {
        return new TravelMemoryService(repository, preferences, null, new ObjectMapper(),
                new MemoryPreferenceProjector(new JsonTaxonomyAdapter()));
    }
}
