# -*- coding: utf-8 -*-
"""款号多情景做货需求：保守(近90天跑率) / 基准(系统P50) / P90 安全口径。

严格复刻 ReplenishmentPlanner + ProductionPlanAllocator 的裁床规则（床=100，余数 ≥50 加一床，SKU 保底 5 件），
用于回答「不同需求口径下分别要做多少床」。前置：先跑 analyze_style_plan90.py 或直接 curl 接口拿到 raw json。

用法：
  python scripts/analyze_style_scenarios.py PNK366066 --json outputs/pnk366066_plan90_raw.json
"""
import argparse
import json
import math
import os
from collections import defaultdict

DB = dict(host='127.0.0.1', port=3306, user='root', password='root',
          database='walmart_ads', charset='utf8mb4')
BED = 100
PARTIAL = 0.5


def p50_from_db(parent, snapshot, window=28):
    import pymysql
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select sum(o.quantity), count(distinct coalesce(o.order_date, o.report_date))
                  from wm_ads_order_record o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                 where o.sku like %s
                   and coalesce(o.order_date, o.report_date)
                       between date_sub(%s, interval %s day) and %s
                   and (o.status is null or (
                        lower(o.status) not like '%%cancel%%'
                        and lower(o.status) not like '%%test%%'))
            """, (parent + '%', snapshot, window - 1, snapshot))
            units, _ = cur.fetchone()
    finally:
        conn.close()
    return float(units or 0) / window


def plan(rows, share, daily, horizon):
    by_color = defaultdict(list)
    total_raw = 0.0
    for r in rows:
        sh = share[r['sku']]
        demand = daily * horizon * sh
        wfs = r['wfsUnits'] or 0
        dom_avail = r['domesticAvailableUnits'] or 0
        sched = r['wipUnits'] or 0            # 排产供应（做货中；在途另计）
        no_repl = sh <= 0
        dom_ship = 0.0 if no_repl else min(math.floor(dom_avail), math.ceil(max(0.0, demand - wfs - sched)))
        raw = 0.0 if no_repl else max(0.0, demand - wfs - dom_ship - sched)
        total_raw += raw
        if raw > 0 and not no_repl:
            by_color[r['color']].append((r['sku'], raw))
    prod = {}
    for color, items in by_color.items():
        if not color:
            continue
        raw_total = sum(i[1] for i in items)
        full = int(math.floor(raw_total / BED))
        rem = raw_total - full * BED
        prod[color] = full * BED + (BED if rem >= PARTIAL * BED else 0)
    return prod, total_raw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('parent')
    ap.add_argument('--json', default=None)
    ap.add_argument('--horizon', type=int, default=90)
    args = ap.parse_args()

    path = args.json or f'outputs/{args.parent.lower()}_plan90_raw.json'
    data = json.load(open(path, encoding='utf-8'))['data']
    snapshot = data['snapshotDate']
    H = data.get('targetCoverageDays', args.horizon)
    rows = [r for r in data['recommendations'] if r['parentSku'] == args.parent]
    total_p90 = sum(r['p90DemandUnits'] or 0 for r in rows)

    p90_daily = total_p90 / H
    p50_daily = p50_from_db(args.parent, snapshot)
    trailing_daily = None
    # 近 90 天跑率（保守口径）
    import pymysql
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select sum(o.quantity) from wm_ads_order_record o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                 where o.sku like %s
                   and coalesce(o.order_date, o.report_date)
                       between date_sub(%s, interval 89 day) and %s
                   and (o.status is null or (
                        lower(o.status) not like '%%cancel%%'
                        and lower(o.status) not like '%%test%%'))
            """, (args.parent + '%', snapshot, snapshot))
            v = cur.fetchone()[0]
        trailing_daily = float(v or 0) / 90
    finally:
        conn.close()

    share = {r['sku']: (r['p90DemandUnits'] or 0) / (p90_daily * H) for r in rows}

    print(f'=== {args.parent} | 快照 {snapshot} | 覆盖 {H} 天 ===')
    print(f'日均：保守(近90天) {trailing_daily:.2f} | 基准(P50/近28天) {p50_daily:.2f} | P90 {p90_daily:.2f}\n')
    print(f'{"情景":<26}{"90天需求":>9}{"缺口":>8}{"做货":>8}{"床数":>6}   分布')
    for name, daily, label in [
        ('保守（近 90 天跑率）', trailing_daily, f'{trailing_daily*H:,.0f}'),
        ('基准（系统 P50）', p50_daily, f'{p50_daily*H:,.0f}'),
        ('P90 安全口径', p90_daily, f'{total_p90:,.0f}'),
    ]:
        prod, raw = plan(rows, share, daily, H)
        total = sum(prod.values())
        dist = ' '.join(f'{c.split("-")[-1]}={int(v)}' for c, v in sorted(prod.items(), key=lambda kv: -kv[1]) if v > 0)
        print(f'{name:<26}{label:>9}{raw:>8.0f}{total:>8.0f}{round(total/BED):>6}   {dist or "—"}')

    p50 = plan(rows, share, p50_daily, H)[0]
    p90 = plan(rows, share, p90_daily, H)[0]
    print('\n--- 分批解读 ---')
    base = sum(p50.values())
    print(f'P50 基础盘：{base:.0f} 件 = ' + ' + '.join(f'{c.split("-")[-1]} {int(v)}' for c, v in sorted(p50.items(), key=lambda kv: -kv[1]) if v > 0))
    inc = {c: p90.get(c, 0) - p50.get(c, 0) for c in p90}
    inc = {c: v for c, v in inc.items() if v > 0}
    print(f'P90 相对 P50 的安全增量：{sum(inc.values()):.0f} 件 = ' + ' + '.join(f'{c.split("-")[-1]} {int(v)}' for c, v in sorted(inc.items(), key=lambda kv: -kv[1])))


if __name__ == '__main__':
    main()
