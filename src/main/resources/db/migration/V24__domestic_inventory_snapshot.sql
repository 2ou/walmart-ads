create table wm_ads_domestic_inventory_snapshot (
    id bigint not null auto_increment,
    import_file_id bigint not null,
    snapshot_at datetime(6) not null,
    sku varchar(180) not null,
    available_units decimal(18, 4) not null,
    raw_payload json not null,
    created_at datetime(6) not null,
    constraint pk_wm_ads_domestic_inventory_snapshot primary key (id),
    constraint fk_wm_ads_domestic_inventory_snapshot_file
        foreign key (import_file_id) references wm_ads_import_file (id),
    index idx_wm_ads_domestic_inventory_snapshot_at (snapshot_at),
    index idx_wm_ads_domestic_inventory_snapshot_sku (sku)
) engine=InnoDB default charset=utf8mb4;
