package com.ai.guide.domain.planner.service;

/** Provider category facts, separate from whether a venue fits a particular trip. */
final class AmapPoiTaxonomy {
    private AmapPoiTaxonomy() {}

    static boolean isValidCode(String code) {
        return code != null && code.length() == 6 && code.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    static boolean isCulturalVenue(String code) {
        if (code == null || "140201".equals(code)) return false; // indoor booth
        return code.startsWith("1401") || code.startsWith("1402")
                || code.startsWith("1404") || code.startsWith("1405")
                || code.startsWith("1406") || code.startsWith("1407") || code.startsWith("1408");
    }

    static boolean isScenicVenue(String code) {
        return code != null && code.startsWith("11") && !"110106".equals(code);
    }
}
