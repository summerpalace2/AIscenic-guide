package com.ai.guide.infrastructure.rag.amap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 高德开放平台 Web API 客户端封装
 *
 * 所属领域：domain.rag.pipeline（知识与数据管线层）
 */
public class AmapWebServiceClient {

    public static final String DEFAULT_BASE_URL = "https://restapi.amap.com";

    private final String baseUrl;
    private final String apiKey;
    private final RestTemplate restTemplate;

    public AmapWebServiceClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, new RestTemplate());
    }

    public AmapWebServiceClient(String baseUrl, String apiKey, RestTemplate restTemplate) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.restTemplate = restTemplate == null ? new RestTemplate() : restTemplate;
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public URI buildPoiTextRequest(String keywords, String city) {
        return buildPoiTextRequest(keywords, city, null, "navi");
    }

    public URI buildPoiTextRequest(String keywords, String city, String showFields) {
        return buildPoiTextRequest(keywords, city, null, showFields);
    }

    public URI buildPoiTextRequest(String keywords, String city, String types, String showFields) {
        return encodedUri(request("/v5/place/text")
                .queryParam("keywords", required(keywords, "keywords"))
                .queryParamIfPresent("city", optional(city))
                .queryParam("citylimit", "true")
                .queryParam("page_size", "20")
                .queryParamIfPresent("types", optional(types))
                .queryParamIfPresent("show_fields", optional(showFields))
                );
    }

    /**
     * Searches POIs around a resolved coordinate. The caller owns the semantic
     * query; this client only builds the provider request and does not contain
     * attraction names or city-specific routing rules.
     */
    public URI buildPoiAroundRequest(String location, int radiusMeters, String types,
                                     String keywords, String showFields) {
        return buildPoiAroundRequest(location, radiusMeters, types, keywords, showFields, 1, "weight");
    }

    public URI buildPoiAroundRequest(String location, int radiusMeters, String types,
                                     String keywords, String showFields, int pageNum) {
        return buildPoiAroundRequest(location, radiusMeters, types, keywords, showFields, pageNum, "weight");
    }

    public URI buildPoiAroundRequest(String location, int radiusMeters, String types,
                                     String keywords, String showFields, int pageNum, String sortrule) {
        return encodedUri(request("/v5/place/around")
                .queryParam("location", required(location, "location"))
                .queryParam("radius", Math.max(1, radiusMeters))
                .queryParam("sortrule", (sortrule == null || sortrule.isBlank()) ? "weight" : sortrule)
                .queryParam("page_size", "25")
                .queryParam("page_num", Math.max(1, pageNum))
                .queryParamIfPresent("types", optional(types))
                .queryParamIfPresent("keywords", optional(keywords))
                .queryParamIfPresent("show_fields", optional(showFields))
                );
    }

    public URI buildPoiDetailRequest(String poiId) {
        return buildPoiDetailRequest(poiId, "navi");
    }

    public URI buildPoiDetailRequest(String poiId, String showFields) {
        return encodedUri(request("/v5/place/detail")
                .queryParam("id", required(poiId, "id"))
                .queryParamIfPresent("show_fields", optional(showFields))
                );
    }

    public URI buildGeocodeRequest(String address, String city) {
        return encodedUri(request("/v3/geocode/geo")
                .queryParam("address", required(address, "address"))
                .queryParamIfPresent("city", optional(city))
                );
    }

    public URI buildReverseGeocodeRequest(String location) {
        return buildReverseGeocodeRequest(location, "all");
    }

    public URI buildReverseGeocodeRequest(String location, String extensions) {
        return encodedUri(request("/v3/geocode/regeo")
                .queryParam("location", required(location, "location"))
                .queryParamIfPresent("extensions", optional(extensions))
                );
    }

    public URI buildWalkingRequest(String origin, String destination) {
        return buildWalkingRequest(origin, destination, "cost,navi,polyline");
    }

    public URI buildWalkingRequest(String origin, String destination, String showFields) {
        return encodedUri(request("/v5/direction/walking")
                .queryParam("origin", required(origin, "origin"))
                .queryParam("destination", required(destination, "destination"))
                .queryParamIfPresent("show_fields", optional(showFields))
                );
    }

    public URI buildTransitRequest(String origin, String destination, String city) {
        return buildTransitFallbackRequest(origin, destination, city);
    }

    public URI buildTransitRequest(String origin, String destination, String city, String strategy) {
        return buildTransitFallbackRequest(origin, destination, city, strategy);
    }

    /** V5 requires citycode on both ends; POI IDs are meaningful only as a pair. */
    public URI buildTransitRequest(String origin, String destination, String city, String strategy,
                                   String originPoi, String destinationPoi) {
        return buildTransitFallbackRequest(origin, destination, city, strategy);
    }

    public URI buildTransitRequest(String origin, String destination, String city1, String city2, String strategy,
                                   String originPoi, String destinationPoi) {
        var builder = request("/v5/direction/transit/integrated")
                .queryParam("origin", required(origin, "origin"))
                .queryParam("destination", required(destination, "destination"))
                .queryParam("city1", requiredCityCode(city1, "city1"))
                .queryParam("city2", requiredCityCode(city2, "city2"))
                .queryParamIfPresent("strategy", optional(strategy))
                .queryParamIfPresent("show_fields", optional("cost,polyline"));
        if (originPoi != null && !originPoi.isBlank() && destinationPoi != null && !destinationPoi.isBlank()) {
            builder.queryParam("originpoi", originPoi.trim())
                    .queryParam("destinationpoi", destinationPoi.trim());
        }
        return encodedUri(builder);
    }

    public URI buildTransitFallbackRequest(String origin, String destination, String city) {
        return buildTransitFallbackRequest(origin, destination, city, "0");
    }

    public URI buildTransitFallbackRequest(String origin, String destination, String city, String strategy) {
        return encodedUri(request("/v3/direction/transit/integrated")
                .queryParam("origin", required(origin, "origin"))
                .queryParam("destination", required(destination, "destination"))
                .queryParam("city", required(city, "city"))
                .queryParam("strategy", optional(strategy).orElse("0"))
                .queryParam("nightflag", "0")
                .queryParam("extensions", "base")
                );
    }

    public URI buildInputTipsRequest(String keywords, String city) {
        return encodedUri(request("/v3/assistant/inputtips")
                .queryParam("keywords", required(keywords, "keywords"))
                .queryParamIfPresent("city", optional(city))
                );
    }

    public URI buildWeatherRequest(String city) {
        return buildWeatherRequest(city, "all");
    }

    public URI buildWeatherRequest(String city, String extensions) {
        return encodedUri(request("/v3/weather/weatherInfo")
                .queryParam("city", required(city, "city"))
                .queryParamIfPresent("extensions", optional(extensions))
                );
    }

    /** Executes a previously built request. The response is intentionally raw JSON for normalization. */
    public JsonNode get(URI requestUri) {
        if (requestUri == null) throw new IllegalArgumentException("requestUri must not be null");
        requireApiKey();
        return restTemplate.getForObject(requestUri, JsonNode.class);
    }

    private UriComponentsBuilder request(String path) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(path)
                .queryParam("key", requireApiKey());
    }

    /** Strictly encode query values once; reserved characters remain data, not extra parameters. */
    private static URI encodedUri(UriComponentsBuilder builder) {
        var parameters = builder.build().getQueryParams();
        var templates = new org.springframework.util.LinkedMultiValueMap<String, String>();
        var values = new java.util.LinkedHashMap<String, Object>();
        parameters.forEach((name, entries) -> entries.forEach(value -> {
            String variable = "queryValue" + values.size();
            templates.add(name, "{" + variable + "}");
            values.put(variable, value);
        }));
        return builder.replaceQueryParams(templates).encode(StandardCharsets.UTF_8).buildAndExpand(values).toUri();
    }

    private String requireApiKey() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("AMAP_WEB_SERVICE_KEY is not configured");
        }
        return apiKey;
    }

    private static String normalizeBaseUrl(String value) {
        String candidate = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        while (candidate.endsWith("/")) candidate = candidate.substring(0, candidate.length() - 1);
        return candidate;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String requiredCityCode(String value, String name) {
        String code = required(value, name);
        if (!code.matches("\\d{3,4}")) throw new IllegalArgumentException(name + " must be an AMap citycode");
        return code;
    }

    private static java.util.Optional<String> optional(String value) {
        return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value.trim());
    }
}
