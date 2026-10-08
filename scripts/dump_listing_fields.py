"""Field-level dump of the PNK366085 listing upload sheet.

Prints every attribute column with its English label + attribute id + value
for the first data row, plus the empty-attribute list, plus variant inventory.
"""
import os
import re
from openpyxl import load_workbook

SRC = r"C:\Users\Administrator\Desktop\PNK366085-Blouses-Tops-upload.xlsx"
OUT = r"D:\WorkBuddyData\walmart-ads\listing_dump\PNK366085_fields.txt"

wb = load_workbook(SRC, data_only=True, read_only=True)
ws = wb["Product Content And Site Exp"]
rows = [list(r) for r in ws.iter_rows(values_only=True)]
wb.close()


def s(v):
    if v is None:
        return ""
    return str(v).strip()


# locate the attribute-id row (col4 == 'sku'), English-label row is directly above
id_idx = None
for i, r in enumerate(rows):
    if len(r) > 3 and s(r[3]) == "sku":
        id_idx = i
        break
label_idx = id_idx - 1
# help text row sits directly below the id row
help_idx = id_idx + 1

labels = rows[label_idx]
ids = rows[id_idx]
helps = rows[help_idx]

ncol = max(len(labels), len(ids))
cols = []
for c in range(ncol):
    lab = s(labels[c]) if c < len(labels) else ""
    aid = s(ids[c]) if c < len(ids) else ""
    cols.append((c + 1, lab, aid))

first = rows[help_idx + 1]
# find last data row (any non-empty in col 4)
data = [r for r in rows[help_idx + 1:] if len(r) > 3 and s(r[3])]

lines = []
lines.append("=" * 90)
lines.append("PART 1 — FILLED ATTRIBUTES (row = first SKU)")
lines.append("=" * 90)
empty = []
for c, lab, aid in cols:
    if c <= 3:
        continue
    val = s(first[c - 1]) if c - 1 < len(first) else ""
    head = f"[col{c:>3}] {lab or '(no label)'}  <{aid}>"
    if val == "":
        empty.append(head)
    else:
        v = val.replace("\n", " ⏎ ")
        lines.append(head)
        lines.append("    " + v)

lines.append("")
lines.append("=" * 90)
lines.append(f"PART 2 — EMPTY ATTRIBUTES ({len(empty)} of "
             f"{len([c for c, l, a in cols if c > 3 and (l or a)])} meaningful columns)")
lines.append("=" * 90)
for h in empty:
    lines.append("  EMPTY " + h)

# PART 3 — per-column help text (what Walmart expects) for the filled/empty ones
lines.append("")
lines.append("=" * 90)
lines.append("PART 3 — WALMART SPEC (help text) for key attributes")
lines.append("=" * 90)
KEY = ["sku", "productName", "brand", "price", "shortDescription", "keyFeatures",
       "mainImageUrl", "productSecondaryImageURL", "color", "clothingSize",
       "shirtNeckStyle", "sleeveLengthStyle", "materialName", "materialPercentage",
       "fabricCareInstructions", "clothingStyle", "clothingTopStyle", "colorCategory",
       "ageGroup", "gender", "clothingSizeGroup", "clothingFit", "clothingOccasion",
       "clothingFeature", "clothing_back_style", "collarType", "patternName",
       "character", "count", "countPerPack", "multipackQuantity", "isProp65WarningRequired",
       "productNetContentUnit", "productNetContentMeasure", "has_written_warranty",
       "zippered", "embellishmentType", "fabric_construction", "fastenerType",
       "clothing_shoulder_style", "designer", "globalBrandLicense", "certification_type"]
for c, lab, aid in cols:
    if c <= 3:
        continue
    a_low = (aid or "").lower()
    if any(k.lower() == a_low for k in KEY) or lab.lower() in [k.lower() for k in KEY]:
        h = s(helps[c - 1]) if c - 1 < len(helps) else ""
        lines.append(f"--- {lab}  <{aid}>")
        lines.append("    " + h[:900])

# PART 4 — variants
lines.append("")
lines.append("=" * 90)
lines.append(f"PART 4 — VARIANTS ({len(data)} data rows)")
lines.append("=" * 90)
for r in data:
    sku = s(r[3])
    name = s(r[6]) if len(r) > 6 else ""
    price = s(r[8]) if len(r) > 8 else ""
    m = re.match(r"^(.*?)-(C|FP)-(.+)-([^-]+)-WFS$", sku)
    if m:
        sku2 = f"{m.group(1)} | {m.group(3)} | {m.group(4)}"
    else:
        sku2 = sku
    lines.append(f"  {sku2:<45} price={price}")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("wrote", OUT, "lines", len(lines))
