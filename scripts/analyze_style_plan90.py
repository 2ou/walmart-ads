# -*- coding: utf-8 -*-
"""按款号分析「90 天覆盖」做货需求（口径 100% 复用线上 /api/forecast/recommendations?targetDays=90）。

用法：
  python scripts/analyze_style_plan90.py PNK366066 --json outputs/pnk366066_plan90_raw.json

输出：
  - 控制台：款级汇总 + 按颜色 + 按尺码 + 需做货明细
  - outputs/<款号>_plan90_summary.json：结构化结果（供报告/表格复用）
"""
import argparse
import json
import os
from collections import defaultdict

DB = dict(host='127.0.0.1', port=3306, user='root', password='root',
          database='walmart_ads', charset='utf8mb4')


def fnum(v):
    try:
        return float(v or 0)
    except (TypeError, ValueError):
        return 0.0


def load_plan(path, parent):
    with open(path, encoding='utf-8') as fh:
        payload = json.load(fh)
    data = payload.get('data', payload)
    recs = [r for r in data.get('recommendations', []) if r.get('parentSku') == parent]
    return data, recs


def query_cutting_yield(parent):
    """返回 {(color_lower, size): cutting_yield} 以及按颜色的裁床数（同款同色共用）。"""
    import pymysql
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select sku, color, size, cutting_yield
                  from wm_ads_product_record
                 where current_version = true and sku like %s
            """, (parent + '%',))
            rows = cur.fetchall()
    finally:
        conn.close()
    by_sku, by_color = {}, {}
    for sku, color, size, cy in rows:
        if color is None:
            continue
        key = color.strip().lower()
        by_sku[sku] = cy
        if cy is not None:
            by_color[key] = max(by_color.get(key, 0) or 0, cy)
    return by_sku, by_color


def query_daily_sales(parent, days=120):
    import pymysql
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select coalesce(o.order_date, o.report_date) as d, sum(o.quantity) as q
                  from wm_ads_order_record o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                 where o.sku like %s
                   and coalesce(o.order_date, o.report_date) >= date_sub(curdate(), interval %s day)
                   and (o.status is null or (
                        lower(o.status) not like '%%cancel%%'
                        and lower(o.status) not like '%%test%%'))
                 group by d
                 order by d
            """, (parent + '%', days))
            rows = cur.fetchall()
    finally:
        conn.close()
    return [(str(d), int(q)) for d, q in rows]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('parent')
    ap.add_argument('--json', default=None)
    ap.add_argument('--out-dir', default='outputs')
    args = ap.parse_args()

    path = args.json or os.path.join(args.out_dir, f'{args.parent.lower()}_plan90_raw.json')
    data, recs = load_plan(path, args.parent)
    snapshot = data.get('snapshotDate')
    horizon = data.get('targetCoverageDays')
    model = recs[0]['forecastModel'] if recs else None

    by_sku_yield, color_yield = query_cutting_yield(args.parent)
    daily = query_daily_sales(args.parent)

    # 款级汇总
    total_demand = sum(fnum(r['p90DemandUnits']) for r in recs)
    total_wfs = sum(fnum(r['wfsUnits']) for r in recs)
    total_dom = sum(fnum(r['domesticAvailableUnits']) for r in recs)
    total_dom_ship = sum(fnum(r['domesticShipmentUnits']) for r in recs)
    total_wip = sum(fnum(r['wipUnits']) for r in recs)
    total_raw = sum(fnum(r['rawProductionUnits']) for r in recs)
    total_prod = sum(fnum(r['productionUnits']) for r in recs)
    total_surplus = sum(fnum(r['cuttingSurplusUnits']) for r in recs)
    status_count = defaultdict(int)
    for r in recs:
        status_count[r['status']] += 1

    # 按颜色
    colors = defaultdict(lambda: dict(demand=0, wfs=0, dom=0, wip=0, raw=0, prod=0, skus=0, stockout=None))
    for r in recs:
        c = colors[r['color']]
        c['demand'] += fnum(r['p90DemandUnits'])
        c['wfs'] += fnum(r['wfsUnits'])
        c['dom'] += fnum(r['domesticAvailableUnits'])
        c['wip'] += fnum(r['wipUnits'])
        c['raw'] += fnum(r['rawProductionUnits'])
        c['prod'] += fnum(r['productionUnits'])
        c['skus'] += 1
        d = r['expectedStockoutDate']
        if d and (c['stockout'] is None or d < c['stockout']):
            c['stockout'] = d

    # 按尺码
    sizes = defaultdict(lambda: dict(demand=0, wfs=0, dom=0, wip=0, prod=0, skus=0))
    for r in recs:
        s = sizes[r['size']]
        s['demand'] += fnum(r['p90DemandUnits'])
        s['wfs'] += fnum(r['wfsUnits'])
        s['dom'] += fnum(r['domesticAvailableUnits'])
        s['wip'] += fnum(r['wipUnits'])
        s['prod'] += fnum(r['productionUnits'])
        s['skus'] += 1

    def size_key(x):
        order = {'S': 0, 'M': 1, 'L': 2, 'XL': 3, '2XL': 4, '3XL': 5, '4XL': 6, '5XL': 7}
        return order.get(x, 99)

    print(f'=== {args.parent} | 快照 {snapshot} | 覆盖 {horizon} 天 | 模型 {model} ===')
    print(f'P90 需求合计 {total_demand:,.1f} | WFS可售 {total_wfs:,.0f} | 国内仓现有 {total_dom:,.0f} '
          f'(建议发 {total_dom_ship:,.0f}) | 做货中 {total_wip:,.0f}')
    print(f'缺口(raw) {total_raw:,.1f} → 裁床取整后做货建议 {total_prod:,.0f}'
          f' | 裁床溢出 {total_surplus:,.0f}')
    print(f'SKU 数 {len(recs)} | 状态分布 {dict(sorted(status_count.items()))}')
    print()
    print('--- 按颜色 ---')
    print(f'{"颜色":<20}{"P90需求":>9}{"WFS":>7}{"国内仓":>7}{"做货中":>7}{"缺口":>8}{"做货建议":>9}{"裁床":>6}{"最早断货":>12}')
    for color, c in sorted(colors.items(), key=lambda kv: -kv[1]['prod']):
        cy = color_yield.get(color.strip().lower())
        print(f'{color:<20}{c["demand"]:>9.0f}{c["wfs"]:>7.0f}{c["dom"]:>7.0f}{c["wip"]:>7.0f}'
              f'{c["raw"]:>8.0f}{c["prod"]:>9.0f}{str(cy):>6}{str(c["stockout"] or "-"):>12}')
    print()
    print('--- 按尺码 ---')
    print(f'{"尺码":<6}{"SKU":>4}{"P90需求":>9}{"WFS":>7}{"国内仓":>7}{"做货中":>7}{"做货建议":>9}')
    for size, s in sorted(sizes.items(), key=lambda kv: size_key(kv[0])):
        print(f'{size:<6}{s["skus"]:>4}{s["demand"]:>9.0f}{s["wfs"]:>7.0f}{s["dom"]:>7.0f}'
              f'{s["wip"]:>7.0f}{s["prod"]:>9.0f}')
    print()
    print('--- 需做货 SKU 明细（productionUnits>0）---')
    print(f'{"SKU":<44}{"P90需求":>8}{"WFS":>6}{"国内仓":>6}{"做货中":>6}{"缺口":>7}{"做货":>6}{"状态":>16}{"预计断货":>12}')
    need = [r for r in recs if fnum(r['productionUnits']) > 0]
    for r in sorted(need, key=lambda x: (-fnum(x['productionUnits']))):
        print(f'{r["sku"]:<44}{fnum(r["p90DemandUnits"]):>8.0f}{fnum(r["wfsUnits"]):>6.0f}'
              f'{fnum(r["domesticAvailableUnits"]):>6.0f}{fnum(r["wipUnits"]):>6.0f}'
              f'{fnum(r["rawProductionUnits"]):>7.0f}{fnum(r["productionUnits"]):>6.0f}'
              f'{r["status"]:>16}{str(r["expectedStockoutDate"] or "-"):>12}')
    uncovered = [r for r in recs if r['status'] == 'BED_NOT_COVERED']
    if uncovered:
        print()
        print('--- 有缺口但裁床未覆盖（需人工决策）---')
        for r in uncovered:
            print(f'  {r["sku"]:<44} 缺口 {fnum(r["rawProductionUnits"]):>6.0f} '
                  f'P90需求 {fnum(r["p90DemandUnits"]):>6.0f} 断货 {r["expectedStockoutDate"]}')

    if daily:
        last28 = daily[-28:]
        prev28 = daily[-56:-28]
        print()
        print(f'--- 日销校验（近 {len(daily)} 天有记录）---')
        print(f'近28天合计 {sum(q for _, q in last28)} | 前28天合计 {sum(q for _, q in prev28)}'
              f' | 近28日均 {sum(q for _, q in last28)/len(last28):.2f}/天')
        print('最近 14 天：', ', '.join(f'{d[5:]}:{q}' for d, q in daily[-14:]))

    summary = dict(
        parent=args.parent, snapshotDate=snapshot, horizonDays=horizon, model=model,
        totalDemand=round(total_demand, 1), totalWfs=total_wfs, totalDomestic=total_dom,
        domesticShipment=total_dom_ship, totalWip=total_wip, rawProduction=round(total_raw, 1),
        production=total_prod, cuttingSurplus=total_surplus,
        statusCount=dict(sorted(status_count.items())),
        byColor={k: {kk: (round(vv, 1) if isinstance(vv, float) else vv) for kk, vv in v.items()}
                 for k, v in colors.items()},
        bySize={k: {kk: (round(vv, 1) if isinstance(vv, float) else vv) for kk, vv in v.items()}
                for k, v in sizes.items()},
        rows=recs,
    )
    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, f'{args.parent.lower()}_plan90_summary.json')
    with open(out_path, 'w', encoding='utf-8') as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
    print(f'\n已写出 {out_path}')


if __name__ == '__main__':
    main()
