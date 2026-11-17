package com.ai.guide.application.agent.tools;

import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 景点与餐饮 POI 检索工具：融合本地受控目录与高德实时 POI 搜索。
 */
@Component
public class SearchPoiAndDiningTool implements AgentToolPort {

    public static final String TOOL_NAME = "search_poi_and_dining";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "搜索关键词，如“室内博物馆”“不辣江湖菜”“洪崖洞”"
                },
                "city": {
                  "type": "string",
                  "description": "目标城市，默认使用上下文城市"
                },
                "category": {
                  "type": "string",
                  "enum": ["ALL", "SCENIC", "DINING"],
                  "description": "检索类别：ALL(全部)、SCENIC(景点/场馆)、DINING(餐饮美食)"
                },
                "limit": {
                  "type": "integer",
                  "description": "返回最大条数（1~10，默认 5）"
                }
              },
              "required": ["query"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "按关键词检索景点、室内场馆或餐饮门店候选，融合受控本地目录与高德实时 POI 数据。",
            SCHEMA,
            false
    );

    private final AttractionService attractionService;
    private final DiningVenueService diningVenueService;
    private final GeoRouteGatewayPort geoRouteGateway;
    private final ObjectMapper objectMapper;

    public SearchPoiAndDiningTool(AttractionService attractionService,
                                  DiningVenueService diningVenueService,
                                  GeoRouteGatewayPort geoRouteGateway,
                                  ObjectMapper objectMapper) {
        this.attractionService = attractionService;
        this.diningVenueService = diningVenueService;
        this.geoRouteGateway = geoRouteGateway;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String query = AgentToolJsonSupport.text(args, "query", "");
        if (query.isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_QUERY", "请提供景点或餐饮检索关键词 query");
        }
        String city = AgentToolJsonSupport.text(args, "city", context.activeCity());
        String category = AgentToolJsonSupport.text(args, "category", "ALL").toUpperCase(Locale.ROOT);
        int limit = AgentToolJsonSupport.intValue(args, "limit", 5, 1, 10);

        List<Map<String, Object>> localScenic = "DINING".equals(category) ? List.of() : matchLocalAttractions(city, query, limit);
        List<Map<String, Object>> localDining = "SCENIC".equals(category) ? List.of() : matchLocalDining(query, limit);
        List<Map<String, Object>> livePois = searchLivePois(query, city, category, limit);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("city", city);
        payload.put("query", query);
        payload.put("localScenic", localScenic);
        payload.put("localDining", localDining);
        payload.put("livePois", livePois);

        int total = localScenic.size() + localDining.size() + livePois.size();
        if (total == 0) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "NO_POI_FOUND",
                    "未在" + city + "检索到匹配“" + query + "”的景点或餐饮，请尝试更换关键词或放宽区域。");
        }
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload),
                "检索到 " + total + " 条" + city + "相关地点候选");
    }

    private List<Map<String, Object>> matchLocalAttractions(String city, String query, int limit) {
        List<Attraction> all = attractionService == null ? List.of() : attractionService.list(city, null, null);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Attraction item : all) {
            if (matches.size() >= limit) break;
            String haystack = String.join(" ",
                    safe(item.getName()), safe(item.getDistrict()), safe(item.getCategory()),
                    safe(item.getSummary()), item.getTags() == null ? "" : String.join(" ", item.getTags())
            ).toLowerCase(Locale.ROOT);
            if (haystack.contains(lower) || matchesAnyToken(haystack, lower)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("name", item.getName());
                row.put("district", safe(item.getDistrict()));
                row.put("category", safe(item.getCategory()));
                row.put("location", safe(item.getLocation()));
                row.put("openingHours", safe(item.getOpeningHours()));
                if (!safe(item.getTicket()).isBlank()) row.put("ticket", safe(item.getTicket()));
                if (!safe(item.getSummary()).isBlank()) row.put("summary", safe(item.getSummary()));
                if (item.getTags() != null && !item.getTags().isEmpty()) row.put("tags", item.getTags());
                matches.add(row);
            }
        }
        return matches;
    }

    private List<Map<String, Object>> matchLocalDining(String query, int limit) {
        List<DiningVenue> all = diningVenueService == null ? List.of() : diningVenueService.listAll();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (DiningVenue venue : all) {
            if (matches.size() >= limit) break;
            String haystack = String.join(" ",
                    safe(venue.getName()), safe(venue.getDistrict()), safe(venue.getCategory()),
                    safe(venue.getSpecialtyDish()), safe(venue.getSpicinessLevel()),
                    venue.getTags() == null ? "" : String.join(" ", venue.getTags())
            ).toLowerCase(Locale.ROOT);
            if (haystack.contains(lower) || matchesAnyToken(haystack, lower)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", venue.getId());
                row.put("name", venue.getName());
                row.put("district", safe(venue.getDistrict()));
                row.put("category", safe(venue.getCategory()));
                row.put("specialtyDish", safe(venue.getSpecialtyDish()));
                row.put("averageCost", safe(venue.getAverageCost()));
                row.put("spicinessLevel", safe(venue.getSpicinessLevel()));
                row.put("location", safe(venue.getLocation()));
                matches.add(row);
            }
        }
        return matches;
    }

    private List<Map<String, Object>> searchLivePois(String query, String city, String category, int limit) {
        if (geoRouteGateway == null || !geoRouteGateway.isConfigured()) {
            return List.of();
        }
        String types = "DINING".equals(category) ? "050000" : "SCENIC".equals(category) ? "110000|140000" : "";
        GeoPoiOutcome outcome = types.isEmpty()
                ? geoRouteGateway.searchPoi(query, city)
                : geoRouteGateway.searchPoi(query, city, types);
        if (outcome == null || outcome.status() != GeoOutcomeStatus.SUCCESS || outcome.candidates() == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GeoPoiCandidate c : outcome.candidates()) {
            if (rows.size() >= limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("poiId", c.poiId());
            row.put("name", c.name());
            row.put("address", safe(c.address()));
            row.put("type", safe(c.type()));
            if (c.coordinate() != null && c.coordinate().isValid()) {
                row.put("location", c.coordinate().longitude() + "," + c.coordinate().latitude());
            }
            if (c.rating() != null) row.put("rating", c.rating());
            rows.add(row);
        }
        return rows;
    }

    private static boolean matchesAnyToken(String haystack, String query) {
        String[] parts = query.split("[\\s,，、]+");
        if (parts.length <= 1) return false;
        for (String p : parts) {
            if (p.length() >= 2 && haystack.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
