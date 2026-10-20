package com.ai.guide.infrastructure.pdf;

import com.lowagie.text.pdf.BaseFont;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Both PDF adapters embed the same font; exports never depend on the viewer's CJK font pack. */
@Component
public class PdfFontProvider {
    private final String fontFile;
    private BaseFont font;

    public PdfFontProvider() {
        this("");
    }

    @Autowired
    public PdfFontProvider(@Value("${pdf.font-file:}") String fontFile) {
        this.fontFile = fontFile == null ? "" : fontFile.trim();
    }

    public synchronized BaseFont getFont() {
        if (font != null) return font;
        try {
            BaseFont loaded;
            if (fontFile.isEmpty()) {
                // Streams also work when the resource lives inside the executable Spring Boot JAR.
                try (var input = PdfFontProvider.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
                    if (input == null) throw new java.io.IOException("Bundled PDF font is missing");
                    loaded = BaseFont.createFont("NotoSansSC-Regular.ttf", BaseFont.IDENTITY_H,
                            BaseFont.EMBEDDED, false, input.readAllBytes(), null);
                }
            } else {
                // Read and close the file ourselves: a malformed font can leave OpenPDF's
                // file-backed parser holding a handle during construction failure on Windows.
                String filePath = fontFile.replaceFirst("(?i)(\\.ttc),\\d+$", "$1");
                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(filePath));
                loaded = BaseFont.createFont(fontFile, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bytes, null);
            }
            if (!loaded.isEmbedded() || !loaded.charExists('中') || !loaded.charExists('文')) {
                throw new IllegalArgumentException("PDF font must support Chinese and embedding");
            }
            font = loaded;
            return loaded;
        } catch (Exception error) {
            // Never silently fall back to Helvetica or a non-embedded CJK font.
            throw new IllegalStateException("无法加载PDF中文字体，请检查 PDF_FONT_FILE 或应用内 fonts 字体资源", error);
        }
    }
}
