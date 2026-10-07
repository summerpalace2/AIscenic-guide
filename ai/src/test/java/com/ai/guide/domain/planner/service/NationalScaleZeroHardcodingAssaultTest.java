package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Component regressions use fake model output, never claim live AMap/LLM coverage. */
@DisplayName("多城市餐饮契约与 POI 分类回归（模型输出模拟，非在线实测）")
public class NationalScaleZeroHardcodingAssaultTest {
    private final DiningCategoryGrounder grounder = new DiningCategoryGrounder();
    private final ObjectMapper mapper = new ObjectMapper();

    private PlanAdjustmentIntent diningIntent(String message, String cuisine, boolean generic, String slot) throws Exception {
        String output = mapper.writeValueAsString(Map.of("operation", "ADD_STOP", "cuisineOrPlace", cuisine,
                "genericDiningCraving", generic, "timeSlot", slot, "confidence", 0.98));
        var extractor = new LlmIntentExtractor((system, user) -> output, mapper,
                new ConversationIntentClassifier(), true, true, 0.75, 500);
        var result = extractor.extract(message, PlanPageContext.builder().activeDay(1).build());
        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        return result.intent();
    }

    @Test
    void nationalDishNamesRemainExactWithoutBackendFoodRootRewrite() throws Exception {
        for (String dish : List.of("驴肉火烧", "柳州螺蛳粉", "老北京爆肚", "潮汕牛肉丸", "陕西肉夹馍",
                "武汉热干面", "延吉冷面", "南昌瓦罐汤", "广式艇仔粥", "南京鸭血粉丝汤", "羊肉泡馍",
                "烤脑花", "老妈蹄花", "油泼面", "锅包肉", "钵钵鸡")) {
            var intent = diningIntent("午餐想吃" + dish, dish, true, "LUNCH");
            assertTrue(grounder.isGenericDiningCraving(intent, dish), dish);
            assertEquals(dish, grounder.resolveRequestedFlavor(intent, "特色美食"), dish);
            assertEquals("LUNCH", grounder.resolveSlotType(intent), dish);
        }
    }

    @Test
    void longNaturalLanguageKeepsCuisineInsteadOfEntireModifiersOrMildPreference() throws Exception {
        var intent = diningIntent("我想吃点附近口碑最好人均不过百而且清淡不油腻适合三代同堂家庭的粤菜", "粤菜", true, "LUNCH");
        assertEquals("粤菜", grounder.resolveRequestedFlavor(intent, "特色美食"));
        assertTrue(grounder.isGenericDiningCraving(intent, "粤菜"));
    }

    @Test
    void multiIntentPrivateRestaurantKeepsPureEntity() throws Exception {
        var intent = diningIntent("我们一行人想找一家临江景观好有独立包厢能够喝茶聊天的老字号私房菜馆", "私房菜馆", true, "DINNER");
        assertEquals("私房菜馆", grounder.resolveRequestedFlavor(intent, "特色美食"));
        assertEquals("DINNER", grounder.resolveSlotType(intent));
    }

    @Test
    void explicitLunchWinsEvenWhenFoodIsBarbecueOrTea() throws Exception {
        for (String food : List.of("烧烤", "烤肉", "喝茶")) {
            var intent = diningIntent("午餐想" + food, food, true, "LUNCH");
            assertEquals("LUNCH", grounder.resolveSlotType(intent), food);
        }
    }

    @Test
    void unfamiliarSpecificVenueRemainsSpecificWithoutBrandDictionaryEntry() throws Exception {
        var intent = diningIntent("午餐去星河拾味", "星河拾味", false, "LUNCH");
        assertFalse(grounder.isGenericDiningCraving(intent, "星河拾味"));
        assertEquals("星河拾味", grounder.resolveRequestedFlavor(intent, "特色美食"));
    }

    @Test
    void authoritativeAmapTypeCodesWinOverCampusBuildingNames() {
        var registry = new PoiCategoryPolicyRegistry();
        for (String name : List.of("清华大学蒙民伟理科馆", "北京大学静园草坪", "浙江大学格物楼", "南开大学思源堂")) {
            assertEquals(PoiDomainType.DINING, registry.resolveCategory(Map.of("name", name + "咖啡馆", "typecode", "050500", "type", "SCENIC")), name);
            assertNotEquals(PoiDomainType.DINING, registry.resolveCategory(Map.of("name", name, "typecode", "141200")), name);
        }
        assertEquals(PoiDomainType.TRANSIT, registry.resolveCategory(Map.of("name", "景区交通站", "typecode", "150700")));
        assertEquals(PoiDomainType.SCENIC, registry.resolveCategory(Map.of("name", "文化景点", "typecode", "110100")));
    }

    @Test
    void adjustmentAlgorithmsContainNoLiteralCityCoordinateOrFixedMealSwitch() throws Exception {
        Path root = Files.exists(Path.of("src/main/java")) ? Path.of("src/main/java") : Path.of("ai/src/main/java");
        assertTrue(Files.isDirectory(root), "Source root must exist; scanning cannot silently skip files");
        Pattern coordinate = Pattern.compile("\"[-+]?\\d{1,3}\\.\\d+,[-+]?\\d{1,2}\\.\\d+\"");
        List<String> violations = new ArrayList<>();
        for (String relative : List.of("service/adjustment/PlanAdjustmentHelper.java", "service/adjustment/AddStopAdjustmentStrategy.java",
                "service/adjustment/ReplaceStopAdjustmentStrategy.java", "service/RuntimeLocationPlanner.java")) {
            Path file = root.resolve("com/ai/guide/domain/planner").resolve(relative);
            String source = Files.readString(file);
            if (coordinate.matcher(source).find()) violations.add(relative + ": literal coordinate");
            if (source.contains("case \"BREAKFAST\" -> \"08:00\"") || source.contains("case \"LUNCH\" -> \"12:00\"")
                    || source.contains("case \"DINNER\" -> \"18:00\"")) violations.add(relative + ": fixed meal switch");
        }
        assertEquals(List.of(), violations);
    }
}
