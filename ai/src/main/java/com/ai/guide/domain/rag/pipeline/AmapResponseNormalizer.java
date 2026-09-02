package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Converts AMap Web Service JSON into provider-neutral records used by the
 * candidate and route verification stages.
 */
public /**
 * 高德 Web Service 响应数据归一化转换器
 *
 * 所属领域：domain.rag.pipeline（知识与数据管线层）
 */
final class AmapResponseNormalizer {

    private AmapResponseNormalizer() {
    }

    public static List<PoiCandidate> normalizePois(JsonNode response) {
        requireSuccess(response);
        JsonNode pois = response.path("pois");
        if (!pois.isArray()) return List.of();

        List<PoiCandidate> result = new ArrayList<>();
        for (JsonNode poi : pois) {
            JsonNode navigation = poi.path("navi");
            Photo photo = firstPhoto(poi.path("photos"));
            result.add(new PoiCandidate(
                    text(poi, "id"),
                    text(poi, "name"),
                    coordinate(text(poi, "location")),
                    text(poi, "address"),
                    text(poi, "type"),
                    firstCoordinate(coordinate(text(poi, "entr_location")),
                            coordinate(text(navigation, "entr_location"))),
                    firstCoordinate(coordinate(text(poi, "exit_location")),
                            coordinate(text(navigation, "exit_location"))),
                    text(poi, "navi_poiid"),
                    photo.url(),
                    photo.title()
            ));
        }
        return List.copyOf(result);
    }

    public static GeocodeResult normalizeGeocode(JsonNode response) {
        requireSuccess(response);
        JsonNode geocodes = response.path("geocodes");
        JsonNode geocode = first(geocodes);
        if (geocode == null) return new GeocodeResult(null, null, null, null, false);
        Coordinate location = coordinate(text(geocode, "location"));
        return new GeocodeResult(
                text(geocode, "formatted_address"),
                location,
                text(geocode, "adcode"),
                text(geocode, "level"),
                location != null
        );
    }

    public static ReverseGeocodeResult normalizeReverseGeocode(JsonNode response) {
        requireSuccess(response);
        JsonNode regeocode = response.path("regeocode");
        JsonNode component = regeocode.path("addressComponent");
        return new ReverseGeocodeResult(
                text(regeocode, "formatted_address"),
                text(component, "province"),
                text(component, "city"),
                text(component, "district"),
                text(component, "township"),
                text(component.path("streetNumber"), "street")
        );
    }

    public static RouteResult normalizeWalking(JsonNode response) {
        requireSuccess(response);
        JsonNode path = first(response.path("route").path("paths"));
        return normalizeRoute("WALKING", path, true);
    }

    public static RouteResult normalizeTransit(JsonNode response) {
        requireSuccess(response);
        JsonNode route = response.path("route");
        JsonNode transit = first(route.path("transits"));
        if (transit == null) transit = first(route.path("paths"));
        if (transit == null) {
            return new RouteResult("TRANSIT", null, null, null, 0, List.of(), null, false);
        }

        Integer distance = integer(transit, "distance");
        Integer duration = integer(transit, "duration");
        Integer walkingDistance = integer(transit, "walking_distance");
        List<Integer> walkTypes = new ArrayList<>();
        collectWalkTypes(transit, walkTypes);
        int stepCount = countSteps(transit);
        return new RouteResult(
                "TRANSIT", distance, duration, walkingDistance, stepCount,
                List.copyOf(walkTypes), text(transit, "polyline"),
                distance != null && duration != null
        );
    }

    public static WeatherResult normalizeWeather(JsonNode response) {
        requireSuccess(response);
        JsonNode forecast = first(response.path("forecasts"));
        if (forecast == null) return new WeatherResult(null, List.of(), false);
        List<WeatherCast> casts = new ArrayList<>();
        JsonNode castArray = forecast.path("casts");
        if (castArray.isArray()) {
            for (JsonNode cast : castArray) {
                casts.add(new WeatherCast(
                        text(cast, "date"),
                        text(cast, "dayweather"),
                        text(cast, "nightweather"),
                        text(cast, "daytemp"),
                        text(cast, "nighttemp")
                ));
            }
        }
        return new WeatherResult(text(forecast, "reporttime"), List.copyOf(casts), !casts.isEmpty());
    }

    public static Coordinate parseCoordinate(String value) {
        return coordinate(value);
    }

