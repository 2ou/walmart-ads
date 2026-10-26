package com.ai.walmartads.supplychain;

/**
 * 供应链快照整批替换成功后发布的事件。
 *
 * <p>库存域用它派生「渠道时效维护」清单：物流信息登记改由插件同步后，
 * 老表不再更新，新渠道也就不会经由导入流程被发现，只能靠这个事件补登记。
 * 事件在当前工作区上下文中同步派发，监听方可以直接使用工作区作用域的仓储。
 */
public record SupplyChainSyncedEvent(int logisticsCount) {
}
