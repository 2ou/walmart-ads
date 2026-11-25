package com.ai.walmartads.walmartlisting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 领星 MCP {@code wfs_stock_v2_list}（查询WFS库存列表-v2）返回的一条 SKU 库存明细。
 * 数值字段在源 JSON 中可能为字符串或数字（部分含 "Over 50" 文案），统一按可空接收并在 Service 层解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WalmartWfsStockDto {

    @JsonProperty("msku") public String msku;
    @JsonProperty("sku") public String sku;
    @JsonProperty("productName") public String productName;
    @JsonProperty("itemId") public String itemId;
    @JsonProperty("gtin") public String gtin;
    @JsonProperty("storeId") public String storeId;

    @JsonProperty("availableQuantity") public String availableQuantity;
    @JsonProperty("availableQuantityV2") public String availableQuantityV2;
    @JsonProperty("onhandQuantity") public String onhandQuantity;
    @JsonProperty("inboundQuantity") public String inboundQuantity;
    @JsonProperty("reservedQuantity") public String reservedQuantity;
    @JsonProperty("unabledWarehousingQuantity") public String unabledWarehousingQuantity;
    @JsonProperty("inventoryReviewQuantity") public String inventoryReviewQuantity;
    @JsonProperty("damagedQuantity") public String damagedQuantity;

    @JsonProperty("ats03Months") public String ats03Months;
    @JsonProperty("ats36Months") public String ats36Months;
    @JsonProperty("ats69Months") public String ats69Months;
    @JsonProperty("ats912Months") public String ats912Months;
    @JsonProperty("ats1Years") public String ats1Years;

    @JsonProperty("surplusUnits") public String surplusUnits;
    @JsonProperty("suggestedUnits") public String suggestedUnits;
    @JsonProperty("daysOfSupply") public String daysOfSupply;
    @JsonProperty("sellThroughRate") public String sellThroughRate;
    @JsonProperty("outOfStockDate") public String outOfStockDate;
    @JsonProperty("firstInStockDate") public String firstInStockDate;
    @JsonProperty("itemLifecycle") public String itemLifecycle;
    @JsonProperty("stockStatus") public String stockStatus;
    @JsonProperty("customerFavorite") public Boolean customerFavorite;

    @JsonProperty("salesForecastWeek1to4") public String salesForecastWeek1to4;
    @JsonProperty("salesForecastWeek5to8") public String salesForecastWeek5to8;
    @JsonProperty("salesForecastWeek9to12") public String salesForecastWeek9to12;

    @JsonProperty("purchasePrice") public String purchasePrice;
}
