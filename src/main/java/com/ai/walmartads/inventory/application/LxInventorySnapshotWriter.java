package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.domain.LxInventorySnapshot;
import com.ai.walmartads.inventory.repository.LxInventorySnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 领星库存快照写入器（V49）。
 *
 * <p>按业务日整窗替换：先清空该 {@code businessDate} 的旧行再写入本次快照，实现
 * 「每小时同步落库、每天只保留最后一版」。独立事务（REQUIRES_NEW），
 * 使库存快照写入失败不影响调用方（沃尔玛在线商品同步）的主流程。
 */
@Service
public class LxInventorySnapshotWriter {

    private static final Logger log = LoggerFactory.getLogger(LxInventorySnapshotWriter.class);

    private final LxInventorySnapshotRepository repository;
    private final ReplenishmentPlanCache replenishmentPlanCache;

    public LxInventorySnapshotWriter(
            LxInventorySnapshotRepository repository,
            ReplenishmentPlanCache replenishmentPlanCache
    ) {
        this.repository = repository;
        this.replenishmentPlanCache = replenishmentPlanCache;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int replaceDay(LocalDate businessDate, LocalDateTime snapshotAt, List<LxInventorySnapshot> rows) {
        int removed = repository.deleteByBusinessDate(businessDate);
        if (!rows.isEmpty()) {
            repository.saveAll(rows);
        }
        invalidateAfterCommit();
        log.info("领星库存快照整窗替换：businessDate={}, snapshotAt={}, removed={}, inserted={}",
                businessDate, snapshotAt, removed, rows.size());
        return rows.size();
    }

    /**
     * 库存不再经 {@code import_file} 落账，必须由写入方直接让补货计划缓存失效，否则页面会一直沿用旧快照。
     * 失效动作挂到事务提交之后，避免并发读在提交前用旧数据把缓存重新填满。
     */
    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    replenishmentPlanCache.invalidate();
                }
            });
            return;
        }
        replenishmentPlanCache.invalidate();
    }
}
