package com.ai.guide.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 景区查询响应实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenicResponse {

    /** 景点列表 */
    private List<ScenicItem> items;

    /** 总结摘要 */
    private String summary;
}
