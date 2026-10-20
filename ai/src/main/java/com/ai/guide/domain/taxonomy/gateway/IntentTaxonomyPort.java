package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;

/** Intent-radar rules owned by the conversation domain. */
public interface IntentTaxonomyPort {
    List<TaxonomyPort.IntentRadarRule> getIntentRadarRules();
    List<TaxonomyPort.InterestKeyword> getInterestKeywords();
}
