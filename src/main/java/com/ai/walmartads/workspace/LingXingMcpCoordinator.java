package com.ai.walmartads.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Database-backed request coordinator shared by every LingXing business category and app instance.
 * It enforces the official QPS=1 rule with a conservative two-second interval and persists 429
 * cooldowns so neither a restart nor another category can bypass the server-provided Retry-After.
 */
@Service
public class LingXingMcpCoordinator {

    static final long DEFAULT_INTERVAL_MS = 2_000L;
    private static final long MIN_INTERVAL_MS = 1_000L;
    private static final long MAX_INTERVAL_MS = 10_000L;
    private static final Duration FALLBACK_COOLDOWN = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final long intervalMs;

    public LingXingMcpCoordinator(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.intervalMs = resolveIntervalMs();
    }

    /** Reserve one durable global request slot and wait for it outside the database transaction. */
    public void awaitPermit(String baseUrl, String apiKey) {
        awaitPermit(baseUrl, apiKey, null);
    }

    /**
     * 预约一个全账号共享的请求时槽，同时识别「本类目」与「全账号」两种冷却。
     *
     * <p>限速槽必须按账号共享 —— 领星的配额是「每用户 1 分钟 100 次」，各类目各自排队
     * 会叠乘上去。但冷却要分两层看：
     * <ul>
     *   <li>账号级：服务端明确回 {@code Retry-After}，说明整个 key 被封，所有类目都得等；</li>
     *   <li>类目级：只是本地兜底冷却（一次没有 Retry-After 的 429），只停这一个类目，
     *       不要把商品、订单、库存一起拖下水。</li>
     * </ul>
     */
    public void awaitPermit(String baseUrl, String apiKey, String category) {
        Scope account = scope(baseUrl, apiKey);
        Scope scoped = scope(baseUrl, apiKey, category);
        Long waitMs = transactions.execute(status -> reserve(account, scoped));
        sleep(waitMs == null ? 0L : waitMs);
    }

    private long reserve(Scope account, Scope scoped) {
        ensureRow(account);
        if (!scoped.hash().equals(account.hash())) {
            ensureRow(scoped);
        }
        RuntimeRow row = jdbc.query("""
                select limited_until,next_request_at
                  from wm_ads_mcp_runtime where scope_hash=? for update
                """, (rs, rowNum) -> new RuntimeRow(
                rs.getObject("limited_until", LocalDateTime.class),
                rs.getObject("next_request_at", LocalDateTime.class)), account.hash()).getFirst();
        LocalDateTime now = LocalDateTime.now();
        if (row.limitedUntil() != null && row.limitedUntil().isAfter(now)) {
            long minutes = Math.max(1L, Duration.between(now, row.limitedUntil()).plusSeconds(59).toMinutes());
            throw new CoolingDownException("领星 MCP 全账号限流冷却中，约 " + minutes + " 分钟后自动重试",
                    row.limitedUntil());
        }
        if (!scoped.hash().equals(account.hash())) {
            LocalDateTime scopedUntil = jdbc.query("""
                    select limited_until from wm_ads_mcp_runtime where scope_hash=?
                    """, rs -> rs.next() ? rs.getObject("limited_until", LocalDateTime.class) : null,
                    scoped.hash());
            if (scopedUntil != null && scopedUntil.isAfter(now)) {
                long minutes = Math.max(1L, Duration.between(now, scopedUntil).plusSeconds(59).toMinutes());
                throw new CoolingDownException("领星 MCP 该类目限流冷却中，约 " + minutes + " 分钟后自动重试",
                        scopedUntil);
            }
        }
        LocalDateTime slot = row.nextRequestAt() != null && row.nextRequestAt().isAfter(now)
                ? row.nextRequestAt() : now;
        LocalDateTime next = slot.plusNanos(intervalMs * 1_000_000L);
        jdbc.update("""
                update wm_ads_mcp_runtime
                   set next_request_at=?, last_request_at=?,
                       limited_until=if(limited_until <= now(6),null,limited_until),
                       consecutive_limit_count=if(limited_until <= now(6),0,consecutive_limit_count)
                 where scope_hash=?
                """, next, slot, account.hash());
        return Math.max(0L, Duration.between(now, slot).toMillis());
    }

    private void ensureRow(Scope scope) {
        jdbc.update("""
                insert into wm_ads_mcp_runtime (scope_hash,key_fingerprint)
                values (?,?) on duplicate key update key_fingerprint=values(key_fingerprint)
                """, scope.hash(), scope.fingerprint());
    }

