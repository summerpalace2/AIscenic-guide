"""
PDF 生成服务
使用 reportlab 生成分析报告 PDF
"""
import io
from datetime import datetime
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm, cm
from reportlab.lib.colors import HexColor
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib import colors


def generate_report_pdf(report_data: dict) -> bytes:
    """
    根据报告数据生成 PDF 文件
    返回 PDF 字节流
    """
    buf = io.BytesIO()
    doc = SimpleDocTemplate(
        buf, pagesize=A4,
        topMargin=2*cm, bottomMargin=2*cm,
        leftMargin=2*cm, rightMargin=2*cm
    )

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        'CustomTitle', parent=styles['Title'],
        fontSize=18, spaceAfter=20,
        textColor=HexColor('#1a1a2e')
    )
    heading_style = ParagraphStyle(
        'CustomHeading', parent=styles['Heading2'],
        fontSize=14, spaceAfter=12, spaceBefore=16,
        textColor=HexColor('#16213e')
    )
    body_style = ParagraphStyle(
        'CustomBody', parent=styles['Normal'],
        fontSize=10, leading=16, spaceAfter=8
    )

    elements = []

    # 标题
    title = report_data.get('title', '分析报告')
    elements.append(Paragraph(title, title_style))
    elements.append(Spacer(1, 10))

    # 时间信息
    now = datetime.now().strftime('%Y-%m-%d %H:%M')
    elements.append(Paragraph(f'生成时间: {now}', body_style))
    elements.append(Spacer(1, 6))

    # 数据统计表
    data_entries = report_data.get('data', {})
    if data_entries:
        elements.append(Paragraph('数据统计', heading_style))
        table_data = [['指标', '数值']]
        for key, value in data_entries.items():
            table_data.append([str(key), str(value)])

        table = Table(table_data, colWidths=[120*mm, 60*mm])
        table.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), HexColor('#16213e')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
            ('FONTSIZE', (0, 0), (-1, 0), 10),
            ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
            ('FONTNAME', (0, 1), (-1, -1), 'Helvetica'),
            ('FONTSIZE', (0, 1), (-1, -1), 9),
            ('GRID', (0, 0), (-1, -1), 0.5, HexColor('#cccccc')),
            ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, HexColor('#f8f9fa')]),
        ]))
        elements.append(table)

    # 报告说明
    elements.append(Spacer(1, 20))
    elements.append(Paragraph('报告说明', heading_style))
    elements.append(Paragraph(
        '本报告由 AI 数字人智能景区导览系统自动生成，'
        '数据来源于系统服务日志和对话记录。',
        body_style
    ))
    elements.append(Paragraph(
        f'报告 ID: {report_data.get("id", "N/A")}',
        body_style
    ))

    doc.build(elements)
    pdf_bytes = buf.getvalue()
    buf.close()
    return pdf_bytes
