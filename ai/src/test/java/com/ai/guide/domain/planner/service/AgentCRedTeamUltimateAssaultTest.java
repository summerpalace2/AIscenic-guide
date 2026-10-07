package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.DefaultBrandDictionary;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("餐饮与地理组件回归（离线测试，非在线实测）")
public class AgentCRedTeamUltimateAssaultTest {

    private final DiningCategoryGrounder diningCategoryGrounder = new DiningCategoryGrounder();
    private final PoiCategoryPolicyRegistry poiCategoryPolicyRegistry = new PoiCategoryPolicyRegistry();

    @Nested
    @DisplayName("攻击向量 1：复合实体与高德 TypeCode 混淆防御（Hybrid POI Ambiguity）")
    class HybridPoiAmbiguityAssault {

        @Test
        @DisplayName("加固验收 1A：大学教学楼里的餐厅（'重邮第四教学楼教工餐厅'）被正确识别为商户实体而非泛需求")
        void assault_campusRestaurant_classifiedAsGenericCraving() {
            // "重邮第四教学楼教工餐厅" 含有 "餐厅" 后缀，被正确识别为具体商户实体
            boolean isGeneric = diningCategoryGrounder.isGenericDiningCraving("重邮第四教学楼教工餐厅");
            assertFalse(isGeneric, "含有'餐厅'机构后缀的商户实体应被判定为门店实体(false)，交由高德精准检索！");
        }

        @Test
        @DisplayName("加固验收 1B：高德离线降级或无 TypeCode 时，'三峡博物馆巴渝老茶馆'被正确识别为 DINING 餐饮茶歇")
        void assault_teaHouseInMuseum_misclassifiedAsScenicWhenTypeCodeMissing() {
            // 当高德 API 超时、离线降级或新店铺无 typecode 时，PoiCategoryPolicyRegistry 进入 Step 2 语义兜底
            Map<String, Object> stopWithoutTypeCode = Map.of(
                    "name", "三峡博物馆巴渝老茶馆",
                    "venueId", "venue-tea-001",
                    "type", "文化服务"
            );
            PoiDomainType category = poiCategoryPolicyRegistry.resolveCategory(stopWithoutTypeCode);
            assertEquals(PoiDomainType.DINING, category,
                    "在无 typecode 降级模式下，'三峡博物馆巴渝老茶馆' 正确被识别为 DINING 餐饮茶歇！");
        }
    }

    @Nested
    @DisplayName("攻击向量 2：全国千奇百怪地名与生僻美食实体防御（Exotic Cravings & Obscure Places）")
    class ExoticCravingsAssault {

        @Test
        @DisplayName("加固验收 2A：地道非遗美食'钵钵鸡'被正确识别为餐饮泛风味需求")
        void assault_boboji_failsDiningKeywords() {
            var intent = PlanAdjustmentIntent.builder().cuisineOrPlace("钵钵鸡").genericDiningCraving(true).build();
            assertTrue(diningCategoryGrounder.isGenericDiningCraving(intent, "钵钵鸡"));
            assertEquals("钵钵鸡", diningCategoryGrounder.resolveRequestedFlavor(intent, "特色美食"));
        }

        @Test
        @DisplayName("加固验收 2B：捐赠命名高校建筑（'李兆基科技大楼'）被识别为非餐饮建筑")
        void assault_donatedCampusBuilding_escapesNonDiningFilter() {
            var category = poiCategoryPolicyRegistry.resolveCategory(Map.of("name", "李兆基科技大楼", "typecode", "141200"));
            assertNotEquals(PoiDomainType.DINING, category, "分类使用高德科教类型，不要求穷举捐赠人名字");
        }

        @Test
        @DisplayName("加固验收 2C：超长外来菜系名称（如'正宗西班牙海鲜炒饭'）完整保留，不再被截断为'正宗西班牙海'")
        void assault_longExoticDishName_truncatedByRegex() {
            String input = "我想去吃正宗西班牙海鲜炒饭";
            String flavor = diningCategoryGrounder.resolveRequestedFlavor(input, "特色美食");
            assertTrue(flavor.contains("正宗西班牙海鲜炒饭"),
                    "9字美食'正宗西班牙海鲜炒饭'完整提取，不再发生机械截断！实际提取结果: " + flavor);
        }
    }

    @Nested
    @DisplayName("攻击向量 3：多城市几何质心畸变与虚无坐标防御（Centroid Distortion & Coordinates Void）")
    class CentroidDistortionAssault {

