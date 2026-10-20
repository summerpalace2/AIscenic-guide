package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;

/** Compatibility hint catalog; new rules should use an owner-specific capability instead. */
public interface LegacyHintTaxonomyPort {
    List<String> getHints(String category);
}
