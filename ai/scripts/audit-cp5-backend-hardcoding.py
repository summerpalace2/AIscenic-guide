"""Project-wide CP5 literal audit for production Java and backend resource assets.

Findings are candidate violations, not automatic judgments about curated data,
protocol constants, or infrastructure endpoints. Review the emitted scope and
resource classification before accepting a zero count as evidence.
"""
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java"
RESOURCE_ROOT = ROOT / "src/main/resources"

RULES = {
    "absolute_drive_path_literal": re.compile(r'"[A-Za-z]:(?:\\\\|/)[^"\n]*"'),
    "coordinate_literal": re.compile(r'"[+-]?\d{1,3}\.\d+\s*,\s*[+-]?\d{1,2}\.\d+"'),
    "city_or_named_place_literal": re.compile(r'"[^"\n]*(?:重庆|成都|西安|北京|杭州|武汉|洪崖洞|解放碑|磁器口|兵马俑)[^"\n]*"'),
    "food_or_brand_literal": re.compile(r'"[^"\n]*(?:火锅|螺蛳粉|驴肉火烧|甜食|甜点|糖水|麻辣|甜口|肯德基|麦当劳|外婆家)[^"\n]*"'),
    "fixed_clock_literal": re.compile(r'"(?:[01]\d|2[0-3]):[0-5]\d"'),
    "inline_meal_regex_vocabulary": re.compile(r'(?:matches|replaceAll)\("[^"\n]*(?:早餐|早饭|早点|午餐|午饭|晚餐|晚饭|宵夜|夜宵)[^"\n]*"'),
    "inline_business_fallback": re.compile(r'"(?:本地菜优先|特色美食|地道美食|夜市特色|休闲茶歇|周边精选美食|重庆市)"'),
    "provider_url_outside_infrastructure": re.compile(r'"https?://[^"\n]+"'),
    "numeric_coordinate_pair": re.compile(r'(?<![A-Za-z])\d{2,3}\.\d{3,}\s*,\s*\d{1,2}\.\d{3,}(?!\d)'),
}


def strip_comments_keep_literals(source: str) -> str:
    return re.sub(
        r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*',
        lambda match: match.group() if match.group().startswith('"') else "\n" * match.group().count("\n"),
        source,
        flags=re.S,
    )


java_files = sorted(JAVA_ROOT.rglob("*.java"))
if not java_files:
    raise SystemExit("No production Java files found; refusing an empty audit")

findings = []
for path in java_files:
    source = strip_comments_keep_literals(path.read_text(encoding="utf-8-sig"))
    relative = path.relative_to(JAVA_ROOT).as_posix()
    for rule, pattern in RULES.items():
        if rule == "provider_url_outside_infrastructure" and relative.startswith("com/ai/guide/infrastructure/"):
            continue
        for match in pattern.finditer(source):
            findings.append({
                "file": relative,
                "line": source.count("\n", 0, match.start()) + 1,
                "rule": rule,
                "literal": match.group(0),
            })

resource_files = sorted(path for path in RESOURCE_ROOT.rglob("*") if path.is_file())
json_files = [path for path in resource_files if path.suffix.lower() in {".json", ".jsonl"}]
invalid_json = []
for path in json_files:
    try:
        if path.suffix.lower() == ".jsonl":
            for line_no, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
                if line.strip():
                    json.loads(line)
        else:
            json.loads(path.read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        invalid_json.append({"file": path.relative_to(RESOURCE_ROOT).as_posix(), "error": str(exc)})

curated_seed_counts = {}
for name in ("attractions.json", "dining_venues.json"):
    path = RESOURCE_ROOT / name
    if path.is_file():
        text = path.read_text(encoding="utf-8-sig")
        curated_seed_counts[name] = {
            "coordinate_records": len(re.findall(r'"location"\s*:\s*"[+-]?\d{1,3}\.\d+\s*,\s*[+-]?\d{1,2}\.\d+"', text)),
            "classification": "curated POI seed records, not executable fallback coordinates",
        }

taxonomy_dir = RESOURCE_ROOT / "taxonomy"
taxonomy_files = sorted(path.name for path in taxonomy_dir.glob("*.json")) if taxonomy_dir.exists() else []
legacy_brand_file = RESOURCE_ROOT / "dining_brands.txt"
resource_inventory = []
for path in resource_files:
    relative = path.relative_to(RESOURCE_ROOT).as_posix()
    if relative.startswith("taxonomy/"):
        classification = "externalized taxonomy dictionary or policy"
    elif relative in {"attractions.json", "dining_venues.json"}:
        classification = "curated POI catalog seed data"
    elif relative == "knowledge_documents.jsonl":
        classification = "curated knowledge corpus"
    elif relative == "dining_brands.txt":
        classification = "legacy asset; production code has no reader, README documents status"
    elif relative == "application.yml":
        classification = "runtime configuration and environment-overridable provider defaults"
    elif relative == "Dockerfile":
        classification = "build/runtime packaging configuration"
    else:
        classification = "documentation or reviewed non-policy resource"
    resource_inventory.append({"file": relative, "classification": classification})

provider_endpoints_in_infrastructure = []
for path in java_files:
    relative = path.relative_to(JAVA_ROOT).as_posix()
    if not relative.startswith("com/ai/guide/infrastructure/"):
        continue
    source = strip_comments_keep_literals(path.read_text(encoding="utf-8-sig"))
    for match in re.finditer(r'"https?://[^"\n]+"', source):
        provider_endpoints_in_infrastructure.append({
            "file": relative,
            "line": source.count("\n", 0, match.start()) + 1,
            "literal": match.group(0),
            "classification": "provider adapter/protocol endpoint; config override reviewed separately",
        })

result = {
    "scope": "all backend production Java and all src/main/resources assets",
    "production_java_files": len(java_files),
    "resource_files": len(resource_files),
    "json_and_jsonl_files_validated": len(json_files),
    "invalid_json_assets": invalid_json,
    "taxonomy_json_assets": taxonomy_files,
    "resource_inventory": resource_inventory,
    "curated_seed_classification": curated_seed_counts,
    "legacy_dining_brands_asset_exists": legacy_brand_file.is_file(),
    "provider_endpoints_in_infrastructure": provider_endpoints_in_infrastructure,
    "findings": findings,
    "rule_limitations": [
        "Literal rules are a review aid and cannot prove arbitrary semantic generalization.",
        "Provider URLs in infrastructure adapters and curated seed coordinates are intentionally classified separately from domain fallbacks.",
        "A clean result applies only to the listed patterns; source/config review remains required for policies not expressible as literals.",
    ],
}
print(json.dumps(result, ensure_ascii=False, indent=2))
if invalid_json:
    raise SystemExit(1)
