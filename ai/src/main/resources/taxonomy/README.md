# Dining taxonomy assets

`brand_dictionary.json` is the single seed source for brand recognition. `dining_taxonomy.json` defines meal windows (minutes after midnight), city breakfast profiles, legacy hints, and flavor aliases. City profiles match exact city names with an optional trailing 市. Unknown cities use DEFAULT; this does not locate a restaurant.

Production services use `TaxonomyPort`. To permit operational updates, set `PLANNER_TAXONOMY_DIRECTORY` (Spring property `planner.taxonomy.directory`) to an absolute directory containing either or both JSON files. Files absent from this directory use bundled classpath defaults. The provider rechecks at most every five seconds when used, parses both files into one immutable snapshot, and retains the previous snapshot if validation fails. Classpath resources alone do not hot reload. Publish related files together to avoid a transient valid mixture of versions.

Every meal window must have 0 <= startMinute < endMinute <= 1440 and startMinute <= idealMinute < endMinute. The whole meal and adjacent route estimates must fit an available interval; existing appointments are preserved. Estimates are not proof of road access, business hours or restaurant opening status.

`attraction_aliases.json` supplies catalog-specific aliases only for entities in the active catalog; aliases reload when the matcher is initialized, not through the dining provider. Historical `dining_brands.txt` is retained for compatibility with the preexisting workspace assets but is no longer the production seed source.

The server LLM intent extraction contract adds cuisineOrPlace, genericDiningCraving (JSON Boolean or null), and timeSlot. The existing deployment setting PLANNER_LLM_INTENT_ENABLED controls model extraction; enabling it requires an available model. No model or malformed output uses the existing deterministic fallback and does not guarantee arbitrary food/place recognition. No browser-provided model result is trusted without server validation.

Flavor aliases optionally include `searchKeyword` and `poiTypeKeywords`. A configured search keyword is used for provider queries; configured POI type keywords must match the returned provider type. This prevents a broad restaurant result from being relabeled as dessert solely because dessert was requested. Local curated menu matching remains separate. Missing optional fields preserve the existing policy; malformed fields reject the entire reload. `TEA_BREAK` is a scheduling window and does not overwrite the food category in user feedback.
