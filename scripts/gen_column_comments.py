#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate V14__add_column_comments.sql from information_schema + a curated comment map.

Reads live column definitions (type/nullable/default/auto_increment) straight from the
database so we never change a column's type by accident (which would break JPA validate).
Only adds/updates COLUMN COMMENT.
"""
import os
import re
import subprocess

DB = "walmart_ads"
USER = "root"
PASSWORD = "root"
HOST = "127.0.0.1"
PORT = "3306"

# Curated Chinese comments: (table, column) -> comment text
COMMENTS = {
    "wm_ads_ad_performance": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID（wm_ads_import_file.id）",
        "report_date": "报表日期",
        "ad_type": "广告类型（如 SP/SD 等）",
        "campaign_name": "广告活动名称",
        "campaign_id": "广告活动ID",
        "campaign_type": "广告活动类型",
        "bidding_strategy": "出价策略",
        "target_roas": "目标 ROAS（广告支出回报率）",
        "ad_group_name": "广告组名称",
        "ad_group_id": "广告组ID",
        "item_id": "商品ID（Walmart item id）",
        "sku": "商品 SKU",
        "parent_sku": "父 SKU",
        "item_name": "商品名称",
        "searched_keyword": "搜索关键词",
        "bidded_keyword": "出价关键词",
        "match_type": "匹配类型（精准/短语/广泛）",
        "bid": "出价",
        "average_cpc": "平均单次点击成本（CPC）",
        "impressions": "展示次数",
        "clicks": "点击次数",
        "ctr": "点击率（点击/展示）",
        "ad_spend": "广告花费",
        "orders": "订单数",
        "conversion_rate": "转化率（订单/点击）",
        "attributed_sales": "归因销售额",
        "advertised_sku_sales": "推广 SKU 销售额",
        "other_sku_sales": "其他 SKU 销售额",
        "units_sold": "销售件数",
        "roas": "广告投资回报率（销售额/花费）",
        "omni_roas": "全渠道 ROAS",
        "raw_payload": "原始报文（JSON，保留平台原始数据）",
        "created_at": "记录创建时间",
    },
    "wm_ads_ai_analysis": {
        "id": "主键，自增",
        "data_fingerprint": "数据指纹（sha256），用于去重",
        "provider": "AI 服务商",
        "model_name": "模型名称",
        "request_payload": "请求报文（JSON）",
        "response_payload": "响应报文（JSON，可空）",
        "status": "状态（如 pending/success/failed）",
        "created_at": "创建时间",
        "completed_at": "完成时间（可空）",
    },
    "wm_ads_import_batch": {
        "id": "主键，自增",
        "started_at": "批次开始时间",
        "completed_at": "批次完成时间（可空）",
        "status": "批次状态",
        "source_root": "数据源根目录",
        "archive_directory": "归档目录（可空）",
        "discovered_file_count": "发现文件数",
        "imported_file_count": "成功导入文件数",
        "skipped_file_count": "跳过文件数",
        "failed_file_count": "失败文件数",
        "message": "备注/消息（可空）",
    },
    "wm_ads_import_file": {
        "id": "主键，自增",
        "batch_id": "关联导入批次ID",
        "file_type": "文件类型（决定用哪个解析器）",
        "report_kind": "报表种类（细化区分，如广告的不同报表）",
        "file_name": "文件名",
        "original_path": "原始路径",
        "archive_path": "归档路径（可空）",
        "sha256": "文件 SHA256 校验值（用于去重）",
        "size_bytes": "文件大小（字节）",
        "source_modified_at": "源文件修改时间",
        "report_date": "报表日期（可空）",
        "snapshot_at": "快照时间（可空）",
        "status": "文件处理状态",
        "active": "是否启用（1启用/0停用）",
        "source_row_count": "源文件行数",
        "imported_row_count": "已导入行数",
        "error_message": "错误信息（可空）",
        "created_at": "创建时间",
    },
    "wm_ads_inventory_snapshot": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID",
        "snapshot_at": "快照时间",
        "sku": "商品 SKU",
        "parent_sku": "父 SKU（可空）",
        "item_id": "商品ID（可空）",
        "gtin": "GTIN（可空）",
        "item_name": "商品名称（可空）",
        "status": "状态（可空）",
        "daily_sales": "日均销售额",
        "daily_units_sold": "日均销量（件）",
        "available_units": "可用库存（件）",
        "reserved_units": "预留库存（件）",
        "inbound_units": "在途库存（件）",
        "forecasted_days_supply": "预计可售天数",
        "out_of_stock_date": "预计缺货日期（可空）",
        "suggested_units": "建议补货量（件）",
        "brand": "品牌（可空）",
        "surplus_units": "冗余库存（件）",
        "sell_through_rate": "售罄率",
        "publishing_status": "上架状态（可空）",
        "item_lifecycle": "商品生命周期阶段（可空）",
        "raw_payload": "原始报文（JSON）",
        "created_at": "创建时间",
    },
    "wm_ads_inventory_supply_plan": {
        "id": "主键，自增",
        "sku": "商品 SKU",
        "production_units": "生产/补货数量（件）",
        "note": "备注（可空）",
        "created_at": "创建时间",
    },
    "wm_ads_item_report_record": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID",
        "source_updated_at": "源数据更新时间",
        "current_version": "是否当前版本（1是/0否）",
        "sku": "商品 SKU",
        "parent_sku": "父 SKU（可空）",
        "item_id": "商品ID（可空）",
        "upc": "UPC（可空）",
        "wpid": "WPID（可空）",
        "gtin": "GTIN（可空）",
        "product_name": "商品名称（可空）",
        "brand": "品牌（可空）",
        "product_category": "商品类目（可空）",
        "lifecycle_status": "生命周期状态（可空）",
        "fulfillment_type": "履约类型（可空）",
        "price": "商品价格",
        "currency": "币种（可空）",
        "price_type": "价格类型（可空）",
        "shipping_weight_lb": "物流重量（磅）",
        "shipping_weight_unit": "物流重量单位（可空）",
        "shipping_weight_kg": "物流重量（千克）",
        "billing_weight_lb": "计费重量（磅）",
        "base_fee": "基础配送费",
        "apparel_surcharge": "服装附加费",
        "low_price_surcharge": "低价附加费",
        "delivery_fee": "配送费（合计）",
        "raw_payload": "原始报文（JSON）",
        "created_at": "创建时间",
    },
    "wm_ads_operation_record": {
        "id": "主键，自增",
        "target_type": "操作对象类型",
        "target_key": "操作对象标识",
        "parent_sku": "父 SKU（可空）",
        "target_name": "操作对象名称（可空）",
        "action_type": "操作类型",
        "source_type": "操作来源类型",
        "status": "状态",
        "occurred_at": "操作发生时间",
        "title": "标题",
        "before_value": "变更前值（可空）",
        "after_value": "变更后值（可空）",
        "note": "备注（可空）",
        "external_reference": "外部引用（可空）",
        "created_at": "创建时间",
        "updated_at": "更新时间",
    },
    "wm_ads_order_record": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID",
        "report_date": "报表日期",
        "order_date": "订单日期",
        "po_number": "采购单号（可空）",
        "order_number": "订单号",
        "line_number": "行号（可空）",
        "sku": "商品 SKU",
        "parent_sku": "父 SKU（可空）",
        "upc": "UPC（可空）",
        "status": "状态（可空）",
        "quantity": "数量",
        "item_cost": "商品成本",
        "original_price": "原价",
        "shipping_cost": "运费",
        "item_description": "商品描述（可空）",
        "shipping_method": "配送方式（可空）",
        "fulfillment_type": "履约类型（可空）",
        "raw_payload": "原始报文（JSON）",
        "created_at": "创建时间",
    },
    "wm_ads_product_record": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID",
        "source_updated_at": "源数据更新时间",
        "current_version": "是否当前版本（1是/0否）",
        "sku": "商品 SKU",
        "parent_sku": "父 SKU（可空）",
        "product_type": "商品类型（可空）",
        "product_id": "商品ID（可空）",
        "product_id_type": "商品ID类型（可空）",
        "product_name": "商品名称（可空）",
        "brand": "品牌（可空）",
        "short_description": "简短描述（可空）",
        "key_features": "关键卖点（JSON，可空）",
        "main_image_url": "主图URL（可空）",
        "secondary_image_urls": "副图URL列表（JSON，可空）",
        "color": "颜色（可空）",
        "color_category": "颜色类目（可空）",
        "size": "尺码（可空）",
        "size_group": "尺码组（可空）",
        "pattern_name": "图案名称（可空）",
        "material_name": "材质名称（可空）",
        "gender": "适用人群/性别（可空）",
        "variant_group_id": "变体组ID（可空）",
        "cutting_yield": "裁床数：一条颜色的布能裁多少件，用于做货数量下单参考（可空）",
        "raw_payload": "原始报文（JSON）",
        "created_at": "创建时间",
    },
    "wm_ads_profit_assumption": {
        "parent_sku": "父 SKU（主键）",
        "purchase_cost": "采购成本（¥）",
        "first_leg": "头程费用（¥）",
        "sales_qty": "销售数量（件）",
        "ad_cost": "广告费用（原币，默认1）",
        "refund_rate": "退款率（可空，0~1）",
        "created_at": "创建时间",
        "updated_at": "更新时间",
    },
    "wm_ads_return_order": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID",
        "source_row_number": "源数据行号",
        "business_key": "业务唯一键（sha256，用于去重）",
        "report_month": "报表月份",
        "request_at": "退货申请时间",
        "request_date": "退货申请日期",
        "customer_order_no": "客户订单号",
        "po_no": "采购单号（可空）",
        "po_line_no": "采购单行号（可空）",
        "rma_no": "RMA 号",
        "return_order_line_no": "退货单行号（可空）",
        "quantity": "数量",
        "return_reason": "退货原因（可空）",
        "item_id": "商品ID",
        "sku": "商品 SKU（可空）",
        "parent_sku": "父 SKU（可空）",
        "condition_name": "商品状态（可空）",
        "current_status": "当前状态（可空）",
        "current_status_time": "当前状态时间（可空）",
        "keep_it": "是否保留（1是/0否）",
        "item_name": "商品名称（可空）",
        "carrier": "承运商（可空）",
        "tracking_number": "物流单号（可空）",
        "channel": "渠道（可空）",
        "customer_name": "客户名称（可空）",
        "customer_email": "客户邮箱（可空）",
        "customer_comments": "客户备注（可空）",
        "order_amount": "订单金额",
        "return_shipping_fee": "退货运费",
        "restocking_fee": "重新上架费",
        "total_refund_amount": "退款总额",
        "seller_order_no": "卖家订单号（可空）",
        "refund_mode": "退款方式（可空）",
        "refund_covered_by": "退款承担方（可空）",
        "tracking_status": "物流状态（可空）",
        "refund_status": "退款状态（可空）",
        "replacement": "是否换货（1是/0否）",
        "platform_type": "平台类型（可空）",
        "return_ship_from": "退货发出地（可空）",
        "return_ship_to": "退货接收地（可空）",
        "raw_payload": "原始报文（JSON）",
        "created_at": "创建时间",
    },
    "wm_ads_rule_result": {
        "id": "主键，自增",
        "analysis_date": "分析日期",
        "period_days": "统计周期天数",
        "target_type": "分析目标类型",
        "target_key": "分析目标标识",
        "action_code": "动作代码",
        "evidence_payload": "证据数据（JSON）",
        "created_at": "创建时间",
    },
    "wm_ads_sku_mapping": {
        "id": "主键，自增",
        "import_file_id": "关联导入文件ID",
        "source_updated_at": "源数据更新时间",
        "current_version": "是否当前版本（1是/0否）",
        "sku": "商品 SKU",
        "parent_sku": "父 SKU",
        "item_id": "商品ID",
        "gtin": "GTIN",
        "upc": "UPC",
        "item_name": "商品名称（可空）",
        "raw_payload": "原始报文（JSON）",
        "created_at": "创建时间",
    },
}

MYSQL = r"D:\Tools\MySQL\MySQL Server 9.0\bin\mysql.exe"


def query_columns():
    sql = (
        "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA "
        "FROM information_schema.columns "
        "WHERE table_schema='%s' AND TABLE_NAME != 'flyway_schema_history' "
        "ORDER BY TABLE_NAME, ORDINAL_POSITION;" % DB
    )
    out = subprocess.check_output(
        [MYSQL, "-h", HOST, "-P", PORT, "-u", USER, "-p" + PASSWORD,
         "--default-character-set=utf8mb4", "-N", "-ss", "-e", sql],
        stderr=subprocess.STDOUT,
    ).decode("utf-8", "replace")
    rows = []
    for line in out.replace("\r\n", "\n").replace("\r", "\n").splitlines():
        line = line.strip()
        if not line or not line.startswith("wm_ads_"):
            continue
        parts = line.split("\t")
        while len(parts) < 6:
            parts.append("")
        table, column, ctype, nullable, default, extra = parts[:6]
        rows.append({
            "table": table, "column": column, "type": ctype,
            "nullable": nullable, "default": default, "extra": extra,
        })
    return rows


def esc(value):
    return value.replace("\\", "\\\\").replace("'", "\\'")


def build_statements(rows):
    by_table = {}
    for r in rows:
        by_table.setdefault(r["table"], []).append(r)
    blocks = []
    for table in sorted(by_table.keys()):
        columns = by_table[table]
        mods = []
        for c in columns:
            comment = COMMENTS.get(table, {}).get(c["column"])
            if not comment:
                comment = c["column"]  # fallback: column name
            pieces = [f"`{c['column']}`", c["type"]]
            if "auto_increment" in c["extra"]:
                pieces.append("AUTO_INCREMENT")
            if c["nullable"] == "NO":
                pieces.append("NOT NULL")
            else:
                pieces.append("NULL")
            default = c["default"]
            if default not in (None, "NULL", "") and "auto_increment" not in c["extra"]:
                dv = default.strip()
                if dv.upper() in ("CURRENT_TIMESTAMP",):
                    pieces.append(f"DEFAULT {dv}")
                elif re.fullmatch(r"-?\d+(\.\d+)?", dv):
                    # Normalize to a clean numeric literal (e.g. 1.000000 -> 1) to avoid
                    # "Invalid default value" truncation errors on DECIMAL columns.
                    pieces.append("DEFAULT %s" % repr(float(dv) if "." in dv else int(dv)))
                else:
                    pieces.append(f"DEFAULT '{esc(dv)}'")
            pieces.append(f"COMMENT '{esc(comment)}'")
            mods.append("    MODIFY COLUMN " + " ".join(pieces))
        stmt = "ALTER TABLE `%s`\n%s;" % (table, ",\n".join(mods))
        blocks.append(stmt)
    return blocks


def main():
    rows = query_columns()
    blocks = build_statements(rows)
    header = (
        "-- 为全部业务表的所有字段补充中文注释（仅修改 COLUMN COMMENT，类型/默认值/NOT NULL/自增均保持原样）\n"
        "SET FOREIGN_KEY_CHECKS=0;\n"
    )
    footer = "\nSET FOREIGN_KEY_CHECKS=1;\n"
    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "src", "main", "resources", "db", "migration")
    out_path = os.path.join(out_dir, "V14__add_column_comments.sql")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(header)
        f.write("\n\n".join(blocks))
        f.write(footer)
    print("Wrote", out_path, "with", len(blocks), "tables.")


if __name__ == "__main__":
    main()
