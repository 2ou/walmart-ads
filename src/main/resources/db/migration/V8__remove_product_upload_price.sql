UPDATE wm_ads_product_record
SET raw_payload = JSON_REMOVE(raw_payload, '$.price')
WHERE JSON_CONTAINS_PATH(raw_payload, 'one', '$.price') = 1;

ALTER TABLE wm_ads_product_record
    DROP COLUMN price;
