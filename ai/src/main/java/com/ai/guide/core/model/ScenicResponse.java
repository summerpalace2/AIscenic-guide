package com.ai.guide.core.model;
import java.util.List;

public record ScenicResponse(
        String welcomeMessage,     // 对应前端 data.welcomeMessage
        List<ScenicItem> dataList, // 对应前端 data.dataList
        String closingTips         // 对应前端 data.closingTips
) {}