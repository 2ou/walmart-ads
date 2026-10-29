# -*- coding: utf-8 -*-
"""按 7 个活动资格指标，把主表拆成 7 个子表（每个指标一张，只放该指标为 N 的行）。
直接改写 xlsx 包结构：新增 sheet2..sheet8，主表 Sheet 原样不动。
"""
import re
import shutil
import zipfile

SRC = r"C:/Users/Administrator/Desktop/1016-Holiday Event 1-活动资格库存检查_10001456058_2026-10-16.xlsx"
NS = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'

INDICATORS = [
    ("U", "1-低于现售价", "Lower than Current Price = N：Promo 价未低于前台现售价"),
    ("V", "2-缺划线价", "Has Comparison Price = N：缺失划线价，需 10.21 前申请豁免或设置 List Price"),
    ("W", "3-划线价9折未达", "Comparison Price 10% OFF Met = N：Promo 价未达划线价 9 折"),
    ("X", "4-未低于L60D", "Lower than L60D Price = N：Promo 价未低于过去 60 天最低价"),
    ("Y", "5-高于AMZ价", "Promo Price < AMZ Price = N：Promo 价高于竞品价"),
    ("Z", "6-CQ低于80", "CQ >= 80 = N：内容质量分不足 80"),
    ("AA", "7-评分不合格", "Rating = 0 or Rating > 3.5 = N：有评分但未达 3.5"),
]


def main():
    z = zipfile.ZipFile(SRC)
    xml = z.read("xl/worksheets/sheet1.xml").decode("utf-8")

    # 拆行：保留每行原始单元格 XML（含样式 s、类型 t、值）
    row_re = re.compile(r'<row r="(\d+)"[^>]*>(.*?)</row>', re.S)
    cell_re = re.compile(r"<c\b[^>]*>.*?</c>|<c\b[^>]*/>", re.S)
    rows = {}
    for m in row_re.finditer(xml):
        rn = int(m.group(1))
        cells = []
        for cm in cell_re.finditer(m.group(2)):
            raw = cm.group(0)
            col = re.match(r'<c[^>]*\br="([A-Z]+)\d+"', raw).group(1)
            cells.append((col, raw))
        rows[rn] = cells

    header = rows[1]
    data_rows = sorted(r for r in rows if r > 1)

    # 共享字符串表（编辑器另存后单元格是 t="s" 索引）
    from xml.etree import ElementTree as ET
    sst = []
    if "xl/sharedStrings.xml" in z.namelist():
        sroot = ET.fromstring(z.read("xl/sharedStrings.xml"))
        sns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
        sst = ["".join(t.text or "" for t in si.iter(sns + "t")) for si in sroot.findall(sns + "si")]

    def cell_val(cells, col):
        for c, raw in cells:
            if c == col:
                t = re.search(r'\bt="([^"]+)"', raw)
                t = t.group(1) if t else None
                if t == "inlineStr":
                    return "".join(re.findall(r"<t[^>]*>(.*?)</t>", raw, re.S))
                v = re.search(r"<v>(.*?)</v>", raw, re.S)
                if v is None:
                    return ""
                return sst[int(v.group(1))] if t == "s" else v.group(1)
        return ""

    def shift(cell, newrow):
        return re.sub(r'(<c[^>]*\br=")([A-Z]+)\d+"',
                      lambda m: m.group(1) + m.group(2) + str(newrow) + '"', cell, count=1)

    def build_row(cells, newrow):
        return '<row r="%d">%s</row>' % (newrow, "".join(shift(c, newrow) for _, c in cells))

    def build_sheet(name, idx, hit_rows, note):
        body = [build_row(header, 1)]
        n = 1
        if hit_rows:
            for r in hit_rows:
                n += 1
                body.append(build_row(rows[r], n))
        else:
            n += 1
            body.append('<row r="2"><c r="A2" t="inlineStr"><is><t>%s</t></is></c></row>' % note)
        return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
                '<worksheet %s><dimension ref="A1:AF%d"/><sheetViews><sheetView workbookViewId="0"/></sheetViews>'
                '<sheetFormatPr defaultRowHeight="15"/>'
                '<cols><col min="32" max="32" width="36" customWidth="1"/></cols>'
                '<sheetData>%s</sheetData>'
                '<pageMargins left="0.75" right="0.75" top="1" bottom="1" header="0.5" footer="0.5"/>'
                '</worksheet>') % (NS, n, "".join(body))

    sheets = []
    for i, (col, name, note) in enumerate(INDICATORS):
        hits = [r for r in data_rows if cell_val(rows[r], col) == "N"]
        sheets.append((name, 2 + i, build_sheet(name, i, hits, note), len(hits)))
        print("%-16s %-4s N=%d" % (name, col, len(hits)))

    # workbook.xml：追加 sheet 定义
    wb = z.read("xl/workbook.xml").decode("utf-8")
    add = "".join('<sheet name="%s" sheetId="%d" r:id="rId%d"/>' % (n, sid, sid + 2)
                  for n, sid, _, _ in sheets)
    wb = wb.replace("</sheets>", add + "</sheets>")

    # rels：追加 worksheet 关系（原 sheet1 = rId3）
    rels = z.read("xl/_rels/workbook.xml.rels").decode("utf-8")
    add_r = "".join('<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
                    'relationships/worksheet" Target="worksheets/sheet%d.xml" />' % (sid + 2, sid)
                    for _, sid, _, _ in sheets)
    rels = rels.replace("</Relationships>", add_r + "</Relationships>")

    # content types：追加 Override
    ct = z.read("[Content_Types].xml").decode("utf-8")
    add_ct = "".join('<Override PartName="/xl/worksheets/sheet%d.xml" ContentType="application/vnd.'
                     'openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' % sid
                     for _, sid, _, _ in sheets)
    ct = ct.replace("</Types>", add_ct + "</Types>")

    # 重打包
    tmp = SRC + ".new"
    zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
    for item in z.infolist():
        if item.filename == "xl/workbook.xml":
            data = wb.encode("utf-8")
        elif item.filename == "xl/_rels/workbook.xml.rels":
            data = rels.encode("utf-8")
        elif item.filename == "[Content_Types].xml":
            data = ct.encode("utf-8")
        else:
            data = z.read(item.filename)
        zout.writestr(item, data)
    for name, sid, content, _ in sheets:
        zout.writestr("xl/worksheets/sheet%d.xml" % sid, content.encode("utf-8"))
    zout.close()
    z.close()
    shutil.move(tmp, SRC)
    print("saved ->", SRC)


if __name__ == "__main__":
    main()
