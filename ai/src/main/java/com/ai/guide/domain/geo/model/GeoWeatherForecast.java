package com.ai.guide.domain.geo.model;

import java.util.List;

public record GeoWeatherForecast(String reportTime, List<GeoWeatherCast> casts, boolean available) {
    public GeoWeatherForecast {
        casts = casts == null ? List.of() : List.copyOf(casts);
    }
}
