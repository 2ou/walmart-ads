package com.ai.walmartads.connector;

public enum ConnectorSystem {
    LINGXING("领星", "https://czyx.lingxing.com/erp/msupply/warehouseDetail"),
    SUPPLY_CHAIN("供应链", "https://czyxgyl.shangyiku.top/produce/deliverGoods");

    private final String label;
    private final String loginUrl;

    ConnectorSystem(String label, String loginUrl) {
        this.label = label;
        this.loginUrl = loginUrl;
    }

    public String label() {
        return label;
    }

    public String loginUrl() {
        return loginUrl;
    }
}
