package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;

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
        return buildPoiTextRequest(keywords, city, "navi");
    }

    public URI buildPoiTextRequest(String keywords, String city, String showFields) {
        return request("/v5/place/text")
                .queryParam("keywords", required(keywords, "keywords"))
                .queryParamIfPresent("city", optional(city))
                .queryParam("citylimit", "true")
                .queryParam("page_size", "10")
                .queryParamIfPresent("show_fields", optional(showFields))
                .build()
                .toUri();
    }

    public URI buildPoiDetailRequest(String poiId) {
        return buildPoiDetailRequest(poiId, "navi");
    }

    public URI buildPoiDetailRequest(String poiId, String showFields) {
        return request("/v5/place/detail")
                .queryParam("id", required(poiId, "id"))
                .queryParamIfPresent("show_fields", optional(showFields))
                .build()
                .toUri();
    }

    public URI buildGeocodeRequest(String address, String city) {
        return request("/v3/geocode/geo")
                .queryParam("address", required(address, "address"))
                .queryParamIfPresent("city", optional(city))
                .build()
                .toUri();
    }

    public URI buildReverseGeocodeRequest(String location) {
        return buildReverseGeocodeRequest(location, "all");
    }

    public URI buildReverseGeocodeRequest(String location, String extensions) {
        return request("/v3/geocode/regeo")
                .queryParam("location", required(location, "location"))
                .queryParamIfPresent("extensions", optional(extensions))
                .build()
                .toUri();
    }

    public URI buildWalkingRequest(String origin, String destination) {
        return buildWalkingRequest(origin, destination, "cost,navi,polyline");
    }

    public URI buildWalkingRequest(String origin, String destination, String showFields) {
        return request("/v5/direction/walking")
                .queryParam("origin", required(origin, "origin"))
                .queryParam("destination", required(destination, "destination"))
                .queryParamIfPresent("show_fields", optional(showFields))
                .build()
                .toUri();
    }

    public URI buildTransitRequest(String origin, String destination, String city) {
        return buildTransitRequest(origin, destination, city, "0", "", "");
    }

    public URI buildTransitRequest(String origin, String destination, String city, String strategy) {
        return buildTransitRequest(origin, destination, city, strategy, "", "");
    }

    public URI buildTransitRequest(String origin, String destination, String city, String strategy,
                                   String originPoi, String destinationPoi) {
        return request("/v5/direction/transit/integrated")
                .queryParam("origin", required(origin, "origin"))
                .queryParam("destination", required(destination, "destination"))
                .queryParam("city", required(city, "city"))
                .queryParamIfPresent("originpoi", optional(originPoi))
                .queryParamIfPresent("destinationpoi", optional(destinationPoi))
                .queryParamIfPresent("strategy", optional(strategy))
                .queryParamIfPresent("show_fields", optional("cost,polyline"))
                .build()
                .toUri();
    }

    public URI buildTransitFallbackRequest(String origin, String destination, String city) {
        return request("/v3/direction/transit/integrated")
                .queryParam("origin", required(origin, "origin"))
                .queryParam("destination", required(destination, "destination"))
                .queryParam("city", required(city, "city"))
                .queryParam("strategy", "0")
                .queryParam("nightflag", "0")
                .queryParam("extensions", "base")
                .build()
                .toUri();
    }

    public URI buildInputTipsRequest(String keywords, String city) {
        return request("/v3/assistant/inputtips")
                .queryParam("keywords", required(keywords, "keywords"))
                .queryParamIfPresent("city", optional(city))
                .build()
                .toUri();
    }

    public URI buildWeatherRequest(String city) {
        return buildWeatherRequest(city, "all");
    }

    public URI buildWeatherRequest(String city, String extensions) {
        return request("/v3/weather/weatherInfo")
                .queryParam("city", required(city, "city"))
                .queryParamIfPresent("extensions", optional(extensions))
                .build()
                .toUri();
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

    private static java.util.Optional<String> optional(String value) {
        return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value.trim());
    }
}
