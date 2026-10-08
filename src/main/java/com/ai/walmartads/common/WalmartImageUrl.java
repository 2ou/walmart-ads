package com.ai.walmartads.common;

/**
 * 沃尔玛商品图 URL 工具。
 *
 * 产品库 / 销量 / 库存 / 退货等列表缩略图原本直接引用沃尔玛 CDN 原图
 * (i5.walmartimages.com/asr/...)，体积大、同域并发受限，导致几百张缩略图加载极慢。
 * 这里在出参层统一追加沃尔玛官方 ODN 缩图参数 (?odnWidth=&odnHeight=&odnBg=FFFFFF)，
 * 把单张图从原图压到数百 KB 以内，列表加载速度数量级提升。
 *
 * 仅对 walmartimages.com 域名生效；非沃尔玛域名（如本地占位 data:URI）原样返回不加参数。
 * 若该参数被 CDN 忽略，只会返回原图（不会 404），因此不会造成图裂。
 * 前端图片统一走沃尔玛 CDN；加载失败由前端统一回落到友好占位图（不再有本地 /img/styles 兜底）。
 */
public final class WalmartImageUrl {

    private WalmartImageUrl() {
    }

    /** 列表缩略图默认尺寸（200x200，适配 ~60px 显示 + 2x 视网膜）。 */
    public static String thumb(String url) {
        return thumbnail(url, 200, 200);
    }

    public static String thumbnail(String url, int width, int height) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (!url.contains("walmartimages.com")) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "odnWidth=" + width + "&odnHeight=" + height + "&odnBg=FFFFFF";
    }
}
