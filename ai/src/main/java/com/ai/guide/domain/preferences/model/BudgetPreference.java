package com.ai.guide.domain.preferences.model;

import java.math.BigDecimal;

/**
 * 预算偏好强类型值对象
 *
 * 所属领域：domain.preferences.model（用户偏好画像领域模型）
 */
public record BudgetPreference(Level level, BigDecimal maxAmount, String currency) {

    public enum Level {
        UNSPECIFIED,
        LIMITED,
        LOW,
        MEDIUM,
        HIGH,
        AMOUNT
    }

    public BudgetPreference {
        level = level == null ? Level.UNSPECIFIED : level;
        if (maxAmount != null && maxAmount.signum() < 0) {
            throw new IllegalArgumentException("预算金额不能为负数");
        }
        if (level == Level.AMOUNT && maxAmount == null) {
            throw new IllegalArgumentException("金额预算必须提供 maxAmount");
        }
        if (maxAmount != null && currency != null && currency.isBlank()) {
            throw new IllegalArgumentException("提供预算金额时 currency 不能为空");
        }
        currency = currency == null ? "" : currency.trim().toUpperCase();
        if (currency.length() > 8) {
            throw new IllegalArgumentException("预算货币代码过长");
        }
    }

    public static BudgetPreference unspecified() {
        return new BudgetPreference(Level.UNSPECIFIED, null, "");
    }
}
