-- 清理 ItemReport：其价格/配送费来源已由「沃尔玛商品」扫描（回写产品库）替代。
-- 先删除数据表（含指向 wm_ads_import_file 的外键），再清理对应的导入文件记录。
DROP TABLE IF EXISTS wm_ads_item_report_record;
DELETE FROM wm_ads_import_file WHERE file_type = 'ITEM_REPORT';
