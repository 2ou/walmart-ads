# -*- coding: utf-8 -*-
"""回填：把匹配到的单件实际配送费写入 wm_ads_product_record.delivery_fee（按 SKU 维护）。

口径（与「产品库SKU实际配送费」报表一致，严格真实、不估算）：
  - 仅用 wm_ads_wfs_fulfillment_fee 中 transaction_type='FulfillmentFee' 且 qty=1 的单件标准行
  - 单个 SKU 的配送费是固定值；偏低值系大促补贴，应以「补贴前（未补贴）标准费」为准
    => 取该 SKU 全部 qty=1 样本中的最大值（net_payable）。补贴只会压低费用，故最大值即补贴前标准费
  - 写入 wm_ads_product_record 中 current_version=1 的对应 SKU 行
  - 没匹配到实测样本的 SKU 不动（保持 NULL），不填充、不估算
  - 人工修正 MANUAL_FIX：个别 SKU 的 max 口径会得到错档/异常收费（如同款更小码反而比大码贵一档、
    系统称重记录混乱），经人工核定后用固定值覆盖，重跑仍保留（幂等）。

用法：
  python scripts/backfill_product_delivery_fee.py            # 执行回填
  python scripts/backfill_product_delivery_fee.py --dry-run  # 只统计，不写库
"""
import argparse
from collections import defaultdict
from decimal import Decimal

import pymysql

DB = dict(host='127.0.0.1', port=3306, user='root', password='root',
          database='walmart_ads', charset='utf8mb4')

# 人工修正：算法 max(=补贴前固定费) 结果明显异常、经人工核定后覆盖（重跑仍保留）。
# 结构：{sku: (人工核定费, 原因)}
# PNK366078-FP-Leopard Khaki-2X-WFS：2026-06 该 SKU 19 笔混收 5.45/6.25（系统称重记录混乱），
#   6.25 系错档（需 >1.50lb，实际申报 0.86~1.04lb 够不到）；同款更大码 3X/4X 2026-07 重测
#   实重 1.22lb -> 稳定收 5.95，2X 更小更轻物理上不可能更高。人工核定为 5.95（同款重测档位口径）。
#   SKU 已停售（最后一单 2026-06-19），仍 PUBLISHED，此值会用于利润测算。
MANUAL_FIX = {
    'PNK366078-FP-Leopard Khaki-2X-WFS': (Decimal('5.95'),
        '同款3X/4X重测收5.95，2X更小不该更高；6.25系2026-06错档(同月混收5.45/6.25)'),
}


def f(v):
    return None if v is None else float(v)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true', help='只统计不写库')
    args = ap.parse_args()

    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    # 1) 产品库生效 SKU
    cur.execute("SELECT sku FROM wm_ads_product_record WHERE current_version=1")
    prod_skus = {r[0].strip() for r in cur.fetchall()}
    print('产品库生效 SKU:', len(prod_skus))

    # 2) 单件配送费真实样本（按 SKU -> 期 -> [candidate]）
    #    行内候选费 = coalesce(original_amount, net_payable) 取较大者：
    #      original_amount 是沃尔玛列示的未补贴(gross)费，net_payable 是实付（含补贴）。
    #      本库 original_amount 约 55% 为 NULL，必须回退 net_payable；补贴只会压低费用，
    #      故行内取二者较大者即"该行补贴前费"。
    cur.execute("""
        SELECT sku, period_label, net_payable, original_amount
        FROM wm_ads_wfs_fulfillment_fee
        WHERE transaction_type='FulfillmentFee' AND qty=1
          AND sku IS NOT NULL AND sku<>''
    """)
    raw = defaultdict(lambda: defaultdict(list))
    for sku, pl, net, orig in cur.fetchall():
        s = sku.strip()
        cand = f(net)
        if orig is not None:
            o = f(orig)
            cand = o if (o is not None and o > cand) else cand
        raw[s][pl].append(cand)

    # 3) 计算每 SKU 的「补贴前固定配送费」= 全部单件候选费的最大值
    #    补贴只会把费用压低，因此最大值即未补贴的标准费（单 SKU 配送费固定）。
    updates = {}  # sku -> fee(Decimal)
    subsidized = 0
    for sku in prod_skus:
        samples = raw.get(sku)
        if not samples:
            continue
        all_vals = [v for pl in samples.values() for v in pl]
        if not all_vals:
            continue
        fee = round(max(all_vals), 2)
        lo = round(min(all_vals), 2)
        if lo < fee - 1e-9:
            subsidized += 1
        updates[sku] = Decimal(str(fee))

    print('匹配到实测配送费的 SKU:', len(updates))
    print('其中观测到补贴（最低值 < 最大值）的 SKU:', subsidized)

    # 3b) 人工修正覆盖（无论是否有实测样本，均按人工核定值写入）
    for sku, (fee, reason) in MANUAL_FIX.items():
        updates[sku] = fee
        print('人工修正 SKU: %s -> %s  (%s)' % (sku, fee, reason))
    print('人工修正覆盖 SKU 数:', len(MANUAL_FIX))

    if args.dry_run:
        conn.close()
        print('[DRY-RUN] 未写库。')
        return

    # 4) 回填（幂等：重跑覆盖为同一计算值）
    updated = 0
    for sku, fee in updates.items():
        cur.execute(
            "UPDATE wm_ads_product_record SET delivery_fee=%s "
            "WHERE sku=%s AND current_version=1",
            (fee, sku))
        updated += cur.rowcount
    conn.commit()
    conn.close()
    print('已写入 delivery_fee 的 product_record 行数:', updated)
    print('涉及 SKU 数:', len(updates))


if __name__ == '__main__':
    main()
