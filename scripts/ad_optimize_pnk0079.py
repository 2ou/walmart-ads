import csv
from collections import defaultdict

KW = r"C:/Users/Administrator/Desktop/KeywordPerformance_2026_08_02_23_44_23.csv"

def fnum(x):
    try:
        return float(x)
    except Exception:
        return 0.0

man_rows = []
with open(KW, encoding="utf-8-sig", newline="") as f:
    for row in csv.DictReader(f):
        if "PNK0079" in (row.get("Campaign Name") or ""):
            man_rows.append(row)

camps = defaultdict(int)
for r in man_rows:
    camps[r["Campaign Name"]] += 1
print("手动广告组 Campaign:", dict(camps))
print("手动广告总行数:", len(man_rows))

agg = defaultdict(lambda: defaultdict(float))
for r in man_rows:
    key = (r["Bidded Keyword"].strip(), r["Match Type"].strip())
    a = agg[key]
    a["imp"] += fnum(r["Impressions"])
    a["clk"] += fnum(r["Clicks"])
    a["spend"] += fnum(r["Ad Spend"])
    a["ord"] += fnum(r["Orders"])
    a["sales"] += fnum(r["Total Attributed Sales"])
    a["units"] += fnum(r["Units Sold"])

T = defaultdict(float)
for r in man_rows:
    for k in ("Impressions", "Clicks", "Ad Spend", "Orders", "Total Attributed Sales"):
        T[k] += fnum(r[k])
print(f"\n手动广告汇总: 曝光={T['Impressions']:.0f} 点击={T['Clicks']:.0f} 花费=${T['Ad Spend']:.2f} 订单={T['Orders']:.0f} 销售额=${T['Total Attributed Sales']:.2f}")
if T["Total Attributed Sales"] > 0:
    print(f"  整体 ACOS={T['Ad Spend']/T['Total Attributed Sales']*100:.1f}%  RoAS={T['Total Attributed Sales']/T['Ad Spend']:.2f}")
print(f"  不同关键词数: {len(agg)}")

winners, losers, mid = [], [], []
for (kw, mt), a in agg.items():
    if a["spend"] <= 0 and a["ord"] <= 0:
        continue
    roas = a["sales"] / a["spend"] if a["spend"] > 0 else 0
    acos = a["spend"] / a["sales"] * 100 if a["sales"] > 0 else 999
    rec = (kw, mt, a, acos, roas)
    if a["ord"] > 0 and roas >= 3:
        winners.append(rec)
    elif a["spend"] > 0 and a["ord"] == 0:
        losers.append(rec)
    else:
        mid.append(rec)

print(f"\n=== 有转化 & RoAS>=3 的赢家关键词 ({len(winners)}) ===")
for kw, mt, a, acos, roas in sorted(winners, key=lambda x: -x[4])[:30]:
    print(f"  [{mt:6}] {kw:42} spend=${a['spend']:.2f} ord={a['ord']:.0f} sales=${a['sales']:.2f} ACOS={acos:.0f}% RoAS={roas:.1f}")

print(f"\n=== 有花费但 0 订单 的亏损关键词 ({len(losers)}) ===")
for kw, mt, a, acos, roas in sorted(losers, key=lambda x: -x[2]["spend"])[:40]:
    print(f"  [{mt:6}] {kw:42} imp={a['imp']:.0f} clk={a['clk']:.0f} spend=${a['spend']:.2f}")
tot_waste = sum(a["spend"] for _, _, a, _, _ in losers)
print(f"  >> 亏损关键词总浪费花费 ~= ${tot_waste:.2f}")

print(f"\n=== 其他 (有花费有订单但RoAS<3) ({len(mid)}) ===")
for kw, mt, a, acos, roas in sorted(mid, key=lambda x: -x[2]["spend"])[:20]:
    print(f"  [{mt:6}] {kw:42} spend=${a['spend']:.2f} ord={a['ord']:.0f} sales=${a['sales']:.2f} ACOS={acos:.0f}% RoAS={roas:.1f}")
