package com.ai.walmartads.walmartlisting;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 领星 MCP {@code platform_listing_walmart_list} 返回的一条沃尔玛在线商品。
 * 数值字段在源 JSON 里多数为字符串（如 price="12.98"、review_count="0"），部分为整数
 * （status/fulfillment_type/wfs_available_quantity），统一按可空处理，解析在 Service 层完成。
 */
public class WalmartListingDto {

    @JsonProperty("msku") public String sku;
    @JsonProperty("local_name") public String parentSku;
    @JsonProperty("item_id") public String itemId;
    /** 商品标题（营销长标题）——SKU 映射表 {@code item_name} 的来源。 */
    @JsonProperty("title") public String title;
    @JsonProperty("price") public String price;
    @JsonProperty("comparison_price") public String strikethroughPrice;
    @JsonProperty("buy_box_price") public String buyBoxPrice;
    @JsonProperty("review_count") public String reviewCount;
    @JsonProperty("average_rating") public String averageRating;
    @JsonProperty("competitor_price") public String competitorPrice;
    @JsonProperty("available_quantity") public Integer nonWfsAvailable;
    @JsonProperty("wfs_available_quantity") public Integer wfsAvailable;
    @JsonProperty("fulfillment_type") public Integer fulfillmentType;
    @JsonProperty("fulfillment_type_name") public String fulfillmentTypeName;
    @JsonProperty("status") public Integer status;
    @JsonProperty("upc") public String upc;
    @JsonProperty("gtin") public String gtin;
    @JsonProperty("store_name") public String store;
    @JsonProperty("store_id") public String storeId;
    @JsonProperty("listing_start_time") public String listingStartTime;
    @JsonProperty("offer_end_date") public String offerEndDate;
    @JsonProperty("item_url") public String itemUrl;
    @JsonProperty("promotion_data_list") public List<Object> promotionDataList;
}