    private static RouteResult normalizeRoute(String mode, JsonNode path, boolean walkingDistanceIsTotal) {
        if (path == null) return new RouteResult(mode, null, null, null, 0, List.of(), null, false);
        Integer distance = integer(path, "distance");
        Integer duration = integer(path, "duration");
        List<Integer> walkTypes = new ArrayList<>();
        collectWalkTypes(path, walkTypes);
        int stepCount = countSteps(path);
        return new RouteResult(
                mode,
                distance,
                duration,
                walkingDistanceIsTotal ? distance : integer(path, "walking_distance"),
                stepCount,
                List.copyOf(walkTypes),
                text(path, "polyline"),
                distance != null && duration != null
        );
    }

    private static void requireSuccess(JsonNode response) {
        if (response == null) throw new AmapResponseException("empty AMap response");
        String status = text(response, "status");
        if (!"1".equals(status)) {
            String info = text(response, "info");
            String code = text(response, "infocode");
            throw new AmapResponseException(info, code);
        }
    }

    private static JsonNode first(JsonNode array) {
        return array != null && array.isArray() && !array.isEmpty() ? array.get(0) : null;
    }

    private static Photo firstPhoto(JsonNode photos) {
        if (photos == null || !photos.isArray()) return new Photo(null, null);
        for (JsonNode photo : photos) {
            String url = text(photo, "url");
            if (url == null || url.isBlank()) url = text(photo, "src");
            if (url != null && !url.isBlank()) return new Photo(url, text(photo, "title"));
        }
        return new Photo(null, null);
    }

    private static Coordinate firstCoordinate(Coordinate first, Coordinate fallback) {
        return first != null ? first : fallback;
    }

    private static Coordinate coordinate(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split(",");
        if (parts.length != 2) return null;
        try {
            return new Coordinate(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integer(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try {
            return value.isNumber() ? value.asInt() : Integer.valueOf(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int countSteps(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        JsonNode steps = node.get("steps");
        if (steps != null && steps.isArray()) return steps.size();
        int count = 0;
        if (node.isArray()) {
            for (JsonNode child : node) count += countSteps(child);
        } else if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) count += countSteps(values.next());
        }
        return count;
    }

    private static void collectWalkTypes(JsonNode node, List<Integer> result) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            Integer walkType = integer(node, "walk_type");
            if (walkType != null) result.add(walkType);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) collectWalkTypes(fields.next().getValue(), result);
        } else if (node.isArray()) {
            for (JsonNode child : node) collectWalkTypes(child, result);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record Coordinate(double longitude, double latitude) {
    }

    public record PoiCandidate(
            String poiId,
            String name,
            Coordinate coordinate,
            String address,
            String type,
            Coordinate navigationEntrance,
            Coordinate navigationExit,
            String navigationPoiId,
            String photoUrl,
            String photoTitle
    ) {
    }

    private record Photo(String url, String title) {
    }

    public record GeocodeResult(
            String formattedAddress,
            Coordinate location,
            String adcode,
            String level,
            boolean resolved
    ) {
    }

    public record ReverseGeocodeResult(
            String formattedAddress,
            String province,
            String city,
            String district,
            String township,
            String street
    ) {
    }

    public record WeatherResult(
            String reportTime,
            List<WeatherCast> casts,
            boolean available
    ) {
    }

    public record WeatherCast(
            String date,
            String dayWeather,
            String nightWeather,
            String dayTemperature,
            String nightTemperature
    ) {
    }

    public record RouteResult(
            String mode,
            Integer distanceMeters,
            Integer durationSeconds,
            Integer walkingDistanceMeters,
            int stepCount,
            List<Integer> walkTypes,
            String polyline,
            boolean metricsComplete
    ) {
    }

    public static class AmapResponseException extends IllegalArgumentException {
        private final String info;
        private final String infocode;

        public AmapResponseException(String message) {
            this(message, null, null);
        }

        public AmapResponseException(String info, String infocode) {
            this("AMap request failed: " + (info == null ? "unknown error" : info)
                    + (infocode == null ? "" : " (" + infocode + ")"), info, infocode);
        }

        private AmapResponseException(String message, String info, String infocode) {
            super(message);
            this.info = info;
            this.infocode = infocode;
        }

        public String info() {
            return info;
        }

        public String infocode() {
            return infocode;
        }
    }
}
