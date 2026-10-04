package com.ai.guide.application.analytics;

import java.util.List;
import java.util.Map;

/**
 * 运营监控与服务看板应用服务门面 (Analytics Application Service)
 *
 * <p>所属架构层级：COLA 4.0 Application Layer（应用层）</p>
 * <p>核心职责：作为适配层（Controller）与领域核心层（AnalyticsService、OnlinePresenceService、PdfExportService）
 * 之间的用例编排中枢，负责统计周期解析、报告时间戳截取规整、流式 PDF 报表生成协同以及在线活跃用户状态感知。</p>
 */
public interface AnalyticsApplicationService {

    /**
     * 获取文旅智能导游大屏综合统计数据（核心 KPI 概览）
     *
     * @param period 统计周期范围，支持："today"（今日）、"week"（本周）、"month"（本月）、"all"（全量）
     * @return 包含服务人次、好评率、平均响应耗时、活跃会话等核心大盘指标的键值对 Map
     */
    Map<String, Object> getDashboard(String period);

    /**
     * 查询游客咨询的高频热点问题排行与关键词聚类列表
     *
     * @param period 统计时间周期（如 "today"）
     * @param topN   排行榜返回的最大条目数量限制（默认 10）
     * @return 按提问频次降序排列的热点问题数据列表
     */
    List<Map<String, Object>> getHotQuestions(String period, int topN);

    /**
     * 查询游客情感倾向分布的时序演变趋势（积极/中性/消极比例）
     *
     * @param startDate   统计起始日期，格式 "yyyy-MM-dd"，可选
     * @param endDate     统计截止日期，格式 "yyyy-MM-dd"，可选
     * @param granularity 聚合时间粒度，支持 "hour"（小时）、"day"（按天，默认）、"week"（周）、"month"（月）
     * @return 情绪时序分布点序列列表
     */
    List<Map<String, Object>> getSentimentTrend(String startDate, String endDate, String granularity);

    /**
     * 查询导游服务交互调用量的时序分布统计
     *
     * @param startDate   统计起始日期，格式 "yyyy-MM-dd"，可选
     * @param endDate     统计截止日期，格式 "yyyy-MM-dd"，可选
     * @param granularity 聚合时间粒度（默认 "day"）
     * @return 按时间桶聚合的服务交互次数列表
     */
    List<Map<String, Object>> getServiceCount(String startDate, String endDate, String granularity);

    /**
     * 分页查询已生成的历史运营分析报告列表
     *
     * @param page 分页页码，从 1 开始
     * @param size 每页记录条数限制
     * @return 包含报告摘要列表 (items) 与分页元数据的 Map 结构
     */
    Map<String, Object> listReports(int page, int size);

    /**
     * 根据报告唯一标识查询单篇运营报告的完整详细内容
     *
     * @param reportId 运营报告主键 ID
     * @return 报告详情字段 Map；若对应报告不存在则返回 null
     */
    Map<String, Object> getReport(String reportId);

    /**
     * 解析运营报告所属的基准日期字符串（截取前 10 位的 yyyy-MM-dd）
     *
     * @param report 报告详情 Map，优先取 period_end，次选 periodStart
     * @return 标准化日期字符串（如 "2026-10-04"）；若元数据均缺失则返回 null
     */
    String resolveReportDate(Map<String, Object> report);

    /**
     * 导出指定历史运营报告的排版 PDF 文件载体（整合单次查库与日期截取）
     *
     * @param reportId 待导出的报告 ID
     * @return 包含 PDF 字节流与文件名的结果载体；若报告不存在则返回 null
     */
    ReportExportResult exportReportPdf(String reportId);

    /**
     * 导出今日（实时生成）运营日报的标准 PDF 二进制字节流
     *
     * @return 当天实时运营指标渲染的 PDF 文件二进制 byte[]
     */
    byte[] exportTodayReportPdf();

    /**
     * 接收当前已登录游客设备或管理端的心跳续约信号，维持在线活跃状态
     */
    void heartbeat();

    /**
     * 查询系统当前有效时间窗口（90 秒 TTL）内处于活跃在线状态的用户与会话列表
     *
     * @return 在线用户设备明细列表
     */
    List<Map<String, Object>> listOnlineUsers();
}
