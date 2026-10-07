package com.ai.guide.domain.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/** Shared limits for persisted plans and PDF exports, checked before expensive rendering. */
@Component
public class TripPlanLimits {
    private final ObjectMapper objectMapper;
    @Value("${trip.plan.max-days:30}")
    private int maxDays = 30;
    @Value("${trip.plan.max-stops-per-day:40}")
    private int maxStopsPerDay = 40;
    @Value("${trip.plan.max-total-stops:500}")
    private int maxTotalStops = 500;
    @Value("${trip.plan.max-text-chars:20000}")
    private int maxTextChars = 20000;
    @Value("${trip.plan.max-json-bytes:2097152}")
    private long maxJsonBytes = 2097152;

    public TripPlanLimits(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(Map<String, Object> plan) {
        if (plan == null || !(plan.get("days") instanceof List<?> days)) {
            throw new IllegalArgumentException("正式行程必须包含完整的 days 数组；修改标题也需提交完整计划");
        }
        if (days.size() > Math.max(1, maxDays)) {
            throw new IllegalArgumentException("行程最多支持 " + maxDays + " 天，请拆分为多个行程");
        }
        long totalStops = 0;
        for (Object value : days) {
            if (!(value instanceof Map<?, ?> day) || !(day.get("stops") instanceof List<?> stops)) {
                throw new IllegalArgumentException("行程每日安排必须包含 stops 数组");
            }
            if (stops.size() > Math.max(1, maxStopsPerDay)) {
                throw new IllegalArgumentException("每天最多支持 " + maxStopsPerDay + " 个站点，请精简安排");
            }
            totalStops += stops.size();
            if (totalStops > Math.max(1, maxTotalStops)) {
                throw new IllegalArgumentException("单个行程最多支持 " + maxTotalStops + " 个站点，请拆分行程");
            }
        }
        validateText(plan, 0);
        BoundedOutput output = new BoundedOutput(Math.max(1, maxJsonBytes));
        try {
            // Count UTF-8 JSON without allocating another full serialized plan.
            objectMapper.writeValue(output, plan);
        } catch (IOException invalid) {
            if (output.exceeded) {
                throw new IllegalArgumentException("行程内容超过 " + maxJsonBytes + " 字节上限，请精简描述或拆分行程");
            }
            throw new IllegalArgumentException("行程 JSON 数据无效", invalid);
        }
    }

    private void validateText(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("行程数据层级过深，请简化内容");
        if (value instanceof String text) {
            if (text.length() > Math.max(1, maxTextChars)) {
                throw new IllegalArgumentException("行程单段文字最多支持 " + maxTextChars + " 字符，请精简描述");
            }
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                validateText(entry.getKey(), depth + 1);
                validateText(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) validateText(item, depth + 1);
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final long limit;
        private long written;
        private boolean exceeded;
        private BoundedOutput(long limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException { reserve(1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); }
        private void reserve(long count) throws IOException {
            if (count > limit - written) {
                exceeded = true;
                throw new IOException("Plan JSON exceeds configured limit");
            }
            written += count;
        }
    }
}
