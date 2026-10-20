package com.ai.guide.domain.planner.service.strategy;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ChongqingTopographyStrategy implements TopographyStrategyPort {
    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public ChongqingTopographyStrategy(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    /** Compatibility constructor for isolated fixtures. Production uses the configured taxonomy port. */
    public ChongqingTopographyStrategy() { this(TaxonomyPort.empty()); }

    @Override public String supportedCity() { return "CHONGQING"; }

    @Override public ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_PLAIN;
        return resolveElevationTier(attraction.getId(), attraction.getName(), attraction.getDistrict(), attraction.getLocation());
    }

    @Override public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        String normalizedId = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String placeName = name == null ? "" : name;
        String placeDistrict = district == null ? "" : district;
        if (matches(placeName, "chongqingTopoHighNameTokens") || matches(normalizedId, "chongqingTopoHighIdTokens"))
            return ElevationTier.HIGH_ALTITUDE;
        if (matches(placeName, "chongqingTopoLowlandNameTokens") || matches(normalizedId, "chongqingTopoLowlandIdTokens"))
            return ElevationTier.LOWLAND_VALLEY;
        if (matches(placeName, "chongqingTopoUpperRidgeNameTokens") || matches(normalizedId, "chongqingTopoUpperIdTokens"))
            return ElevationTier.UPPER_PLATEAU;
        if (matches(placeDistrict, "chongqingTopoUpperDistricts") || matches(placeName, "chongqingTopoUpperNameTokens"))
            return ElevationTier.UPPER_PLATEAU;
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override public BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to) {
        if (from == null || to == null) return BarrierCrossingType.NONE;
        return detectBarrierCrossing(from.getDistrict(), to.getDistrict());
    }

    private BarrierCrossingType detectBarrierCrossing(String from, String to) {
        String source = from == null ? "" : from.trim();
        String target = to == null ? "" : to.trim();
        if (source.isBlank() || target.isBlank() || source.equals(target)) return BarrierCrossingType.NONE;
        if (matchesPair(source, target, "chongqingTopoConfluenceDistrictPairs")) return BarrierCrossingType.CONFLUENCE_BOTH;
        if (crosses(source, target, "chongqingTopoJialingSouthDistricts", "chongqingTopoJialingNorthDistricts"))
            return BarrierCrossingType.MAJOR_RIVER_PRIMARY;
        if (crosses(source, target, "chongqingTopoYangtzeNorthDistricts", "chongqingTopoYangtzeSouthDistricts"))
            return BarrierCrossingType.MAJOR_RIVER_SECONDARY;
        return BarrierCrossingType.NONE;
    }

    @Override public ElevationFlow detectElevationFlow(Attraction from, Attraction to) {
        if (from == null || to == null) return ElevationFlow.FLAT;
        String source = from.getName() == null ? "" : from.getName();
        String target = to.getName() == null ? "" : to.getName();
        if (matchesPair(source, target, "chongqingTopoDownhillPairs")) return ElevationFlow.DOWNHILL;
        if (matchesPair(target, source, "chongqingTopoDownhillPairs")) return ElevationFlow.UPHILL;
        ElevationTier sourceTier = resolveElevationTier(from);
        ElevationTier targetTier = resolveElevationTier(to);
        if (sourceTier.getRank() > targetTier.getRank()) return ElevationFlow.DOWNHILL;
        if (sourceTier.getRank() < targetTier.getRank()) return ElevationFlow.UPHILL;
        return ElevationFlow.FLAT;
    }

    @Override public double calculateZigZagPenalty(List<Attraction> history, Attraction candidate) {
        if (history == null || history.size() < 2 || candidate == null) return 0;
        Attraction previous = history.get(history.size() - 2);
        Attraction last = history.get(history.size() - 1);
        ElevationTier candidateTier = resolveElevationTier(candidate);
        ElevationTier lastTier = resolveElevationTier(last);
        ElevationTier previousTier = resolveElevationTier(previous);
        boolean bounce = (previousTier == ElevationTier.HIGH_ALTITUDE && lastTier == ElevationTier.LOWLAND_VALLEY
                && candidateTier == ElevationTier.HIGH_ALTITUDE)
                || (previousTier == ElevationTier.LOWLAND_VALLEY && lastTier == ElevationTier.HIGH_ALTITUDE
                && candidateTier == ElevationTier.LOWLAND_VALLEY);
        double penalty = bounce ? configuredPenalty("MOUNTAIN_BOUNCE") : 0;
        BarrierCrossingType first = detectBarrierCrossing(previous, last);
        BarrierCrossingType next = detectBarrierCrossing(last, candidate);
        if (first != BarrierCrossingType.NONE && first == next) penalty += configuredPenalty("RIVER_BOUNCE");
        return penalty;
    }

    @Override public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";
        String source = from.getName() == null ? "" : from.getName();
        String target = to.getName() == null ? "" : to.getName();
        String direct = configuredHint(source, target);
        if (!direct.isBlank()) return direct;
        direct = configuredHint(target, source);
        if (!direct.isBlank()) return direct;
        return switch (detectBarrierCrossing(from, to)) {
            case MAJOR_RIVER_PRIMARY -> configuredHint("CROSSING_PRIMARY", "*");
            case MAJOR_RIVER_SECONDARY -> configuredHint("CROSSING_SECONDARY", "*");
            case CONFLUENCE_BOTH -> configuredHint("CROSSING_BOTH", "*");
            default -> "";
        };
    }

    @Override public boolean allowStartPlaceProximity(Attraction previous, Attraction candidate) {
        return previous == null || detectBarrierCrossing(previous, candidate) == BarrierCrossingType.NONE;
    }

    private boolean crosses(String source, String target, String firstBankKey, String secondBankKey) {
        return (matches(source, firstBankKey) && matches(target, secondBankKey))
                || (matches(source, secondBankKey) && matches(target, firstBankKey));
    }

    private boolean matches(String value, String hintKey) {
        if (value == null || value.isBlank()) return false;
        return taxonomy.getHints(hintKey).stream().anyMatch(value::contains);
    }

    private boolean matchesPair(String source, String target, String hintKey) {
        for (String encoded : taxonomy.getHints(hintKey)) {
            String[] pair = encoded.split("\\|", 2);
            if (pair.length == 2 && matchesAny(source, pair[0]) && matchesAny(target, pair[1])) return true;
        }
        return false;
    }

    private boolean matchesAny(String value, String alternatives) {
        if (value == null || alternatives == null) return false;
        for (String token : alternatives.split(",")) {
            String clean = token.trim();
            if (!clean.isBlank() && value.contains(clean)) return true;
        }
        return false;
    }

    private double configuredPenalty(String key) {
        for (String value : taxonomy.getHints("chongqingTopoZigzagPenalties")) {
            String[] fields = value.split("\\|", 2);
            if (fields.length == 2 && fields[0].equals(key)) {
                try { return Double.parseDouble(fields[1]); } catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }

    private String configuredHint(String source, String target) {
        for (String encoded : taxonomy.getHints("chongqingTopoTransitHintRules")) {
            String[] fields = encoded.split("\\|", 3);
            if (fields.length != 3) continue;
            boolean matchesFrom = "*".equals(fields[0]) || matchesAny(source, fields[0]);
            boolean matchesTo = "*".equals(fields[1]) || matchesAny(target, fields[1]);
            if (matchesFrom && matchesTo) return fields[2];
        }
        return "";
    }
}
