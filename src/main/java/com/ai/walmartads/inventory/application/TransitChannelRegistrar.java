package com.ai.walmartads.inventory.application;

import com.ai.walmartads.supplychain.SupplyChainSyncedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 供应链快照整批替换后，把物流信息登记里出现的渠道补登进「渠道时效维护」。
 *
 * <p>物流信息登记从 Excel 改为插件同步后，新渠道不再经过导入流程被发现；
 * 没有这一步，新渠道的货件会一直停在 NEEDS_MANUAL，预计到仓也算不出来。
 * 事件在同步线程的工作区上下文里同步派发，因此这里可以直接用工作区作用域的仓储。
 */
@Component
public class TransitChannelRegistrar {

    private static final Logger log = LoggerFactory.getLogger(TransitChannelRegistrar.class);

    private final TransitInventoryService transitInventoryService;
    private final ReplenishmentPlanCache replenishmentPlanCache;

    public TransitChannelRegistrar(
            TransitInventoryService transitInventoryService,
            ReplenishmentPlanCache replenishmentPlanCache
    ) {
        this.transitInventoryService = transitInventoryService;
        this.replenishmentPlanCache = replenishmentPlanCache;
    }

    @EventListener
    public void onSupplyChainSynced(SupplyChainSyncedEvent event) {
        try {
            transitInventoryService.syncChannelsForManualMaintenance();
        } catch (Exception exception) {
            // 补登失败不该让整轮同步变红：快照已落库，下一轮同步会再试一次。
            log.warn("供应链同步后补登渠道时效失败（下一轮同步会重试）：{}", exception.getMessage());
        }
        try {
            // 货件/物流不再走 import_file 落账，补货计划缓存只能由同步完成事件直接失效。
            replenishmentPlanCache.invalidate();
        } catch (Exception exception) {
            log.warn("供应链同步后失效补货计划缓存失败：{}", exception.getMessage());
        }
    }
}
