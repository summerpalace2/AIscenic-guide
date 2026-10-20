package com.ai.guide.infrastructure.attraction.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** A validated, transactional snapshot of the managed city files; unrelated database rows are untouched. */
@Component
public class CityAttractionCatalogImporter {
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};
    private static final String MANIFEST_NAME = "catalog-release.json";
    private record Release(long version, String fingerprint) {}
    private record Snapshot(Map<String, Map<String, Object>> rows, Release release) {}
    private static final String[] FIELDS = {"id", "name", "displayName", "city", "district", "category", "tags", "icon", "tone", "location", "summary", "walk", "duration", "indoor", "walkDifficulty", "ticket", "bestTime", "amapQuery", "intro", "fit", "accessibility", "environment", "recommendedVisitMinutes", "companionTags", "featureTags", "photoUrl"};
    private static final String[] COLUMNS = {"id", "name", "display_name", "city", "district", "category", "tags", "icon", "tone", "location", "summary", "walk", "duration", "indoor", "walk_difficulty", "ticket", "best_time", "amap_query", "intro", "fit", "accessibility", "environment", "recommended_visit_minutes", "companion_tags", "feature_tags", "photo_url"};
    private static final Set<String> JSON_FIELDS = Set.of("tags", "amapQuery", "companionTags", "featureTags", "verifiedFields", "aliases");
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    @Value("${attractions.catalog.external-directory:}")
    private String externalDirectory = "";

    public CityAttractionCatalogImporter() {}
    public CityAttractionCatalogImporter(Path externalDirectory) { this.externalDirectory = externalDirectory.toString(); }

    /** Explicit operations hook; validation failures throw without replacing the prior database snapshot. */
    public synchronized int refresh(JdbcTemplate jdbc, boolean sqlite) {
        try {
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
            return Objects.requireNonNull(transaction.execute(status -> {
                acquireInstallLock(jdbc, sqlite);
                Snapshot snapshot = readSnapshot();
                sync(jdbc, sqlite, snapshot.rows(), fingerprint(snapshot.rows()), snapshot.release());
                return snapshot.rows().size();
            }));
        } catch (Exception error) {
            throw new IllegalStateException("刷新城市景点目录失败，保留原快照", error);
        }
    }

    private Snapshot readSnapshot() {
        try {
            Map<String, Resource> resources = new TreeMap<>();
            Path directory = externalDirectory.isBlank() ? null : Path.of(externalDirectory);
            if (directory != null && !Files.isDirectory(directory)) throw new IllegalArgumentException("城市目录不存在: " + directory);
            if (directory != null && Files.exists(directory.resolve(MANIFEST_NAME))
                    && !Files.isRegularFile(directory.resolve(MANIFEST_NAME)))
                throw new IllegalArgumentException("发布清单必须是普通文件");
            boolean versioned = directory != null && Files.isRegularFile(directory.resolve(MANIFEST_NAME));
            if (!versioned)
                for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:taxonomy/cities/*.json")) {
                    if (MANIFEST_NAME.equals(resource.getFilename())) continue;
                    if (resources.putIfAbsent(resource.getFilename(), resource) != null)
                        throw new IllegalArgumentException("重复城市资源文件: " + resource.getFilename());
                }
            if (!externalDirectory.isBlank()) {
                try (var paths = Files.list(directory)) {
                    for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")
                            && !MANIFEST_NAME.equals(p.getFileName().toString())).sorted().toList()) {
                        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("城市资源必须是普通文件: " + path);
                        resources.put(path.getFileName().toString(), new org.springframework.core.io.FileSystemResource(path));
                    }
                }
            }
            if (resources.isEmpty()) throw new IllegalArgumentException("城市目录不能为空");
            Map<String, byte[]> bytes = new TreeMap<>();
            Map<String, String> hashes = new TreeMap<>();
            for (var entry : resources.entrySet()) {
                byte[] content;
                try (var input = entry.getValue().getInputStream()) { content = input.readAllBytes(); }
                bytes.put(entry.getKey(), content);
                hashes.put(entry.getKey(), sha256(content));
            }
            Release release = versioned ? readRelease(directory.resolve(MANIFEST_NAME), hashes) : null;
            Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
            for (var file : bytes.entrySet()) {
                    List<Map<String, Object>> entries = mapper.readValue(file.getValue(), ROWS);
                    if (entries == null) throw new IllegalArgumentException("城市文件必须是数组: " + file.getKey());
                    String fileCity = null;
                    for (Map<String, Object> row : entries) {
                        validate(row);
                        String city = text(row, "city").replaceFirst("市$", "");
                        if (city.isBlank()) throw new IllegalArgumentException("城市名不能为空");
                        row.put("city", city);
                        row.put("id", text(row, "id"));
                        if (fileCity != null && !fileCity.equals(city)) throw new IllegalArgumentException("同一目录文件包含多个城市");
                        fileCity = city;
                        if (rows.putIfAbsent(text(row, "id"), row) != null) throw new IllegalArgumentException("重复景点ID: " + row.get("id"));
                    }
            }
            return new Snapshot(rows, release);
        } catch (Exception error) {
            throw new IllegalStateException("读取并校验城市目录失败", error);
        }
    }

    private Release readRelease(Path manifestPath, Map<String, String> actualHashes) throws Exception {
        Map<?, ?> manifest = mapper.readValue(Files.readAllBytes(manifestPath), Map.class);
        Object value = manifest.get("version");
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException("发布版本必须为正整数");
        Object filesValue = manifest.get("files");
        if (!(filesValue instanceof Map<?, ?> files) || files.isEmpty())
            throw new IllegalArgumentException("发布清单必须列出全部城市文件");
        Map<String, String> expected = new TreeMap<>();
        for (var file : files.entrySet()) {
            if (!(file.getKey() instanceof String name) || !name.matches("[A-Za-z0-9._-]+\\.json")
                    || MANIFEST_NAME.equals(name) || !(file.getValue() instanceof String hash)
                    || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("非法发布文件清单");
            expected.put(name, hash);
        }
        if (!expected.equals(actualHashes)) throw new IllegalArgumentException("城市文件与发布清单 SHA-256 不一致");
        return new Release(((Number) value).longValue(), sha256(mapper.writeValueAsBytes(expected)));
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String fingerprint(Map<String, Map<String, Object>> rows) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(rows)));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成城市目录指纹", error);
        }
    }

    private void acquireInstallLock(JdbcTemplate jdbc, boolean sqlite) {
        if (sqlite) {
            // SQLite has no SELECT FOR UPDATE; a no-op write takes its database writer lock.
            jdbc.update("UPDATE attraction_catalog_revision SET revision=revision WHERE id=1");
        } else {
            jdbc.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1 FOR UPDATE", Long.class);
        }
    }

    private void sync(JdbcTemplate jdbc, boolean sqlite, Map<String, Map<String, Object>> rows, String fingerprint, Release release) {
        Map<String, Object> published = jdbc.queryForMap("SELECT fingerprint, release_version, release_fingerprint FROM attraction_catalog_revision WHERE id=1");
        String priorFingerprint = String.valueOf(published.get("fingerprint"));
        long priorVersion = ((Number) published.get("release_version")).longValue();
        String priorReleaseFingerprint = String.valueOf(published.get("release_fingerprint"));
        if (release == null && priorVersion > 0)
            throw new IllegalArgumentException("城市目录已启用发布版本，拒绝无清单刷新");
        if (release != null) {
            if (release.version() < priorVersion) throw new IllegalArgumentException("城市目录发布版本不得回退");
            if (release.version() == priorVersion &&
                    (!release.fingerprint().equals(priorReleaseFingerprint) || !fingerprint.equals(priorFingerprint)))
                throw new IllegalArgumentException("同一城市目录发布版本的内容不可变更");
        }
        if (fingerprint.equals(priorFingerprint)) {
            if (release != null && release.version() > priorVersion)
                jdbc.update("UPDATE attraction_catalog_revision SET release_version=?, release_fingerprint=? WHERE id=1",
                        release.version(), release.fingerprint());
            return;
        }
        Map<String, String> owned = new HashMap<>();
        jdbc.query("SELECT id, city FROM attraction_catalog_entry", rs -> { owned.put(rs.getString("id"), rs.getString("city")); });
        for (var row : rows.values()) {
            String id = text(row, "id");
            List<String> existing = jdbc.queryForList("SELECT city FROM attraction WHERE id = ?", String.class, id);
            if (!existing.isEmpty() && (!owned.containsKey(id) || !existing.get(0).equals(row.get("city"))))
                throw new IllegalArgumentException("景点ID已由其他来源或城市使用: " + id);
            if (owned.containsKey(id) && !owned.get(id).equals(row.get("city")))
                throw new IllegalArgumentException("景点ID不能跨城市迁移: " + id);
        }
        for (String id : owned.keySet()) {
            if (!rows.containsKey(id)) {
                jdbc.update("DELETE FROM attraction WHERE id=?", id);
                jdbc.update("DELETE FROM attraction_catalog_entry WHERE id=?", id);
            }
        }
        String updates = String.join(",", Arrays.stream(COLUMNS).skip(1).map(column -> column + "=excluded." + column).toList());
        String sql = "INSERT INTO attraction (" + String.join(",", COLUMNS) + ") VALUES (" + String.join(",", Collections.nCopies(COLUMNS.length, "?")) + ") ON CONFLICT(id) DO UPDATE SET " + updates;
        for (var row : rows.values()) {
            Object[] values = new Object[FIELDS.length];
            for (int i = 0; i < FIELDS.length; i++) values[i] = value(row, FIELDS[i], sqlite);
            jdbc.update(sql, values);
            jdbc.update("INSERT INTO attraction_catalog_entry (id,city,source_url,verified_at,verified_fields,aliases) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET source_url=excluded.source_url, verified_at=excluded.verified_at, verified_fields=excluded.verified_fields, aliases=excluded.aliases",
                    row.get("id"), row.get("city"), row.get("sourceUrl"), row.get("verifiedAt"), value(row, "verifiedFields", sqlite), value(row, "aliases", sqlite));
        }
        if (release == null)
            jdbc.update("UPDATE attraction_catalog_revision SET revision=revision+1, fingerprint=? WHERE id=1", fingerprint);
        else jdbc.update("UPDATE attraction_catalog_revision SET revision=revision+1, fingerprint=?, release_version=?, release_fingerprint=? WHERE id=1",
                fingerprint, release.version(), release.fingerprint());
    }

    private Object value(Map<String, Object> row, String field, boolean sqlite) {
        if (field.equals("indoor")) return row.get(field) == null ? null
                : sqlite ? (Boolean.TRUE.equals(row.get(field)) ? 1 : 0) : row.get(field);
        if (field.equals("recommendedVisitMinutes")) return row.get(field);
        if (JSON_FIELDS.contains(field)) {
            try { return mapper.writeValueAsString(row.getOrDefault(field, field.equals("amapQuery") ? Map.of() : List.of())); }
            catch (Exception error) { throw new IllegalArgumentException("非法JSON字段: " + field, error); }
        }
        return row.getOrDefault(field, "");
    }

    private void validate(Map<String, Object> row) {
        for (String field : List.of("id", "name", "city", "category", "sourceUrl", "verifiedAt")) text(row, field);
        if (text(row, "id").length() > 128) throw new IllegalArgumentException("景点ID过长");
        URI source = URI.create(text(row, "sourceUrl"));
        if (!("https".equals(source.getScheme()) || "http".equals(source.getScheme())) || source.getHost() == null)
            throw new IllegalArgumentException("来源必须为HTTP网址");
        if (LocalDate.parse(text(row, "verifiedAt")).isAfter(LocalDate.now())) throw new IllegalArgumentException("核验日期不能在未来");
        if (row.containsKey("location") && !(row.get("location") instanceof String)) throw new IllegalArgumentException("坐标必须为文本");
        if (row.get("location") instanceof String coordinate && !coordinate.isBlank()) {
            String[] location = coordinate.split(",", -1);
            if (location.length != 2) throw new IllegalArgumentException("坐标必须为经度,纬度");
            double lng = Double.parseDouble(location[0]), lat = Double.parseDouble(location[1]);
            if (!Double.isFinite(lng) || !Double.isFinite(lat) || Math.abs(lng) > 180 || Math.abs(lat) > 90) throw new IllegalArgumentException("非法坐标");
        }
        if (row.containsKey("indoor") && row.get("indoor") != null && !(row.get("indoor") instanceof Boolean)) throw new IllegalArgumentException("indoor必须为布尔值或未知");
        for (String field : JSON_FIELDS) {
            if (row.containsKey(field) && !(field.equals("amapQuery") ? row.get(field) instanceof Map : row.get(field) instanceof List)) throw new IllegalArgumentException("非法结构字段: " + field);
        }
        Object verifiedFields = row.getOrDefault("verifiedFields", List.of());
        if (!(verifiedFields instanceof List<?> list)
                || list.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank()))
            throw new IllegalArgumentException("verifiedFields必须为字符串数组");
        row.put("verifiedFields", verifiedFields);
        Object aliases = row.getOrDefault("aliases", List.of());
        if (!(aliases instanceof List<?> names) || names.stream().anyMatch(value ->
                !(value instanceof String name) || name.isBlank() || name.length() > 128))
            throw new IllegalArgumentException("aliases必须为非空名称字符串数组");
        if (!((List<?>) aliases).isEmpty() && !((List<?>) verifiedFields).contains("aliases"))
            throw new IllegalArgumentException("aliases必须纳入来源核验字段");
        if (row.containsKey("recommendedVisitMinutes") && (!(row.get("recommendedVisitMinutes") instanceof Integer minutes) || minutes <= 0 || minutes > 1440)) throw new IllegalArgumentException("非法停留分钟");
    }

    private String text(Map<String, Object> row, String field) {
        if (row == null || !(row.get(field) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("缺少目录字段: " + field);
        return value.trim();
    }
}
