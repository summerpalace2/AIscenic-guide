package com.ai.guide.domain.geo.model;

/** Provider-neutral geographic coordinate in longitude/latitude order. */
public record GeoCoordinate(double longitude, double latitude) {
    public boolean isValid() {
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
    }
}
