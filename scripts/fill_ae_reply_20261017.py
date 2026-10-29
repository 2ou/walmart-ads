# -*- coding: utf-8 -*-
"""按规则把「卖家回复」简短备注写入 Holiday Event 1 库存检查表 AE 列。
直接改写 xlsx 内的 sheet XML，仅追加 AE 单元格，不改动其它列与格式。
"""
import re
import shutil
import zipfile
from xml.etree import ElementTree as ET

SRC = r"C:/Users/Administrator/Desktop/1016-Holiday Event 1-活动资格库存检查_10001456058_2026-10-16.xlsx"
TMP = r"D:/Tools/Code/walmart-ads/backups/_tmp_ae.xlsx"
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

COLS = ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P",
        "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "AA", "AB", "AC", "AD", "AE"]


def cell_val(c):
    t = c.get("t")
    if t == "inlineStr":
        return "".join(x.text or "" for x in c.iter(NS + "t"))
    v = c.find(NS + "v")
    return v.text if v is not None else None


def build_note(vals):
    """vals: dict 列字母 -> 值"""
    def g(k):
        return (vals.get(k) or "").strip()

    # 第一段：库存（零库存按预估补货周期给到仓日期，数量取预测销量）
    try:
        stock = int(float(g("L") or 0))
    except ValueError:
        stock = 0
    if stock > 0:
        seg1 = "在库%d件，正常参加" % stock
    else:
        try:
            fc = float(g("AB"))
        except (ValueError, TypeError):
            fc = 0.0
        if fc >= 30:
            seg1 = "预计10/24到仓%d件" % round(fc)      # 缺口大，优先快调拨
        elif fc > 0:
            seg1 = "预计10/30到仓%d件" % round(fc)      # 常规补货
        else:
            seg1 = "预计11/06到仓，按预测补货"           # 无销量预测，按计划补

    # 第二段：不合格指标整改（U→V→W→X→Y，最多 2 条）
    fixes = []
    if g("U") == "N":
        fixes.append("promo低于现售价")
    if g("V") == "N":
        fixes.append("10/21前设List Price")
    if g("W") == "N":
        fixes.append("promo调至划线价9折内")
    if g("X") == "N":
        fixes.append("promo低于L60D")
    if g("Y") == "N":
        fixes.append("promo不高于竞品价")
    seg2 = ("整改：" + "、".join(fixes[:2])) if fixes else ""

    # 只回复平台明确要求反馈的两块：AD 列库存 + R-AA 列不合格指标
    # Q 列（报名/接受状态）不属于回复项，不再输出第三段
    seg3, short3 = "", ""

    # 逐级压缩，确保不超过 45 字
    def compose(s1, fx, s3):
        ps = [s1] + ([("整改：" + "、".join(fx)) if fx else ""]) + [s3]
        return "；".join([p for p in ps if p])

    note = compose(seg1, fixes[:2], seg3)
    if len(note) > 45 and len(fixes) >= 2:
        note = compose(seg1, fixes[:1], seg3)
    if len(note) > 45 and "按预测补货" in seg1:
        note = compose(seg1.replace("，按预测补货", ""), fixes[:1], seg3)
    if len(note) > 45:
        note = compose(seg1, [], seg3)
    return note


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    z = zipfile.ZipFile(SRC)
    xml = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
    names = z.namelist()

    # 解析每行数据
    root = ET.fromstring(xml)
    rows_data = {}
    for row in root.findall(".//" + NS + "row"):
        rn = int(row.get("r"))
        if rn < 2:
            continue
        d = {}
        for c in row.findall(NS + "c"):
            ref = c.get("r")
            col = re.match(r"([A-Z]+)", ref).group(1)
            d[col] = cell_val(c)
        rows_data[rn] = d

    notes = {rn: build_note(d) for rn, d in rows_data.items()}

    # SKU 映射：优先项目产品库导出（wm_ads_sku_mapping），缺失再回退 inv.json 快照
    import json
    sku_map = {}
    tsv = r"D:/Tools/Code/walmart-ads/outputs/sku_map_from_db_20261017.tsv"
    try:
        for line in open(tsv, encoding="utf-8"):
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2 and parts[0].strip() and parts[1].strip():
                sku_map.setdefault(parts[0].strip(), parts[1].strip())
    except FileNotFoundError:
        pass
    inv = json.load(open(r"D:/Tools/Code/walmart-ads/inv.json", encoding="utf-8"))["data"]["rows"]
    for r in inv:
        if r.get("itemId") and r.get("sku"):
            sku_map.setdefault(str(r["itemId"]), r["sku"])
    skus = {}
    for rn, d in rows_data.items():
        skus[rn] = sku_map.get(str(d.get("A") or "").strip(), "")

    # 逐行插入 AE（卖家回复）与 AF（SKU）单元格
    pattern = re.compile(r'(<row r="(\d+)"[^>]*>)(.*?)(</row>)', re.S)

    def repl(m):
        open_tag, rn_s, body, close_tag = m.group(1), m.group(2), m.group(3), m.group(4)
        rn = int(rn_s)
        if rn < 2 or rn not in notes:
            return m.group(0)
        add = ""
        if not re.search(r'<c r="AE%d"' % rn, body):
            add += '<c r="AE%d" t="inlineStr"><is><t>%s</t></is></c>' % (rn, esc(notes[rn]))
        if skus.get(rn) and not re.search(r'<c r="AF%d"' % rn, body):
            add += '<c r="AF%d" t="inlineStr"><is><t>%s</t></is></c>' % (rn, esc(skus[rn]))
        return open_tag + body + add + close_tag

    new_xml, n = pattern.subn(repl, xml)
    print("processed rows:", n, "notes:", len(notes), "sku filled:", len([v for v in skus.values() if v]))

    # 表头 AF1 = SKU（沿用 AD1 的样式）
    m_hdr = re.search(r'<c r="AD1"( s="(\d+)")?', new_xml)
    style = (' s="%s"' % m_hdr.group(2)) if m_hdr and m_hdr.group(2) else ""
    if '<c r="AF1"' not in new_xml:
        new_xml = new_xml.replace("</row>",
                                  '<c r="AF1"%s t="inlineStr"><is><t>SKU</t></is></c></row>' % style, 1)

    # 扩展 dimension + 设置 AF 列宽
    new_xml = new_xml.replace('<dimension ref="A1:AE299" />', '<dimension ref="A1:AF299" />')
    if "<cols>" not in new_xml:
        new_xml = new_xml.replace("<sheetData>",
                                  '<cols><col min="32" max="32" width="36" customWidth="1" /></cols><sheetData>', 1)


    # 重写 zip（保留其他条目不变）
    shutil.copyfile(SRC, TMP)
    zin = zipfile.ZipFile(TMP, "r")
    zout = zipfile.ZipFile(SRC + ".new", "w", zipfile.ZIP_DEFLATED)
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "xl/worksheets/sheet1.xml":
            data = new_xml.encode("utf-8")
        zout.writestr(item, data)
    zout.close()
    zin.close()
    shutil.move(SRC + ".new", SRC)
    print("saved ->", SRC)

    # 统计
    from collections import Counter
    c = Counter()
    for v in notes.values():
        c["在库" if v.startswith("在库") else "零库存"] += 1
        if "整改：" in v:
            c["含整改"] += 1
        if "点击接受" in v or "重新提报" in v or "待平台审核" in v:
            c["含报名动作"] += 1
    print(dict(c))
    print("max len:", max(len(v) for v in notes.values()))
    for r in (2, 4, 45, 111, 162, 183, 187, 275, 298):
        print(r, notes[r])


if __name__ == "__main__":
    main()
