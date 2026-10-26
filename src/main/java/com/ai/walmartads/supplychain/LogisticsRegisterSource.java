package com.ai.walmartads.supplychain;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 物流信息登记快照读口 —— 路途库存的「货代 / 渠道」唯一来源。
 *
 * <p>2026-10-19 起物流信息登记不再读 Excel（{@code source/货件/物流信息登记_*.xlsx}），
 * 改由项目内置供应链连接器同步落库到 {@code wm_ads_supply_logistics_register}。
 * 老表 {@code wm_ads_shipment_logistics} 只作历史归档，不再写入也不再被库存域读取。
 *
 * <p>状态白名单与 {@link SupplyChainClient} 的拉取过滤保持一致：
 * 只认「待确认(1) / 已完成(2)」，其余状态（作废、已取消等）既不参与货件匹配，
 * 也不派生渠道清单。状态缺失时按保留处理，避免上游字段调整导致整批数据失效。
 */
@Service
public class LogisticsRegisterSource {

    private static final Set<String> KEPT_STATUSES = Set.of("1", "2", "待确认", "已完成");

    private final NamedParameterJdbcTemplate jdbc;

    public LogisticsRegisterSource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LogisticsSnapshot snapshot() {
        long workspaceId = WorkspaceContext.requireId();
        List<LogisticsRegisterInfo> rows = jdbc.query("""
                select fba_number, channel_name, freight_forwarding_name,
                       transportation_date, signing_date, shipment_status
                  from wm_ads_supply_logistics_register
                 where workspace_id=:workspaceId and active = 1
                 order by transportation_date desc, signing_date desc
                """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, rowNum) -> new LogisticsRegisterInfo(
                rs.getString("fba_number"),
                trimToNull(rs.getString("channel_name")),
                trimToNull(rs.getString("freight_forwarding_name")),
                rs.getObject("transportation_date", LocalDate.class),
                rs.getObject("signing_date", LocalDate.class),
                rs.getString("shipment_status")
        ));

        Map<String, LogisticsRegisterInfo> byShipmentNo = new LinkedHashMap<>();
        Set<String> channelNames = new LinkedHashSet<>();
        for (LogisticsRegisterInfo row : rows) {
            if (!kept(row.shipmentStatus())) {
                continue;
            }
            if (row.channelName() != null) {
                channelNames.add(row.channelName());
            }
            String shipmentNo = normalize(row.shipmentNo());
            if (shipmentNo.isEmpty()) {
                continue;
            }
            // 同一货件可能有多条登记（分批 / 重登记），取最新一条（SQL 已按时间倒序）。
            byShipmentNo.putIfAbsent(shipmentNo, row);
        }
        return new LogisticsSnapshot(Map.copyOf(byShipmentNo), Set.copyOf(channelNames), rows.size());
    }

    private static boolean kept(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return KEPT_STATUSES.contains(status.trim());
    }

    /** 货件号归一：去空格 + 转大写，货件号在不同上游大小写不一致。 */
    public static String normalize(String shipmentNo) {
        return shipmentNo == null ? "" : shipmentNo.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record LogisticsRegisterInfo(
            String shipmentNo,
            String channelName,
            String forwarder,
            LocalDate departureDate,
            LocalDate signedDate,
            String shipmentStatus
    ) {
    }

    public record LogisticsSnapshot(
            Map<String, LogisticsRegisterInfo> byShipmentNo,
            Set<String> channelNames,
            int rawRowCount
    ) {
        public boolean isEmpty() {
            return byShipmentNo.isEmpty();
        }
    }
}
