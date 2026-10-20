package com.ai.guide.domain.geo.model;

/** Shared validation for provider-neutral longitude/latitude values. */
public final class GeoCoordinateRules {
    private GeoCoordinateRules() {}

    public static boolean isValid(String value) {
        if (value == null) return false;
        String[] parts = value.split(",", -1);
        if (parts.length != 2) return false;
        try {
            return new GeoCoordinate(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim())).isValid();
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
