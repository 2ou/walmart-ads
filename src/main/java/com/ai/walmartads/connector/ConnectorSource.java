package com.ai.walmartads.connector;

public enum ConnectorSource {
    LINGXING_INVENTORY(ConnectorSystem.LINGXING, "领星国内仓库存"),
    LINGXING_WFS(ConnectorSystem.LINGXING, "领星 WFS 货件"),
    SUPPLY_CHAIN(ConnectorSystem.SUPPLY_CHAIN, "供应链生产与物流");

    private final ConnectorSystem system;
    private final String label;

    ConnectorSource(ConnectorSystem system, String label) {
        this.system = system;
        this.label = label;
    }

    public ConnectorSystem system() {
        return system;
    }

    public String label() {
        return label;
    }
}
