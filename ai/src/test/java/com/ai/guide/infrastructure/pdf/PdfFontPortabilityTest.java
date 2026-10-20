package com.ai.guide.infrastructure.pdf;

import com.ai.guide.domain.analytics.gateway.AnalyticsRepositoryPort;
import com.ai.guide.infrastructure.analytics.pdf.OpenPdfAnalyticsReportRenderer;
import com.ai.guide.infrastructure.trip.pdf.TripPlanPdfService;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.*;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PdfFontPortabilityTest {
    @TempDir Path temporary;

    @Test
    void legacyCjkFallbackHasNoEmbeddedFontProgramEvenWhenRequested() throws Exception {
        var font = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.EMBEDDED);
        try (var bytes = new ByteArrayOutputStream()) {
            var document = new Document();
            PdfWriter.getInstance(document, bytes);
            document.open();
            document.add(new Paragraph("旧回退中文依赖阅读器字库", new Font(font, 14)));
            document.close();
            writeSample("legacy-font-fallback.pdf", bytes.toByteArray());
            var reader = new PdfReader(bytes.toByteArray());
            try {
                var fonts = reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.FONT);
                assertFalse(fonts.getKeys().isEmpty());
                for (var key : fonts.getKeys()) assertFalse(hasFontProgram(fonts.getAsDict(key)));
            } finally { reader.close(); }
        }
    }

    @Test
    void bundledFontIsEmbeddedAndSupportsChinesePunctuationAndTemperature() {
        var provider = new PdfFontProvider();
        var font = provider.getFont();
        assertSame(font, provider.getFont());
        assertTrue(font.isEmbedded());
        "人文历史蒙民伟理科馆驴肉火烧螺蛳粉，。℃ABC123".codePoints()
                .forEach(character -> assertTrue(font.charExists(character), "Missing glyph: " + character));
    }

    @Test
    void explicitFontFileIsLoadedWithoutContaminatingAnotherProvider() throws Exception {
        Path file = temporary.resolve("中文字体.ttf");
        try (var input = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            assertNotNull(input);
            Files.copy(input, file);
        }
        assertTrue(new PdfFontProvider(file.toString()).getFont().charExists('游'));
        assertTrue(new PdfFontProvider().getFont().charExists('游'));
        assertThrows(IllegalStateException.class,
                () -> new PdfFontProvider(temporary.resolve("missing.ttf").toString()).getFont());
    }

    @Test
    void corruptExplicitFontDoesNotSilentlyFallBack() throws Exception {
        Path file = temporary.resolve("invalid.ttf");
        Files.writeString(file, "not a font");
        var error = assertThrows(IllegalStateException.class, () -> new PdfFontProvider(file.toString()).getFont());
        assertTrue(error.getMessage().contains("PDF_FONT_FILE"));
        Files.delete(file); // Failed parsing must not retain an OS file handle.
    }

    @Test
    void tripExportCarriesChineseTextAndEmbeddedGlyphs() throws Exception {
        byte[] bytes = new TripPlanPdfService(new PdfFontProvider()).export(Map.of(
                "title", "人文历史旅行手册", "subtitle", "蒙民伟理科馆与螺蛳粉，气温25℃",
                "sourceMode", "隔离样例来源", "days", List.of(Map.of("day", 1, "title", "文化漫步",
                        "stops", List.of(Map.of("name", "蒙民伟理科馆", "time", "10:00",
                                "summary", "人文历史展览，驴肉火烧与螺蛳粉。", "duration", "约60分钟"))))));
        assertPortable(bytes, "人文历史旅行手册", "蒙民伟理科馆", "螺蛳粉", "隔离样例来源", "第1页");
        writeSample("portable-trip.pdf", bytes);
    }

    @Test
    void analyticsDailyExportCarriesChineseQuestionsAndEmbeddedGlyphs() throws Exception {
        var repository = mock(AnalyticsRepositoryPort.class);
        when(repository.loadDailyReportData(anyString(), anyString())).thenReturn(
                new AnalyticsRepositoryPort.DailyReportData(8, 5, 1,
                        List.of(Map.of("rank", 1, "question", "蒙民伟理科馆如何参观？", "count", 3))));
        byte[] bytes = new OpenPdfAnalyticsReportRenderer(repository, new PdfFontProvider()).exportDailyReport("2026-10-16");
        assertPortable(bytes, "日服务报告", "数据概览", "蒙民伟理科馆如何参观", "总服务次数");
        writeSample("portable-analytics-daily.pdf", bytes);
    }

    @Test
    void analyticsStoredExportCarriesSnapshotChineseAndEmbeddedGlyphs() throws Exception {
        var repository = mock(AnalyticsRepositoryPort.class);
        byte[] bytes = new OpenPdfAnalyticsReportRenderer(repository, new PdfFontProvider()).exportStoredReport(Map.of(
                "title", "人文历史运营周报", "type", "weekly", "periodStart", "2026-10-09", "periodEnd", "2026-10-16",
                "data", "{\"热门问题\":\"螺蛳粉与驴肉火烧，气温25℃\",\"次数\":8}"));
        assertPortable(bytes, "人文历史运营周报", "热门问题", "螺蛳粉与驴肉火烧", "25℃");
        verifyNoInteractions(repository);
        writeSample("portable-analytics-stored.pdf", bytes);
    }

    private static void writeSample(String name, byte[] bytes) throws Exception {
        Path folder = Path.of("target/test-output/pdf-portability");
        Files.createDirectories(folder);
        Files.write(folder.resolve(name), bytes);
    }

    private static void assertPortable(byte[] bytes, String... expected) throws Exception {
        assertNotNull(bytes);
        var reader = new PdfReader(bytes);
        try {
            StringBuilder text = new StringBuilder();
            var extractor = new PdfTextExtractor(reader);
            int embeddedChineseFonts = 0;
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
                var fonts = reader.getPageN(page).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.FONT);
                for (var key : fonts.getKeys()) {
                    var font = fonts.getAsDict(key);
                    if (PdfName.TYPE0.equals(font.getAsName(PdfName.SUBTYPE))) {
                        assertTrue(hasFontProgram(font), "CJK font program must be inside the PDF");
                        assertNotNull(font.get(PdfName.TOUNICODE), "Unicode extraction must not depend on local fonts");
                        embeddedChineseFonts++;
                    }
                }
            }
            assertTrue(embeddedChineseFonts > 0);
            String compact = text.toString().replaceAll("\\s+", "");
            for (String value : expected) assertTrue(compact.contains(value), "Missing PDF text: " + value);
        } finally { reader.close(); }
    }

    private static boolean hasFontProgram(PdfDictionary font) {
        var descendants = font.getAsArray(PdfName.DESCENDANTFONTS);
        if (descendants != null) font = descendants.getAsDict(0);
        var descriptor = font.getAsDict(PdfName.FONTDESCRIPTOR);
        return descriptor != null && (PdfReader.getPdfObject(descriptor.get(PdfName.FONTFILE2)) instanceof PdfStream
                || PdfReader.getPdfObject(descriptor.get(PdfName.FONTFILE3)) instanceof PdfStream);
    }
}
