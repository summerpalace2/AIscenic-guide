package com.ai.guide.domain.geo.model;

/** Shared parsing and validation for longitude,latitude values from geo providers. */
public final class GeoCoordinates {
    private GeoCoordinates() { }

    public static GeoCoordinate parse(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.trim().split(",", -1);
        if (parts.length != 2) return null;
        try {
            GeoCoordinate coordinate = new GeoCoordinate(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            return coordinate.isValid() ? coordinate : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
