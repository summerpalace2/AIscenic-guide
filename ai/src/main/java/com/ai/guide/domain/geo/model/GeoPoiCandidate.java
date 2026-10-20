package com.ai.guide.domain.geo.model;

/** Provider-neutral point-of-interest result used across geo-backed domains. */
public record GeoPoiCandidate(
        String poiId, String name, GeoCoordinate coordinate, String address, String type,
        GeoCoordinate navigationEntrance, GeoCoordinate navigationExit, String navigationPoiId,
        String photoUrl, String photoTitle, Double rating, String heatTag, String typecode, String businessArea,
        String cityName
) {
    public GeoPoiCandidate(String poiId, String name, GeoCoordinate coordinate, String address, String type,
                           GeoCoordinate navigationEntrance, GeoCoordinate navigationExit, String navigationPoiId,
                           String photoUrl, String photoTitle, Double rating, String heatTag, String typecode, String businessArea) {
        this(poiId, name, coordinate, address, type, navigationEntrance, navigationExit, navigationPoiId,
                photoUrl, photoTitle, rating, heatTag, typecode, businessArea, null);
    }
    public GeoPoiCandidate(String poiId, String name, GeoCoordinate coordinate, String address, String type,
                           GeoCoordinate navigationEntrance, GeoCoordinate navigationExit, String navigationPoiId,
                           String photoUrl, String photoTitle) {
        this(poiId, name, coordinate, address, type, navigationEntrance, navigationExit, navigationPoiId,
                photoUrl, photoTitle, null, null, null, null);
    }

    public GeoPoiCandidate(String poiId, String name, GeoCoordinate coordinate, String address, String type,
                           GeoCoordinate navigationEntrance, GeoCoordinate navigationExit, String navigationPoiId,
                           String photoUrl, String photoTitle, Double rating, String heatTag) {
        this(poiId, name, coordinate, address, type, navigationEntrance, navigationExit, navigationPoiId,
                photoUrl, photoTitle, rating, heatTag, null, null);
    }
}
