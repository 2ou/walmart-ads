import csv
from collections import defaultdict

KW = r"C:/Users/Administrator/Desktop/KeywordPerformance_2026_08_02_23_44_23.csv"

def fnum(x):
    try:
        return float(x)
    except Exception:
        return 0.0

rows = []
with open(KW, encoding="utf-8-sig", newline="") as f:
    for row in csv.DictReader(f):
        if "PNK0079" in (row.get("Campaign Name") or ""):
            rows.append(row)

# current bid per (keyword, matchtype) -- take first nonzero
bid = {}
for r in rows:
    k = (r["Bidded Keyword"].strip(), r["Match Type"].strip())
    b = fnum(r["Bid"])
    if b > 0 and k not in bid:
        bid[k] = b

watch = [
    ("2 piece", "phrase"),
    ("two piece", "phrase"),
    ("travel outfit", "exact"),
    ("matching set summer", "broad"),
    ("2 piece travel set", "broad"),
    ("woman outfit 2 piece", "broad"),
    ("summer outfit", "phrase"),
    ("two piece set summer", "broad"),
    ("2 piece set", "exact"),
    ("2 piece set pants", "broad"),
    ("matching set", "exact"),
]
print("关键词当前出价 (manual):")
for k in watch:
    print(f"  [{k[1]:6}] {k[0]:28} bid=${bid.get(k, 0):.2f}")

# auto campaign strategy
auto_rows = []
with open(r"C:/Users/Administrator/Desktop/ItemKeywordPerformance_2026_08_02_19_42_08.csv", encoding="utf-8-sig", newline="") as f:
    for row in csv.DictReader(f):
        if "PNK0079" in (row.get("Campaign Name") or ""):
            auto_rows.append(row)
if auto_rows:
    print("\n自动广告策略:", auto_rows[0].get("Current Bidding Strategy"),
          "| Target ROAS:", auto_rows[0].get("Current Target ROAS") or "(无)")
