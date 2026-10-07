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
}
files = sorted(set((root / "domain/planner").rglob("*.java")) | set((root / "domain/dining").rglob("*.java")))
if not files:
    raise SystemExit("No source files found; refusing an empty audit")
violations = []
for path in files:
    # Ignore documentation comments: examples do not supply runtime coordinates.
    source = re.sub(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*', lambda m: m.group() if m.group().startswith('"') else "\n" * m.group().count("\n"), path.read_text(encoding="utf-8-sig"), flags=re.S)
    for name, pattern in checks.items():
        for match in pattern.finditer(source):
            violations.append({"file": str(path.relative_to(root)), "line": source[:match.start()].count("\n") + 1, "rule": name})
print(json.dumps({"scope": "production planner and dining Java", "files": len(files), "checks": list(checks),
                  "violations": violations, "limitation": "Static rules do not prove semantic generalization, route validity or absence of every literal."},ensure_ascii=False,indent=2))
raise SystemExit(bool(violations))
