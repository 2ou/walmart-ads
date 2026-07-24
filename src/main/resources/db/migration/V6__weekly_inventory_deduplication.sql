ALTER TABLE wm_ads_import_file
    DROP INDEX uk_import_file_hash_type,
    ADD CONSTRAINT uk_import_file_hash_type_date
        UNIQUE (sha256, file_type, report_date);