        @Test
        @DisplayName("加固验收 3A：远郊跨区行程（武隆天生三桥+巫山小三峡）质心畸变防御，锚定最新游览点")
        void assault_wulongWushanCentroid_landsInWilderness() {
            // 武隆天生三桥 (107.75, 29.32) 与 巫山小三峡 (109.87, 31.07) 相距 280 公里
            List<Map<String, Object>> stops = List.of(
                    Map.of("name", "武隆天生三桥", "location", "107.750000,29.320000"),
                    Map.of("name", "巫山小三峡", "location", "109.870000,31.070000")
            );
            String centroid = PlanAdjustmentHelper.resolveDefaultCoordinate(Collections.emptyMap(), null, stops);
            // 杜绝落在湖北恩施无人区 (108.81, 30.19)，自动锚定最后游览点
            assertEquals("109.870000,31.070000", centroid,
                    "大跨度远郊行程自动锚定最后一个有效游览点，杜绝深山无人区质心！");
        }

        @Test
        @DisplayName("城市来自高德上下文，不从经纬度包围盒猜测")
        void assault_routeCostProvider_chongqingEastBoundaryDefect() throws Exception {
            RouteCostProvider provider = new RouteCostProvider(null);
            Method detectMethod = RouteCostProvider.class.getDeclaredMethod("detectCityFromAttraction", Attraction.class);
            detectMethod.setAccessible(true);

            // 奉节白帝城：经度 109.465，纬度 31.018，属于重庆市直辖区县
            Attraction fengjie = Attraction.builder()
                    .id("scenic-fengjie")
                    .name("白帝城瞿塘峡景区")
                    .district("奉节县") // district故意不含"重庆"二字，检验纯坐标划分能力
                    .location("109.465000,31.018000")
                    .build();

            String detectedCity = (String) detectMethod.invoke(provider, fengjie);
            assertNull(detectedCity, "坐标不能冒充行政区划证据");
            fengjie.setAmapQuery(Map.of("city", "重庆市"));
            assertEquals("重庆市", detectMethod.invoke(provider, fengjie));
        }

        @Test
        @DisplayName("加固验收 3C：NaN 脏数据注入质心计算被免疫拦截，安全降级至城市核心中枢")
        void assault_nanCoordinates_pollution() {
            List<Map<String, Object>> stops = List.of(
                    Map.of("name", "脏数据点", "location", "NaN,NaN")
            );
            String centroid = PlanAdjustmentHelper.resolveDefaultCoordinate(Collections.emptyMap(), null, stops);
            assertNotEquals("NaN,NaN", centroid, "NaN 坐标毒丸被有效防御！");
            assertNull(centroid, "无有效地理证据时交给动态地理编码，不能伪造固定城市中枢");
        }
    }

    @Nested
    @DisplayName("攻击向量 4：全库残留死规则与偷懒打地鼠二次大搜捕与加固")
    class LegacyRulesAndCheatingAssault {

        @Test
        @DisplayName("加固验收 4A：DefaultBrandDictionary 自动加载外部词库并支持动态品牌注册")
        void assault_registerBrand_isNeverInvoked() {
            DefaultBrandDictionary dictionary = new DefaultBrandDictionary();
            // 外部配置文件 dining_brands.txt 中的扩展品牌如 "塔斯汀" 应被自动加载
            assertTrue(dictionary.isKnownBrand("塔斯汀"), "外部品牌字典应成功装载'塔斯汀'！");
            assertTrue(dictionary.isKnownBrand("肯德基"), "种子品牌应保留！");

            // 动态注册新品牌测试
            dictionary.registerBrand("新潮地方特色汉堡");
            assertTrue(dictionary.isKnownBrand("新潮地方特色汉堡"), "动态注册品牌生效！");
        }

        @Test
        @DisplayName("风味规范化保留用户文本，不把所有早点强制改成城市预设菜名")
        void assault_breakfastFlavor_forceLockedToChongqingNoodles() throws Exception {
            DiningVenueService service = new DiningVenueService();
            Method normalizeMethod = DiningVenueService.class.getDeclaredMethod("normalizeFlavor", String.class, String.class);
            normalizeMethod.setAccessible(true);

            String flavorInXiAn = (String) normalizeMethod.invoke(service, "我想吃点当地特色早餐早点", "西安市");
            assertEquals("我想吃点当地特色早餐早点", flavorInXiAn);

            String flavorInChengdu = (String) normalizeMethod.invoke(service, "早餐吃什么", "成都市");
            assertEquals("早餐吃什么", flavorInChengdu);
            assertEquals("驴肉火烧", normalizeMethod.invoke(service, "驴肉火烧", "西安市"));
        }
    }
}
