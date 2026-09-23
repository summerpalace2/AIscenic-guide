package com.ai.guide.domain.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 通用多维意图雷达服务 (Intent Radar Service)
 * 
 * 架构职责：
 * 1. 彻底解决自然语言偏好识别“打地鼠”痛点，涵盖 DIET, PACE, ENVIRONMENT, EXPERIENCE, COMPANION 5 大核心旅行维度；
 * 2. 毫秒级提取结构化偏好（DetectedPreference）并自动生成高转化、可一键落地的交互动作（ActionableSuggestion）；
 * 3. 供 ChatController SSE 流式元数据直接挂载，实现“一次推理、双重输出”，杜绝时序断层与孤立大模型调用。
 */
@Service
public class IntentRadarService {

    private static final Logger log = LoggerFactory.getLogger(IntentRadarService.class);

    public record DetectedPreference(String domain, String trait, double confidence) {}

    public record ActionableSuggestion(String action, String label, String icon, String payload) {}

    public record RadarResult(DetectedPreference preference, List<ActionableSuggestion> suggestions) {}

    private record PatternRule(
            String domain,
            Pattern pattern,
            String trait,
            double confidence,
            List<ActionTemplate> templates
    ) {}

    private record ActionTemplate(String action, String label, String icon, String payloadTemplate) {}

    private final List<PatternRule> rules = new ArrayList<>();

    public IntentRadarService() {
        initRules();
    }

