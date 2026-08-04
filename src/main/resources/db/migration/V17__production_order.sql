create table wm_ads_production_order (
    id bigint not null auto_increment,
    import_file_id bigint not null,
    current_version bit not null default 1,
    sku varchar(180) not null,
    style_no varchar(120),
    order_no varchar(120),
    model varchar(60),
    open_qty decimal(18, 4),
    cutting_qty decimal(18, 4),
    production_qty decimal(18, 4),
    ship_qty decimal(18, 4),
    warehouse_receive_qty decimal(18, 4),
    qc_pass_qty decimal(18, 4),
    unfinished_qty decimal(18, 4),
    factory_return_date varchar(60),
    store varchar(120),
    order_type varchar(120),
    raw_payload json,
    created_at datetime(6) not null,
    constraint pk_wm_ads_production_order primary key (id),
    constraint fk_wm_ads_production_order_file foreign key (import_file_id) references wm_ads_import_file (id)
) engine = InnoDB default charset = utf8mb4;

create index idx_wm_ads_production_order_sku on wm_ads_production_order (sku);
create index idx_wm_ads_production_order_version on wm_ads_production_order (current_version);
