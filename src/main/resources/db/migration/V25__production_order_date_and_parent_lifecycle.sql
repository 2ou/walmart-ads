alter table wm_ads_production_order
    add column order_date date null after order_no;

create table wm_ads_parent_lifecycle (
    parent_sku varchar(180) not null,
    replenishment_mode varchar(20) not null default 'NORMAL',
    updated_at datetime(6) not null,
    constraint pk_wm_ads_parent_lifecycle primary key (parent_sku),
    index idx_wm_ads_parent_lifecycle_mode (replenishment_mode, updated_at)
) engine=InnoDB default charset=utf8mb4;