    private void initRules() {
        // ================= 1. DIET 餐饮风味与特色就餐 =================
        // 烧烤、烤串、烤脑花（先于火锅匹配）
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(烧烤|烤串|烤脑花|烤鱼|吃烧烤|整点烧烤).*"),
                "偏好特色烧烤与市井烤串",
                0.96,
                List.of(
                        new ActionTemplate("replace_meal", "安排附近特色烧烤或烤串聚餐", "🍢", "寻找并替换为附近特色烧烤餐厅"),
                        new ActionTemplate("save_slot_preference", "将【烧烤】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"烧烤\"}")
                )
        ));

        // 甜点、糖水、甜食
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(甜的|甜品|甜食|吃甜|想吃甜|糖水|豆花|冰粉|凉糕|糍粑|糕点|蛋糕|烘焙).*"),
                "偏好甜品糖水与特色甜点",
                0.93,
                List.of(
                        new ActionTemplate("add_stop", "沿途加一站甜点/糖水小憩", "🍰", "在当前行程加入一处甜点或特色糖水小憩停留"),
                        new ActionTemplate("save_slot_preference", "将【甜品糖水】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"甜品糖水\"}")
                )
        ));

        // 重辣、火锅、江湖菜
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(火锅|老火锅|九宫格|串串|毛血旺|辣子鸡|江湖菜|重辣|麻辣|喜辣|爱吃辣|想吃辣|川菜|爆辣).*"),
                "饮食偏好麻辣浓郁与地道火锅",
                0.95,
                List.of(
                        new ActionTemplate("replace_meal", "推荐并替换为地道老火锅/江湖菜", "🍲", "寻找并替换为附近地道老火锅或江湖菜餐厅"),
                        new ActionTemplate("save_slot_preference", "将【老火锅】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"老火锅\"}")
                )
        ));

        // 清淡、少辣、少油、暖胃热汤
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(清淡|不吃辣|少辣|微辣|不能吃辣|少油|喝汤|热汤|暖胃|养生|炖汤|抄手清汤|老鸭汤|蹄花).*"),
                "饮食偏好清淡温润与少辣热汤",
                0.94,
                List.of(
                        new ActionTemplate("replace_meal", "安排清汤蹄花或滋补老鸭汤正餐", "🥣", "安排清淡滋补蹄花汤或老鸭汤正餐"),
                        new ActionTemplate("save_slot_preference", "将【清淡少辣】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"清淡少辣\"}")
                )
        ));

        // 老茶馆、喝茶、盖碗茶
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(茶馆|老茶馆|盖碗茶|喝茶|喝茶馆|泡茶|品茗).*"),
                "偏好特色老茶馆与慢生活品茗",
                0.95,
                List.of(
                        new ActionTemplate("add_stop", "沿途安排老茶馆品茗停留", "🍵", "在路线中增加一处特色老茶馆或盖碗茶品茗停留"),
                        new ActionTemplate("save_slot_preference", "将【老茶馆】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"老茶馆\"}")
                )
        ));

        // 咖啡、下午茶、独立特调
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(咖啡|下午茶|拿铁|特调|精酿|歇歇脚|找个地方坐坐).*"),
                "偏好特色咖啡、特调与下午茶小憩",
                0.91,
                List.of(
                        new ActionTemplate("add_stop", "沿途安排景观咖啡或下午茶停留", "☕", "在路线中增加一处特色景观咖啡馆停留"),
                        new ActionTemplate("save_slot_preference", "将【特色咖啡】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"特色咖啡\"}")
                )
        ));

        // 夜市、街头小吃
        rules.add(new PatternRule(
                "DIET",
                Pattern.compile(".*(夜市|小吃街|路边摊|逛吃|酸辣粉|小面|特色小吃|好吃街).*"),
                "偏好街头特色小吃与夜市烟火",
                0.92,
                List.of(
                        new ActionTemplate("add_stop", "晚间衔接特色夜市或好吃街逛吃", "🍢", "行程晚间连接特色夜市或好吃街"),
                        new ActionTemplate("save_slot_preference", "将【特色小吃】加入美食偏好", "✦", "{\"slot\":\"dining\",\"tag\":\"特色小吃\"}")
                )
        ));

        // ================= 2. PACE 体力节奏与作息 =================
        // 少走路、脚酸、走不动、避台阶、懒得走
        rules.add(new PatternRule(
                "PACE",
                Pattern.compile(".*(走累|脚酸|脚疼|腿酸|腿疼|膝盖|走不动|爬不动|少走|不想走|懒得走|有点懒|很懒|太懒|偷懒|犯懒|不想动|少爬坡|少台阶|平路|平地|爬坡累|体力有限|体力一般|省力|省腿).*"),
                "偏好少走路，优先平路与平缓动线",
                0.96,
                List.of(
                        new ActionTemplate("replan_pace", "优化为少台阶与低步行平缓路线", "🚶", "优化今天行程，少走路、少台阶、优先平路"),
                        new ActionTemplate("reduce_density", "精简日程，减少1处步行较远的站点", "⏱", "今天行程太累了，少推荐一个景点"),
                        new ActionTemplate("save_slot_preference", "将【少爬坡少走路】加入出行偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"少爬坡少走路\"}")
                )
        ));

        // 慢节奏、放松从容
        rules.add(new PatternRule(
                "PACE",
                Pattern.compile(".*(慢节奏|不想太赶|太累|别太赶|休闲|轻松点|不要特种兵|不赶时间|佛系游).*"),
                "出行偏好慢节奏休闲，避免奔波",
                0.92,
                List.of(
                        new ActionTemplate("replan_pace", "调整为慢节奏舒适漫游模式", "🌿", "调整今天的游览节奏更从容一些，留足休息时间"),
                        new ActionTemplate("save_slot_preference", "将【慢节奏休闲】加入出行偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"慢节奏休闲\"}")
                )
        ));

        // 晚起、睡懒觉
        rules.add(new PatternRule(
                "PACE",
                Pattern.compile(".*(起不来|睡懒觉|睡到自然醒|晚起|上午不出发|下午才出门|不早起|起太早).*"),
                "作息偏好从容晚起，睡到自然醒",
                0.90,
                List.of(
                        new ActionTemplate("adjust_time", "顺延上午出发时间至11点后", "⏰", "将出发时间推迟到上午11点之后，行程从容安排"),
                        new ActionTemplate("save_slot_preference", "将【习惯晚起】加入出行偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"习惯晚起\"}")
                )
        ));

        // 特种兵、充实多走
        rules.add(new PatternRule(
                "PACE",
                Pattern.compile(".*(特种兵|充实|暴走|多逛几个|多看几个|不怕累|多走走|抓紧时间).*"),
                "偏好高密度充实打卡，多走多看",
                0.89,
                List.of(
                        new ActionTemplate("add_stop", "顺路方向追加1处特色小众打卡点", "🚀", "在顺路方向再增加一个值得一看的特色景点"),
                        new ActionTemplate("save_slot_preference", "将【充实高效打卡】加入出行偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"充实高效打卡\"}")
                )
        ));

        // ================= 3. ENVIRONMENT 环境与天气 =================
        // 雨天避雨、全室内
        rules.add(new PatternRule(
                "ENVIRONMENT",
                Pattern.compile(".*(下雨|雨天|雨好大|避雨|鞋湿|淋雨|室内|雨季).*"),
                "注重天气体感，雨天优先室内场馆",
                0.95,
                List.of(
                        new ActionTemplate("indoor_mode", "一键替换为全室内舒适避雨场馆", "☂", "今天下雨，请全部替换为室内舒适场馆"),
                        new ActionTemplate("save_slot_preference", "将【室内场馆】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"室内场馆\"}")
                )
        ));

        // 防晒、避暑、怕热
        rules.add(new PatternRule(
                "ENVIRONMENT",
                Pattern.compile(".*(太热|怕热|怕晒|防晒|中暑|大太阳|吹空调|避暑|暴晒).*"),
                "注重防晒避暑，优先阴凉与空调环境",
                0.92,
                List.of(
                        new ActionTemplate("cool_mode", "调整正午时段为室内空调清凉体验", "❄", "调整中午时段为室内空调或清凉避暑安排"),
                        new ActionTemplate("save_slot_preference", "将【室内避暑】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"室内避暑\"}")
                )
        ));

        // 清静、避开拥挤、小众
        rules.add(new PatternRule(
                "ENVIRONMENT",
                Pattern.compile(".*(人太多|人挤人|排队|清静|安静|小众|冷门|不想挤|清幽).*"),
                "偏好小众清幽秘境，避开人流拥挤",
                0.91,
                List.of(
                        new ActionTemplate("quiet_mode", "替换为同片区清幽小众文化去处", "🍃", "避开人挤人的网红点，推荐同区域人少小众的文化去处"),
                        new ActionTemplate("save_slot_preference", "将【清幽小众】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"清幽小众\"}")
                )
        ));

        // ================= 4. EXPERIENCE 视听与体验 =================
        // 摄影出片、绝佳机位
        rules.add(new PatternRule(
                "EXPERIENCE",
                Pattern.compile(".*(拍照|摄影|出片|机位|找角度|发朋友圈|汉服|写真|拍大片|好看的照片).*"),
                "重视摄影出片，追求绝佳机位与光影",
                0.93,
                List.of(
                        new ActionTemplate("photo_mode", "优化路线至周边最佳机位打卡点", "📸", "推荐附近最出片、视角最好的摄影打卡机位"),
                        new ActionTemplate("save_slot_preference", "将【摄影出片】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"摄影出片\"}")
                )
        ));

        // 山城夜景、魔幻8D
        rules.add(new PatternRule(
                "EXPERIENCE",
                Pattern.compile(".*(夜景|看夜景|两江|夜色|立交桥|轻轨穿楼|赛博朋克|魔幻8D|天台).*"),
                "偏爱魔幻8D立体景观与震撼山城夜景",
                0.94,
                List.of(
                        new ActionTemplate("night_view", "傍晚及晚间衔接两江绝美夜景", "🌙", "将傍晚和晚间行程优化为最佳夜景观景点"),
                        new ActionTemplate("save_slot_preference", "将【山城夜景】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"山城夜景\"}")
                )
        ));

        // 历史文化、古迹古建
        rules.add(new PatternRule(
                "EXPERIENCE",
                Pattern.compile(".*(历史|人文|古迹|非遗|老街|博物馆|古镇|古建|老故事|传统).*"),
                "偏爱巴渝人文历史与传统古建风貌",
                0.91,
                List.of(
                        new ActionTemplate("culture_mode", "沿途串联深度巴渝历史文化去处", "🏛️", "推荐附近最具巴渝历史底蕴的文博古迹去处"),
                        new ActionTemplate("save_slot_preference", "将【人文历史】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"人文历史\"}")
                )
        ));

        // 自然山水、峡谷风光
        rules.add(new PatternRule(
                "EXPERIENCE",
                Pattern.compile(".*(自然|山水|峡谷|瀑布|徒步|森林|公园|风景区|爬山).*"),
                "偏爱自然山水与峡谷生态风貌",
                0.92,
                List.of(
                        new ActionTemplate("add_stop", "沿途增加自然山水景观停留", "🏞️", "推荐附近生态极佳的自然山水去处"),
                        new ActionTemplate("save_slot_preference", "将【自然山水】加入景点偏好", "✦", "{\"slot\":\"attraction\",\"tag\":\"自然山水\"}")
                )
        ));

        // ================= 5. COMPANION 同行人关照 =================
        // 长辈、老年人
        rules.add(new PatternRule(
                "COMPANION",
                Pattern.compile(".*(老人|长辈|父母|爸妈|带老人|老年人|腿脚不便).*"),
                "同行有长辈，优先适老舒适与休息设施",
                0.95,
                List.of(
                        new ActionTemplate("senior_friendly", "适配长辈体感：少台阶、避大坡度", "🧓", "为长辈优化行程，避开大坡度台阶，增加歇脚点"),
                        new ActionTemplate("save_memory", "将【同行有长辈需适老关照】沉淀至档案", "✦", "同行有长辈，优先适老舒适与休息设施")
                )
        ));

        // 亲子、儿童
        rules.add(new PatternRule(
                "COMPANION",
                Pattern.compile(".*(带娃|孩子|宝宝|小朋友|亲子|游乐|推车).*"),
                "家庭亲子出行，注重儿童友好与推车平顺",
                0.93,
                List.of(
                        new ActionTemplate("kids_friendly", "切换为亲子友好与推车平顺动线", "👶", "优化为儿童友好动线，避开陡坡，增加趣味体验"),
                        new ActionTemplate("save_memory", "将【亲子出行需兼顾儿童】沉淀至档案", "✦", "家庭亲子出行，注重儿童友好与推车平顺")
                )
        ));
    }

    /**
     * 针对用户输入分析意图雷达感知与可执行动作
     */
    public RadarResult scan(String message) {
        if (message == null || message.trim().length() < 2) return null;
        String text = message.trim();

        // 避免纯系统指令被误判为微调诉求
        if (text.startsWith("/") || text.matches("^(确定|取消|返回|跳过|刷新|是|否|好|对)$")) {
            return null;
        }

        for (PatternRule rule : rules) {
            if (rule.pattern.matcher(text).find()) {
                DetectedPreference pref = new DetectedPreference(rule.domain, rule.trait, rule.confidence);
                List<ActionableSuggestion> actions = new ArrayList<>();
                for (ActionTemplate tmpl : rule.templates) {
                    actions.add(new ActionableSuggestion(tmpl.action, tmpl.label, tmpl.icon, tmpl.payloadTemplate));
                }
                log.info("[IntentRadar] Match domain={}, trait={}, actionCount={}", rule.domain, rule.trait, actions.size());
                return new RadarResult(pref, actions);
            }
        }
        return null;
    }
}
