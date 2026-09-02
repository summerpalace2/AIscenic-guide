package com.ai.guide.domain.memory.model;

/** A user-owned, confirmed long-term travel memory. */
public record TravelMemory(
        String id,
        String category,
        String content,
        String sourceType,
        String sourceRef,
        String status,
        double confidence,
        long createdAt,
        long updatedAt) {
}
