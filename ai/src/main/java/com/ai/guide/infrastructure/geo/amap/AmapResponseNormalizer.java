package com.ai.guide.infrastructure.geo.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoRouteSegment;
import com.ai.guide.domain.geo.model.GeoWeatherForecast;
import com.ai.guide.domain.geo.model.GeoWeatherCast;
import com.ai.guide.domain.geo.model.GeoCoordinates;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Converts AMap Web Service JSON into provider-neutral records used by the
 * candidate and route verification stages.
 */
/**
 * 高德 Web Service 响应数据归一化转换器
 *
 * 所属模块：infrastructure.geo.amap
 */
public final class AmapResponseNormalizer {

    private AmapResponseNormalizer() {
    }

    public static List<GeoPoiCandidate> normalizePois(JsonNode response) {
        requireSuccess(response);
        JsonNode pois = response.path("pois");
        if (!pois.isArray()) return List.of();

        List<GeoPoiCandidate> result = new ArrayList<>();
        for (JsonNode poi : pois) {
            JsonNode navigation = poi.path("navi");
            Photo photo = firstPhoto(poi.path("photos"));
            JsonNode business = poi.path("business");
            Double rating = parseRating(text(business, "rating"));
            String rectag = text(business, "rectag");
            String keytag = text(business, "keytag");
            String tag = text(business, "tag");
            List<String> tags = new ArrayList<>();
            if (rectag != null && !rectag.isBlank()) tags.add(rectag);
            if (keytag != null && !keytag.isBlank() && !tags.contains(keytag)) tags.add(keytag);
            if (tag != null && !tag.isBlank() && !tags.contains(tag)) tags.add(tag);
            String heatTag = String.join(";", tags);

            String typecode = text(poi, "typecode");
            String businessArea = text(business, "business_area");
            if (businessArea == null || businessArea.isBlank()) businessArea = text(poi, "business_area");

            result.add(new GeoPoiCandidate(
                    text(poi, "id"),
                    text(poi, "name"),
                    coordinate(text(poi, "location")),
                    text(poi, "address"),
                    text(poi, "type"),
                    firstGeoCoordinate(coordinate(text(poi, "entr_location")),
                            coordinate(text(navigation, "entr_location"))),
                    firstGeoCoordinate(coordinate(text(poi, "exit_location")),
                            coordinate(text(navigation, "exit_location"))),
                    text(poi, "navi_poiid"),
                    photo.url(),
                    photo.title(),
                    rating,
                    heatTag,
                    typecode,
                    businessArea,
                    text(poi, "cityname")
            ));
        }
        return List.copyOf(result);
    }

    public static GeocodeResult normalizeGeocode(JsonNode response) {
        requireSuccess(response);
        JsonNode geocodes = response.path("geocodes");
        JsonNode geocode = first(geocodes);
        if (geocode == null) return new GeocodeResult(null, null, null, null, false);
        GeoCoordinate location = coordinate(text(geocode, "location"));
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

    public static GeoRouteSegment normalizeWalking(JsonNode response) {
        requireSuccess(response);
        JsonNode path = first(response.path("route").path("paths"));
        return normalizeRoute("WALKING", path, true);
    }

    public static GeoRouteSegment normalizeTransit(JsonNode response) {
        requireSuccess(response);
        JsonNode route = response.path("route");
        JsonNode transit = first(route.path("transits"));
        if (transit == null) transit = first(route.path("paths"));
        if (transit == null) {
            return new GeoRouteSegment("TRANSIT", null, null, null, 0, List.of(), null, false);
        }

        Integer distance = integer(transit, "distance");
        Integer duration = firstNonNull(integer(transit, "duration"), integer(transit.path("cost"), "duration"));
        Integer walkingDistance = firstNonNull(integer(transit, "walking_distance"), walkingDistance(transit));
        List<Integer> walkTypes = new ArrayList<>();
        collectWalkTypes(transit, walkTypes);
        int stepCount = countSteps(transit);
        return new GeoRouteSegment(
                "TRANSIT", distance, duration, walkingDistance, stepCount,
                List.copyOf(walkTypes), routePolyline(transit),
                distance != null && duration != null
        );
    }

    public static GeoWeatherForecast normalizeWeather(JsonNode response) {
        requireSuccess(response);
        JsonNode forecast = first(response.path("forecasts"));
        if (forecast == null) return new GeoWeatherForecast(null, List.of(), false);
        List<GeoWeatherCast> casts = new ArrayList<>();
        JsonNode castArray = forecast.path("casts");
        if (castArray.isArray()) {
            for (JsonNode cast : castArray) {
                casts.add(new GeoWeatherCast(
                        text(cast, "date"),
                        text(cast, "dayweather"),
                        text(cast, "nightweather"),
                        text(cast, "daytemp"),
                        text(cast, "nighttemp")
                ));
            }
        }
        return new GeoWeatherForecast(text(forecast, "reporttime"), List.copyOf(casts), !casts.isEmpty());
    }

    private static GeoRouteSegment normalizeRoute(String mode, JsonNode path, boolean walkingDistanceIsTotal) {
        if (path == null) return new GeoRouteSegment(mode, null, null, null, 0, List.of(), null, false);
        Integer distance = integer(path, "distance");
        Integer duration = firstNonNull(integer(path, "duration"), integer(path.path("cost"), "duration"));
        List<Integer> walkTypes = new ArrayList<>();
        collectWalkTypes(path, walkTypes);
        int stepCount = countSteps(path);
        return new GeoRouteSegment(
                mode,
                distance,
                duration,
                walkingDistanceIsTotal ? distance : integer(path, "walking_distance"),
                stepCount,
                List.copyOf(walkTypes),
                routePolyline(path),
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

    private static GeoCoordinate firstGeoCoordinate(GeoCoordinate first, GeoCoordinate fallback) {
        return first != null ? first : fallback;
    }

    private static GeoCoordinate coordinate(String value) {
        return GeoCoordinates.parse(value);
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

    private static Integer firstNonNull(Integer first, Integer second) {
        return first == null ? second : first;
    }

    private static Integer walkingDistance(JsonNode transit) {
        JsonNode segments = transit == null ? null : transit.path("segments");
        if (segments == null || !segments.isArray() || segments.isEmpty()) return null;
        int total = 0;
        boolean foundWalkingSegment = false;
        for (JsonNode segment : segments) {
            JsonNode walking = segment.path("walking");
            if (walking.isMissingNode() || walking.isNull()) continue;
            foundWalkingSegment = true;
            Integer distance = integer(walking, "distance");
            if (distance == null || distance < 0) return null;
            total += distance;
        }
        return foundWalkingSegment ? total : null;
    }

    private static String routePolyline(JsonNode route) {
        String direct = text(route, "polyline");
        if (direct != null && !direct.isBlank()) return direct;
        List<String> polylines = new ArrayList<>();
        collectPolylines(route, polylines);
        return polylines.isEmpty() ? null : String.join(";", polylines);
    }

    private static void collectPolylines(JsonNode node, List<String> result) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            String polyline = text(node, "polyline");
            if (polyline != null && !polyline.isBlank()) result.add(polyline.trim());
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) collectPolylines(fields.next().getValue(), result);
        } else if (node.isArray()) {
            for (JsonNode child : node) collectPolylines(child, result);
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

    private static Double parseRating(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double r = Double.parseDouble(value.trim());
            return r > 0 ? r : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record Photo(String url, String title) {
    }

    public record GeocodeResult(
            String formattedAddress,
            GeoCoordinate location,
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
