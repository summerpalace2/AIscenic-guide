package com.ai.guide.domain.trip.gateway;

import java.util.Map;

/**
 * 行程手册 PDF 导出服务端口契约接口
 *
 * 所属领域：domain.trip.gateway（行程域端口层）
 * 架构职责：定义行程排版与 PDF 渲染生成的抽象标准，将报表渲染技术与领域层彻底解耦。
 */
public interface TripPlanPdfExportPort {

    /**
     * 将标准化行程排程计划数据渲染导出为高清 PDF 字节流
     *
     * @param plan 行程数据快照 Map
     * @return PDF 二进制文件字节数组
     */
    byte[] export(Map<String, Object> plan);
}
