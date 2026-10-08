"""Check variant structure + per-variant images + title length for PNK366085."""
import re
from collections import defaultdict
from openpyxl import load_workbook

SRC = r"C:\Users\Administrator\Desktop\PNK366085-Blouses-Tops-upload.xlsx"
OUT = r"D:\WorkBuddyData\walmart-ads\listing_dump\PNK366085_variant_check.txt"

wb = load_workbook(SRC, data_only=True, read_only=True)
ws = wb["Product Content And Site Exp"]
rows = [list(r) for r in ws.iter_rows(values_only=True)]
wb.close()


def s(v):
    return "" if v is None else str(v).strip()


id_idx = next(i for i, r in enumerate(rows) if len(r) > 3 and s(r[3]) == "sku")
ids = [s(x) for x in rows[id_idx]]
data = [r for r in rows[id_idx + 2:] if len(r) > 3 and s(r[3])]


def col(name, n=1):
    """1-based index of n-th column whose id == name"""
    cnt = 0
    for i, a in enumerate(ids):
        if a == name:
            cnt += 1
            if cnt == n:
                return i
    return None


ci = {k: col(k) for k in ["sku", "productName", "color", "clothingSize",
                          "clothingSizeGroup", "variantAttributeNames",
                          "isPrimaryVariant", "mainImageUrl", "swatchImageUrl",
                          "colorCategory", "price", "variantGroupId"]}
# variantAttributeNames appears twice
va1, va2 = col("variantAttributeNames", 1), col("variantAttributeNames", 2)
sec = [col("productSecondaryImageURL", n) for n in range(1, 9)]

L = []
L.append("### title / price")
titles = {s(r[ci["productName"]]) for r in data}
for t in titles:
    L.append(f"  title ({len(t)} chars): {t}")
L.append(f"  prices: {sorted({s(r[ci['price']]) for r in data})}")
L.append(f"  variantGroupId: {sorted({s(r[ci['variantGroupId']]) for r in data})}")

L.append("")
L.append("### variant axes")
for r in data[:3]:
    L.append(f"  {s(r[ci['sku']])}: variantAttrNames=[{s(r[va1])}, {s(r[va2])}] "
             f"sizeGroup={s(r[ci['clothingSizeGroup']])} size={s(r[ci['clothingSize']])} "
             f"isPrimary={s(r[ci['isPrimaryVariant']])}")
L.append(f"  isPrimaryVariant values: {sorted({s(r[ci['isPrimaryVariant']]) for r in data})}")
L.append(f"  clothingSizeGroup values: {sorted({s(r[ci['clothingSizeGroup']]) for r in data})}")
L.append(f"  clothingSize values: {sorted({s(r[ci['clothingSize']]) for r in data})}")

L.append("")
L.append("### image uniqueness (main + 8 secondary per colour)")
by_color = defaultdict(list)
for r in data:
    by_color[s(r[ci["color"]])].append(r)
for c in sorted(by_color):
    rs = by_color[c]
    mains = {s(r[ci["mainImageUrl"]]) for r in rs}
    sw = {s(r[ci["swatchImageUrl"]]) for r in rs}
    secs = {tuple(s(r[x]) for x in sec if x is not None) for r in rs}
    L.append(f"  {c:<15} rows={len(rs)} distinct_main={len(mains)} "
             f"distinct_swatch={len(sw)} distinct_secondary_sets={len(secs)}")

allmain = {s(r[ci["mainImageUrl"]]) for r in data}
L.append(f"  GLOBAL distinct main images across all 20 rows: {len(allmain)}")
L.append(f"  empty secondary slots per row: "
         f"{sorted({sum(1 for x in sec if x is not None and s(r[x]) == '') for r in data})}")

L.append("")
L.append("### colour / colorCategory map")
cc = defaultdict(set)
for r in data:
    cc[s(r[ci["color"]])].add(s(r[ci["colorCategory"]]))
for c in sorted(cc):
    L.append(f"  {c} -> {sorted(cc[c])}")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(L))
print("ok")
