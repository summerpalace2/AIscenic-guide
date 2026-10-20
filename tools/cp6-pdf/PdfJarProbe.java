import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Run using the actual executable JAR's PropertiesLauncher; no Spring/database/server is started. */
public class PdfJarProbe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Class<?> fontType = Class.forName("com.ai.guide.infrastructure.pdf.PdfFontProvider");
        Object provider = fontType.getConstructor().newInstance();
        Object font = fontType.getMethod("getFont").invoke(provider);
        if (!Boolean.TRUE.equals(font.getClass().getMethod("isEmbedded").invoke(font))) {
            throw new AssertionError("The executable JAR did not load an embedded font");
        }
        var resource = fontType.getResource("/fonts/NotoSansSC-Regular.ttf");
        if (resource == null || !resource.toString().contains(".jar")) {
            throw new AssertionError("Probe must load the font from the actual packaged JAR");
        }
        Class<?> tripType = Class.forName("com.ai.guide.infrastructure.trip.pdf.TripPlanPdfService");
        Object trip = tripType.getConstructor(fontType).newInstance(provider);
        Map<String, Object> plan = Map.of("title", "打包环境中文旅行手册", "subtitle", "蒙民伟理科馆与螺蛳粉，25℃",
                "sourceMode", "隔离验收来源", "days", List.of(Map.of("day", 1, "title", "人文历史",
                        "stops", List.of(Map.of("name", "蒙民伟理科馆", "time", "10:00",
                                "summary", "人文历史与驴肉火烧", "duration", "约60分钟")))));
        byte[] tripBytes = (byte[]) tripType.getMethod("export", Map.class).invoke(trip, plan);
        Files.write(output.resolve("jar-trip.pdf"), tripBytes);

        Class<?> repositoryType = Class.forName("com.ai.guide.domain.analytics.gateway.AnalyticsRepositoryPort");
        Class<?> dailyType = Class.forName(repositoryType.getName() + "$DailyReportData");
        Object daily = dailyType.getConstructor(int.class, int.class, int.class, List.class)
                .newInstance(8, 5, 1, List.of(Map.of("rank", 1, "question", "如何参观蒙民伟理科馆？", "count", 3)));
        Object repository = Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class[]{repositoryType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("loadDailyReportData")) return daily;
                    throw new AssertionError("Unexpected repository access: " + method.getName());
                });
        Class<?> analyticsType = Class.forName("com.ai.guide.infrastructure.analytics.pdf.OpenPdfAnalyticsReportRenderer");
        Object renderer = analyticsType.getConstructor(repositoryType, fontType).newInstance(repository, provider);
        byte[] dailyBytes = (byte[]) analyticsType.getMethod("exportDailyReport", String.class).invoke(renderer, "2026-10-16");
        Files.write(output.resolve("jar-analytics-daily.pdf"), dailyBytes);
        byte[] storedBytes = (byte[]) analyticsType.getMethod("exportStoredReport", Map.class).invoke(renderer,
                Map.of("title", "打包环境中文运营报告", "type", "weekly", "periodStart", "2026-10-09",
                        "periodEnd", "2026-10-16", "data", "{\"内容\":\"螺蛳粉与驴肉火烧，25℃\"}"));
        Files.write(output.resolve("jar-analytics-stored.pdf"), storedBytes);
        System.out.println("FONT_RESOURCE=" + resource);
        System.out.println("PDF_BYTES=" + tripBytes.length + "," + dailyBytes.length + "," + storedBytes.length);
    }
}
