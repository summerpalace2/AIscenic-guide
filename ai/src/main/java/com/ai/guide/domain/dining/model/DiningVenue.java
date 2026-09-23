package com.ai.guide.domain.dining.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 美食餐饮实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiningVenue {
    private String id;
    private String name;
    private String district;
    private String category;
    @Builder.Default
    private List<String> mealTypes = new ArrayList<>();
    private String location;
    private String specialtyDish;
    private String averageCost;
    private String duration;
    private String distanceDesc;
    private String summary;
    private String recommendationReason;
    private String spicinessLevel;
    @Builder.Default
    private Boolean elderFriendly = true;
    private String tone;
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    public boolean servesMeal(String mealType) {
        if (mealType == null || mealType.isBlank()) return true;
        if (mealTypes == null || mealTypes.isEmpty()) return true;
        for (String m : mealTypes) {
            if (m.equalsIgnoreCase(mealType)) return true;
        }
        return false;
    }
}