    public LocalDateTime recordRateLimit(
            String baseUrl, String apiKey, Long retryAfterSeconds, String message
    ) {
        return recordRateLimit(baseUrl, apiKey, null, retryAfterSeconds, message);
    }

    /**
     * 记录一次限流冷却。
     *
     * @param category 传 {@code null} 表示账号级封禁（服务端给了 Retry-After，整个 key 都废），
     *                 所有类目一起等；传具体类目则只冷却该类目，其他类目继续跑。
     */
    public LocalDateTime recordRateLimit(
            String baseUrl, String apiKey, String category, Long retryAfterSeconds, String message
    ) {
        Scope scope = scope(baseUrl, apiKey, category);
        long seconds = retryAfterSeconds != null && retryAfterSeconds > 0
                ? retryAfterSeconds : FALLBACK_COOLDOWN.toSeconds();
        LocalDateTime until = LocalDateTime.now().plusSeconds(seconds);
        jdbc.update("""
                insert into wm_ads_mcp_runtime (
                    scope_hash,key_fingerprint,limited_until,next_request_at,last_limit_at,
                    last_limit_message,consecutive_limit_count
                ) values (?,?,?, ?,now(6),?,1)
                on duplicate key update
                    key_fingerprint=values(key_fingerprint),
                    limited_until=greatest(coalesce(limited_until,values(limited_until)),values(limited_until)),
                    next_request_at=greatest(coalesce(next_request_at,values(next_request_at)),values(next_request_at)),
                    last_limit_at=now(6),last_limit_message=values(last_limit_message),
                    consecutive_limit_count=consecutive_limit_count+1
                """, scope.hash(), scope.fingerprint(), until, until, truncate(message, 1000));
        return until;
    }

