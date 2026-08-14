# -*- coding: utf-8 -*-
"""PNK0079 自动广告搜索词盘点：好词(收割) vs 废词(否定)。"""
import csv
from collections import defaultdict

ITEM = r"C:/Users/Administrator/Desktop/ItemKeywordPerformance_2026_08_02_19_42_08.csv"
ROAS_LINE = 4.0

def fnum(x):
    try: return float(x)
    except Exception: return 0.0

rows = []
with open(ITEM, encoding="utf-8-sig", newline="") as f:
    for r in csv.DictReader(f):
        if "PNK0079" in (r.get("Campaign Name") or ""):
            rows.append(r)

agg = defaultdict(lambda: defaultdict(float))
for r in rows:
    kw = (r.get("Searched Keyword") or "").strip()
    if not kw:
        kw = "(空)"
    a = agg[kw]
    a["imp"]   += fnum(r["Impressions"])
    a["clk"]   += fnum(r["Clicks"])
    a["spend"] += fnum(r["Ad Spend"])
    a["ord"]   += fnum(r["Orders"])
    a["sales"] += fnum(r["Total Attributed Sales"])

T = defaultdict(float)
for a in agg.values():
    for k in ("imp","clk","spend","ord","sales"):
        T[k] += a[k]

good, waste = [], []
for kw, a in agg.items():
    if a["spend"] <= 0 and a["ord"] <= 0:
        continue
    roas = a["sales"]/a["spend"] if a["spend"] > 0 else 0
    rec = (kw, a["imp"], a["clk"], a["spend"], a["ord"], a["sales"], roas)
    if a["ord"] > 0 and roas >= ROAS_LINE:
        good.append(rec)
    elif a["spend"] > 0 and a["ord"] == 0:      # 烧钱 0 单 → 否定候选
        waste.append(rec)
    elif a["ord"] > 0 and roas < 2:             # 有单但极差 → 也列入观察(可否定)
        waste.append(rec)

good.sort(key=lambda x: -x[6])
waste.sort(key=lambda x: -x[3])

tot_good_spend = sum(x[3] for x in good)
tot_good_sales = sum(x[5] for x in good)
tot_waste_spend = sum(x[3] for x in waste)

print(f"自动广告总览: 触发词 {len(agg)} 个 | 花费 ${T['spend']:.2f} | 销售额 ${T['sales']:.2f} | 订单 {T['ord']:.0f}")
print(f"整体 RoAS = {T['sales']/T['spend']:.2f}\n")

print(f"=== ① 好词（有转化 & RoAS≥4，建议收割到手动 exact，起始 bid $0.40）共 {len(good)} 个 ===")
print(f"    这部分合计花费 ${tot_good_spend:.2f} / 销售额 ${tot_good_sales:.2f}")
for kw, imp, clk, spend, ord_, sales, roas in good[:25]:
    print(f"  RoAS {roas:5.1f} | {ord_:>2.0f}单 ${sales:7.1f} | 花费${spend:6.2f} | {kw}")

print(f"\n=== ② 废词（烧钱0单，或 RoAS<2，建议加否定）共 {len(waste)} 个，合计浪费 ${tot_waste_spend:.2f} ===")
for kw, imp, clk, spend, ord_, sales, roas in waste[:25]:
    tag = "0单" if ord_ == 0 else f"RoAS{roas:.1f}"
    print(f"  [{tag:6}] 花费${spend:6.2f} | {imp:.0f}曝光 {clk:.0f}点击 | {kw}")
