"""把 gradle 的 JUnit XML 整理成給 issue 用的失敗摘要。

按測試類別(≈書源)分組,每條失敗只取斷言訊息的第一行 ——
issue 是給人看的,完整堆疊在 artifact 裡。
"""
import sys
import glob
import xml.etree.ElementTree as ET

results_dir = sys.argv[1] if len(sys.argv) > 1 else "CustomSources/build/test-results/test"

groups = {}
total = failed = 0
for path in sorted(glob.glob(f"{results_dir}/TEST-*.xml")):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in suite.iter("testcase"):
        total += 1
        problems = list(case.iter("failure")) + list(case.iter("error"))
        if not problems:
            continue
        failed += 1
        cls = case.get("classname", "?")
        message = (problems[0].get("message") or problems[0].text or "").strip()
        first_line = message.splitlines()[0][:160] if message else "(無訊息)"
        groups.setdefault(cls, []).append((case.get("name", "?"), first_line))

print(f"**{failed} / {total} 個測試失敗**\n")
for cls in sorted(groups):
    print(f"### {cls}")
    for name, message in groups[cls]:
        print(f"- `{name}` — {message}")
    print()
