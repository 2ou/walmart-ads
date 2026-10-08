"""Verify the patched workbook: only the intended columns may differ from source."""
import os
from openpyxl import load_workbook

SRC = r"C:\Users\Administrator\Desktop\PNK366085-Blouses-Tops-upload.xlsx"
DST = r"D:\Tools\Code\walmart-ads\outputs\PNK366085-Blouses-Tops-upload-optimized-20260916.xlsx"
LOG = r"D:\WorkBuddyData\walmart-ads\listing_dump\patch_verify.txt"

EXPECTED = {8, 42, 43, 44, 45, 46, 47, 48, 103, 111, 112, 115, 116, 125, 132,
            139, 148, 149, 153, 154, 156}

L = []
wa = load_workbook(SRC)
wb = load_workbook(DST)

L.append("sheets SRC: %s" % wa.sheetnames)
L.append("sheets DST: %s" % wb.sheetnames)
L.append("sheets identical: %s" % (wa.sheetnames == wb.sheetnames))
L.append("hidden states SRC: %s" % [wa[s].sheet_state for s in wa.sheetnames])
L.append("hidden states DST: %s" % [wb[s].sheet_state for s in wb.sheetnames])

diffs = {}
for name in wa.sheetnames:
    a, b = wa[name], wb[name]
    L.append("-- %s: dims SRC=%dx%d DST=%dx%d" % (name, a.max_row, a.max_column, b.max_row, b.max_column))
    for r in range(1, max(a.max_row, b.max_row) + 1):
        for c in range(1, max(a.max_column, b.max_column) + 1):
            va = a.cell(row=r, column=c).value
            vb = b.cell(row=r, column=c).value
            if va != vb:
                diffs.setdefault(name, {}).setdefault(c, []).append(r)

L.append("")
L.append("=== CELL DIFFS ===")
for name, cold in diffs.items():
    for c in sorted(cold):
        rows = cold[c]
        lbl = wa[name].cell(row=4, column=c).value or wa[name].cell(row=5, column=c).value
        aid = wa[name].cell(row=5, column=c).value
        v1 = wb[name].cell(row=rows[0], column=c).value
        L.append("  %s col=%d (%s / %s) rows=%d..%d n=%d -> %s" % (
            name, c, str(lbl)[:30], str(aid)[:24], min(rows), max(rows), len(rows), str(v1)[:60]))

L.append("")
ok = True
if set(diffs.keys()) - {"Product Content And Site Exp"}:
    ok = False
    L.append("!! unexpected sheet changed")
else:
    got = set(diffs.get("Product Content And Site Exp", {}).keys())
    extra = got - EXPECTED
    missing = EXPECTED - got
    L.append("changed cols      = %s" % sorted(got))
    L.append("expected cols     = %s" % sorted(EXPECTED))
    L.append("unexpected (BUG)  = %s" % sorted(extra))
    L.append("expected-but-same = %s" % sorted(missing))
    if extra or missing:
        ok = False

# data validations + merged cells preservation
for name in wa.sheetnames:
    dva = len(wa[name].data_validations.dataValidation)
    dvb = len(wb[name].data_validations.dataValidation)
    ma, mb = len(wa[name].merged_cells.ranges), len(wb[name].merged_cells.ranges)
    L.append("%s dv %d->%d | merged %d->%d" % (name, dva, dvb, ma, mb))

L.append("")
L.append("RESULT: %s" % ("PASS" if ok else "REVIEW NEEDED"))

# write it only if it looks clean
with open(LOG, "w", encoding="utf-8") as f:
    f.write("\n".join(L))
print("WROTE", LOG, "|", "PASS" if ok else "REVIEW")
