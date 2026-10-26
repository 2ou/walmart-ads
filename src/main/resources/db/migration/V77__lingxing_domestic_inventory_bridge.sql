create table wm_ads_lx_domestic_inventory (
    id bigint not null auto_increment,
    workspace_id bigint not null,
    sku varchar(180) not null,
    available_units decimal(18, 4) not null,
    warehouse_name varchar(255) null,
    store_name varchar(255) null,
    source_url varchar(500) not null,
    raw_payload json not null,
    synced_at datetime(6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint pk_wm_ads_lx_domestic_inventory primary key (id),
    constraint uq_lx_domestic_inventory_workspace_sku unique (workspace_id, sku),
    index idx_lx_domestic_inventory_workspace_synced (workspace_id, synced_at),
    constraint fk_lx_domestic_inventory_workspace
        foreign key (workspace_id) references wm_ads_workspace (id)
) engine=InnoDB default charset=utf8mb4;

create table wm_ads_lx_domestic_inventory_sync_log (
    id bigint not null auto_increment,
    workspace_id bigint not null,
    started_at datetime(6) not null,
    finished_at datetime(6) null,
    status varchar(20) not null,
    source_row_count int not null default 0,
    imported_sku_count int not null default 0,
    skipped_row_count int not null default 0,
    available_units decimal(18, 4) not null default 0,
    source_url varchar(500) null,
    message varchar(1000) null,
    constraint pk_wm_ads_lx_domestic_inventory_sync_log primary key (id),
    index idx_lx_domestic_inventory_sync_workspace_finished (workspace_id, finished_at),
    constraint fk_lx_domestic_inventory_sync_workspace
        foreign key (workspace_id) references wm_ads_workspace (id)
) engine=InnoDB default charset=utf8mb4;
