package com.ai.guide.domain.geo.model;

/**
 * 地理与路线服务调用状态枚举
 *
 * 架构定位：COLA 领域模型 (Domain Model)
 * 职责：统一标识外部地理或路线提供者的查询结果状态。
 */
public enum GeoOutcomeStatus {
    SUCCESS, UNAVAILABLE, TIMEOUT, INVALID, UNSUPPORTED, RATE_LIMITED, AUTH_ERROR, TLS_ERROR
}
