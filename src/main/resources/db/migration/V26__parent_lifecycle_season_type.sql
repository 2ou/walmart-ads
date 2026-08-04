alter table wm_ads_parent_lifecycle
    add column season_type varchar(20) not null default 'SEASONAL' after replenishment_mode;

create index idx_wm_ads_parent_lifecycle_season_type
    on wm_ads_parent_lifecycle (season_type, updated_at);