    /** 账号级状态 + 该账号下所有类目级冷却合并（初始化跨类目时用）。 */
    public RuntimeStatus status(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new RuntimeStatus(false, null, null, null, 0);
        }
        Scope account = scope(baseUrl, apiKey);
        List<RuntimeStatus> rows = jdbc.query("""
                select limited_until,last_request_at,last_limit_message,consecutive_limit_count
                  from wm_ads_mcp_runtime where key_fingerprint like ?
                """, (rs, rowNum) -> {
            LocalDateTime limitedUntil = rs.getObject("limited_until", LocalDateTime.class);
            return new RuntimeStatus(limitedUntil != null && limitedUntil.isAfter(LocalDateTime.now()),
                    limitedUntil, rs.getObject("last_request_at", LocalDateTime.class),
                    rs.getString("last_limit_message"), rs.getInt("consecutive_limit_count"));
        }, account.fingerprint() + "%");
        return rows.isEmpty() ? new RuntimeStatus(false, null, null, null, 0) : merge(rows);
    }

    /**
     * 该业务类目当前能否发请求：账号级封禁 或 本类目冷却，任一命中都要等。
     *
     * @param category 传 {@code null} 时只看账号级。
     */
    public RuntimeStatus status(String baseUrl, String apiKey, String category) {
        if (apiKey == null || apiKey.isBlank()) {
            return new RuntimeStatus(false, null, null, null, 0);
        }
        Scope account = scope(baseUrl, apiKey);
        Scope scoped = scope(baseUrl, apiKey, category);
        if (scoped.hash().equals(account.hash())) {
            return readStatus(account);
        }
        return merge(List.of(readStatus(account), readStatus(scoped)));
    }

    private RuntimeStatus readStatus(Scope scope) {
        List<RuntimeStatus> rows = jdbc.query("""
                select limited_until,last_request_at,last_limit_message,consecutive_limit_count
                  from wm_ads_mcp_runtime where scope_hash=?
                """, (rs, rowNum) -> {
            LocalDateTime limitedUntil = rs.getObject("limited_until", LocalDateTime.class);
            return new RuntimeStatus(limitedUntil != null && limitedUntil.isAfter(LocalDateTime.now()),
                    limitedUntil, rs.getObject("last_request_at", LocalDateTime.class),
                    rs.getString("last_limit_message"), rs.getInt("consecutive_limit_count"));
        }, scope.hash());
        return rows.isEmpty() ? new RuntimeStatus(false, null, null, null, 0) : rows.getFirst();
    }

    /** 多条运行状态取「最保守」的一条：冷却取最晚截止，时间取最近。 */
    private static RuntimeStatus merge(List<RuntimeStatus> rows) {
        LocalDateTime limitedUntil = null;
        LocalDateTime lastRequestAt = null;
        String message = null;
        int consecutive = 0;
        for (RuntimeStatus row : rows) {
            if (row.limitedUntil() != null && (limitedUntil == null || row.limitedUntil().isAfter(limitedUntil))) {
                limitedUntil = row.limitedUntil();
                message = row.lastMessage();
            }
            if (row.lastRequestAt() != null && (lastRequestAt == null || row.lastRequestAt().isAfter(lastRequestAt))) {
                lastRequestAt = row.lastRequestAt();
            }
            consecutive = Math.max(consecutive, row.consecutiveLimitCount());
        }
        return new RuntimeStatus(limitedUntil != null && limitedUntil.isAfter(LocalDateTime.now()),
                limitedUntil, lastRequestAt, message, consecutive);
    }

    public boolean coolingDown(String baseUrl, String apiKey) {
        return status(baseUrl, apiKey).coolingDown();
    }

    public String fingerprint(String baseUrl, String apiKey) {
        return scope(baseUrl, apiKey).fingerprint();
    }

    static Scope scope(String baseUrl, String apiKey) {
        String hash = sha256(canonical(baseUrl, apiKey));
        return new Scope(hash, "mcp-" + hash.substring(0, 10));
    }

    /**
     * 类目级 scope：同一个 key 下按业务类目各占一行，用于隔离本地兜底冷却。
     * 限速槽位仍只认账号级 scope，不能按类目各排一条队。
     *
     * <p>fingerprint 必须保持「账号前缀 + @ + 类目标签」且不超过 {@code key_fingerprint}
     * 的 varchar(20) —— 账号前缀固定 14 字符（{@code mcp-} + 10 位 hash），所以类目标签
     * 最长只能 5 位，否则 insert 会报 Data too long。标签只用于人工辨识和 like 前缀匹配，
     * 真正的唯一性由 scope_hash 保证。
     */
    static Scope scope(String baseUrl, String apiKey, String category) {
        Scope account = scope(baseUrl, apiKey);
        if (category == null || category.isBlank()) {
            return account;
        }
        String suffix = category.trim().toUpperCase(java.util.Locale.ROOT);
        String hash = sha256(canonical(baseUrl, apiKey) + "|" + suffix);
        return new Scope(hash, account.fingerprint() + "@" + categoryTag(suffix));
    }

    /** 类目标签：总长上限 5，保证 fingerprint 不超过 varchar(20)。 */
    private static String categoryTag(String category) {
        return switch (category) {
            case "PRODUCTS" -> "PROD";
            case "INVENTORY" -> "INV";
            case "SALES" -> "SALES";
            case "ADS" -> "ADS";
            case "REVIEWS" -> "REV";
            case "HISTORY" -> "HIST";
            case "WORKSPACE" -> "WS";
            default -> category.length() > 5 ? category.substring(0, 5) : category;
        };
    }

    private static String canonical(String baseUrl, String apiKey) {
        String normalizedUrl = baseUrl == null ? "" : baseUrl.trim().toLowerCase();
        String secret = apiKey == null ? "" : apiKey.trim();
        return normalizedUrl + "|" + secret;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static long resolveIntervalMs() {
        String raw = System.getProperty("lingxing.rateLimit.intervalMs");
        if (raw == null || raw.isBlank()) raw = System.getenv("LINGXING_RATE_LIMIT_INTERVAL_MS");
        if (raw == null || raw.isBlank()) return DEFAULT_INTERVAL_MS;
        try {
            return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, Long.parseLong(raw.trim())));
        } catch (NumberFormatException ignored) {
            return DEFAULT_INTERVAL_MS;
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("领星 MCP 请求排队被中断", exception);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private record RuntimeRow(LocalDateTime limitedUntil, LocalDateTime nextRequestAt) { }
    record Scope(String hash, String fingerprint) { }

    public record RuntimeStatus(boolean coolingDown, LocalDateTime limitedUntil,
                                LocalDateTime lastRequestAt, String lastMessage,
                                int consecutiveLimitCount) { }

    public static class CoolingDownException extends IllegalStateException {
        private final LocalDateTime limitedUntil;

        public CoolingDownException(String message, LocalDateTime limitedUntil) {
            super(message);
            this.limitedUntil = limitedUntil;
        }

        public LocalDateTime limitedUntil() {
            return limitedUntil;
        }
    }
}
