package com.ai.guide.application.analytics;

/**
 * 运营报告 PDF 导出结果载体
 *
 * @param pdfData  PDF 文件二进制字节流（若渲染失败可能为 null）
 * @param fileName 导出报告文件名中嵌入的基准日期或报告 ID
 */
public record ReportExportResult(byte[] pdfData, String fileName) {
}
