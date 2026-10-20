"""Audit executable planner/dining sources. Curated data and tests are not application fallbacks."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "src/main/java/com/ai/guide"
checks = {
    "coordinate_literal": re.compile(r'"[+-]?\d{1,3}\.\d+\s*,\s*[+-]?\d{1,2}\.\d+"'),
    "named_person_or_noise_list": re.compile(r'\b(?:FAMOUS_PERSONS|NOISE_NAME_KEYWORDS|GENUINE_SCENIC_SUFFIXES)\b'),
    "fixed_meal_insertion": re.compile(r'createDiningStop\([^;]*"\d{2}:\d{2}"', re.S),
    "fixed_meal_switch": re.compile(r'case\s+"(?:BREAKFAST|LUNCH|DINNER|TEA_BREAK|NIGHT_SNACK)"\s*->\s*"\d{2}:\d{2}"'),
    "city_or_place_literal": re.compile(r'"[^"\n]*(?:重庆|成都|西安|北京|杭州|武汉|洪崖洞|解放碑|磁器口|兵马俑)[^"\n]*"'),
    "food_or_brand_literal": re.compile(r'"[^"\n]*(?:火锅|螺蛳粉|驴肉火烧|甜食|甜点|糖水|麻辣|甜口|肯德基|麦当劳|外婆家)[^"\n]*"'),
    "inline_dining_default": re.compile(r'"(?:本地菜优先|特色美食|地道美食|夜市特色|休闲茶歇|周边精选美食)"'),
    "fixed_clock_literal": re.compile(r'"(?:[01]\d|2[0-3]):[0-5]\d"'),
    "poi_fallback_business_keyword": re.compile(r'"景点"'),
    "meal_slot_regex_vocabulary": re.compile(r'(?:matches|replaceAll)\("[^"\n]*(?:早餐|早饭|早点|午餐|午饭|晚餐|晚饭|宵夜|夜宵)[^"\n]*"'),
    "inline_meal_or_search_category_label": re.compile(r'"(?:【(?:早餐|午餐|晚餐|宵夜|特色茶歇)[^】]*】|晨间早点|核心地标|景区地标|全网资讯)"'),
    "inline_dining_candidate_limit": re.compile(r'(?:candidates\.size\(\)\s*<\s*[35]|idx\s*>\s*5|proximityPriority,\s*[56]|limit\s*>\s*0\s*\?\s*limit\s*:\s*3)'),
    "inline_nearby_poi_suffix": re.compile(r'"\s*附近(?:景点|餐饮)"'),
}
files = sorted(set((root / "domain/planner").rglob("*.java")) | set((root / "domain/dining").rglob("*.java"))
               | set((root / "domain/search").rglob("*.java"))
               | set((root / "application/chat").rglob("*.java")))
if not files:
    raise SystemExit("No source files found; refusing an empty audit")
violations = []
for path in files:
    # Ignore documentation comments: examples do not supply runtime coordinates.
    source = re.sub(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*', lambda m: m.group() if m.group().startswith('"') else "\n" * m.group().count("\n"), path.read_text(encoding="utf-8-sig"), flags=re.S)
    for name, pattern in checks.items():
        for match in pattern.finditer(source):
            violations.append({"file": str(path.relative_to(root)), "line": source[:match.start()].count("\n") + 1, "rule": name})
print(json.dumps({"scope": "production domain/planner + domain/dining + domain/search + application/chat Java", "files": len(files), "checks": list(checks),
                  "violations": violations, "limitation": "Static rules do not prove semantic generalization, route validity or absence of every literal."},ensure_ascii=False,indent=2))
raise SystemExit(bool(violations))
