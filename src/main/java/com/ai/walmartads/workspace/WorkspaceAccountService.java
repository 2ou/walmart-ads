package com.ai.walmartads.workspace;

import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.walmartlisting.LingXingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkspaceAccountService {

    public static final String SESSION_COOKIE = "WM_SESSION";
    public static final String DEFAULT_MCP_URL = "https://openmcp.lingxing.com/mcp-servers/lingxing-mcp";
    private static final int SESSION_DAYS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final LingXingProperties legacyProperties;

    private final RowMapper<AccountRow> accountMapper = (rs, rowNum) -> new AccountRow(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getString("status"), rs.getLong("session_version"), rs.getString("store_id"),
            rs.getString("store_name"), rs.getString("store_abbreviation"), rs.getString("sku_prefixes"),
            rs.getString("mcp_base_url"), rs.getString("mcp_key"), rs.getString("mcp_key_last4"),
            rs.getBoolean("supply_chain_enabled"), rs.getBoolean("primary_workspace"),
            rs.getString("initialization_status"), rs.getString("initialization_message"));

    public WorkspaceAccountService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            LingXingProperties legacyProperties
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.legacyProperties = legacyProperties;
    }

    public boolean setupRequired() {
        Integer count = jdbc.queryForObject("select count(*) from wm_ads_workspace", Integer.class);
        return count == null || count == 0;
    }

    public LegacyBootstrap legacyBootstrap() {
        return new LegacyBootstrap(
                legacyProperties.configured(),
                legacyProperties.resolvedStoreId(),
                legacyProperties.resolvedAdvertiserIds(),
                legacyProperties.resolvedBaseUrl(),
                legacyProperties.configured() ? last4(legacyProperties.apiKey()) : null);
    }

    @Transactional
    public SetupResult setupPrimary(SetupRequest request, String ip, String userAgent) {
        if (!setupRequired()) {
            throw new IllegalStateException("首次初始化已经完成");
        }
        validateConfirmedPassword(request.password(), request.confirmPassword());
        String abbreviation = normalizeAbbreviation(request.storeAbbreviation());
        String key = valueOr(request.mcpKey(), legacyProperties.apiKey());
        String baseUrl = valueOr(request.baseUrl(), legacyProperties.resolvedBaseUrl());
        String storeId = valueOr(request.storeId(), legacyProperties.resolvedStoreId());
        List<Long> advertisers = normalizedAdvertisers(request.advertiserIds(), legacyProperties.resolvedAdvertiserIds());
        if (!StringUtils.hasText(key) || !StringUtils.hasText(storeId)) {
            throw new IllegalArgumentException("请填写领星 MCP Key 并确认店铺");
        }
        List<StoreOption> stores = discoverStores(baseUrl, key);
        StoreOption selected = stores.stream().filter(store -> store.storeId().equals(storeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选店铺不在当前 MCP Key 的可访问范围内"));
        String storeName = requireStoreName(selected.storeName());
        if (!storeName.equalsIgnoreCase("PINKSIR")) {
            throw new IllegalArgumentException("首个工作区必须选择 PINKSIR 店铺");
        }
        long id = insertAccount(storeName, request.password(), "ACTIVE", storeId,
                storeName, abbreviation, List.of(abbreviation),
                baseUrl, key, true, true, null, "SYNCING");
        if (id != 1L) {
            throw new IllegalStateException("初始 PINKSIR 工作区必须使用固定编号 1");
        }
        replaceAdvertisers(id, advertisers);
        seedSyncSettings(id, !advertisers.isEmpty());
        audit(id, storeName, "FIRST_SETUP", true, "完成本机首次初始化", ip, userAgent);
        return new SetupResult(accountView(requireAccount(id), false));
    }

    @Transactional
    public SetupResult createAccount(CreateAccountRequest request, String ip, String userAgent) {
        WorkspacePrincipal creator = WorkspaceContext.requirePrincipal();
        validateConfirmedPassword(request.password(), request.confirmPassword());
        String abbreviation = normalizeAbbreviation(request.storeAbbreviation());
        String key = blankToNull(request.mcpKey());
        String baseUrl = valueOr(request.baseUrl(), DEFAULT_MCP_URL);
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("请填写 X-Mcp-Key");
        }
        List<StoreOption> stores = discoverStores(baseUrl, key);
        StoreOption selected = stores.stream().filter(store -> store.storeId().equals(request.storeId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选店铺不在当前 MCP Key 的可访问范围内"));
        String storeName = requireStoreName(selected.storeName());
        long id;
        try {
            id = insertAccount(storeName, request.password(), "ACTIVE", request.storeId(),
                    storeName, abbreviation, List.of(abbreviation), baseUrl, key,
                    false, false, creator.workspaceId(), "SYNCING");
            List<Long> advertisers = normalizedAdvertisers(request.advertiserIds(), selected.advertiserIds());
            replaceAdvertisers(id, advertisers);
            seedSyncSettings(id, !advertisers.isEmpty());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("该店铺名、Store ID、店铺缩写或广告账号已被其他工作区使用");
        }
        audit(creator.workspaceId(), creator.username(), "ACCOUNT_CREATE", true,
                "创建并绑定店铺账号 " + storeName, ip, userAgent);
        return new SetupResult(accountView(requireAccount(id), false));
    }

    public List<StoreOption> discoverRegistrationStores(RegisterDiscoveryRequest request) {
        if (setupRequired()) {
            throw new IllegalStateException("请先在服务器本机完成 PINKSIR 初始化");
        }
        String key = requireMcpKey(request == null ? null : request.mcpKey());
        return discoverStores(DEFAULT_MCP_URL, key);
    }

    @Transactional
    public SetupResult registerAccount(RegisterAccountRequest request, String ip, String userAgent) {
        if (setupRequired()) {
            throw new IllegalStateException("请先在服务器本机完成 PINKSIR 初始化");
        }
        if (request == null) {
            throw new IllegalArgumentException("请填写注册信息");
        }
        validateConfirmedPassword(request.password(), request.confirmPassword());
        String abbreviation = normalizeAbbreviation(request.storeAbbreviation());
        String key = requireMcpKey(request.mcpKey());
        List<StoreOption> stores = discoverStores(DEFAULT_MCP_URL, key);
        StoreOption selected = stores.stream()
                .filter(store -> store.storeId().equals(request.storeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选店铺不在当前 MCP Key 的可访问范围内"));
        String storeName = requireStoreName(selected.storeName());
        long id;
        try {
            id = insertAccount(storeName, request.password(), "ACTIVE", selected.storeId(),
                    storeName, abbreviation, List.of(abbreviation), DEFAULT_MCP_URL, key,
                    false, false, null, "SYNCING");
            replaceAdvertisers(id, selected.advertiserIds());
            seedSyncSettings(id, !selected.advertiserIds().isEmpty());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("该店铺或店铺缩写已经注册，请直接登录或联系 PINKSIR 重置密码");
        }
        audit(id, storeName, "ACCOUNT_SELF_REGISTER", true,
                "通过登录页注册并绑定领星店铺 " + storeName, ip, userAgent);
        return new SetupResult(accountView(requireAccount(id), false));
    }

    public LoginResult login(String username, String password, String ip, String userAgent) {
        String normalized = normalizeUsername(username);
        Optional<AccountRow> account = findByUsername(normalized);
        if (account.isEmpty() || !passwordEncoder.matches(password == null ? "" : password, account.get().passwordHash())) {
            audit(account.map(AccountRow::id).orElse(null), normalized, "LOGIN", false, "账号或密码错误", ip, userAgent);
            throw new IllegalArgumentException("账号或密码错误");
        }
        AccountRow row = account.get();
        String rawToken = randomToken(32);
        String tokenHash = sha256(rawToken);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into wm_ads_workspace_session
                        (workspace_id, token_hash, session_version, ip_address, user_agent, expires_at)
                    values (?, ?, ?, ?, ?, date_add(now(6), interval 7 day))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, row.id());
            statement.setString(2, tokenHash);
            statement.setLong(3, row.sessionVersion());
            statement.setString(4, truncate(ip, 64));
            statement.setString(5, truncate(userAgent, 500));
            return statement;
        }, keyHolder);
        audit(row.id(), row.username(), "LOGIN", true, "登录成功", ip, userAgent);
        return new LoginResult(rawToken, SESSION_DAYS * 24 * 60 * 60,
                principal(row, keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue()));
    }

    public Optional<WorkspacePrincipal> resolveSession(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return Optional.empty();
        }
        List<WorkspacePrincipal> rows = jdbc.query("""
                select w.id, w.username, w.status, w.store_id, w.store_name, w.store_abbreviation,
                       w.supply_chain_enabled, s.id session_id
                  from wm_ads_workspace_session s
                  join wm_ads_workspace w on w.id = s.workspace_id
                 where s.token_hash = ? and s.revoked_at is null and s.expires_at > now(6)
                   and s.session_version = w.session_version
                """, (rs, rowNum) -> new WorkspacePrincipal(
                rs.getLong("id"), rs.getString("username"), rs.getString("status"),
                rs.getString("store_id"), rs.getString("store_name"), rs.getString("store_abbreviation"),
                rs.getBoolean("supply_chain_enabled"), rs.getLong("session_id")), sha256(rawToken));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        WorkspacePrincipal principal = rows.getFirst();
        jdbc.update("update wm_ads_workspace_session set last_seen_at=now(6) where id=?", principal.sessionId());
        return Optional.of(principal);
    }

    public void logout(WorkspacePrincipal principal, String ip, String userAgent) {
        jdbc.update("update wm_ads_workspace_session set revoked_at=now(6) where id=?", principal.sessionId());
        audit(principal.workspaceId(), principal.username(), "LOGOUT", true, "退出登录", ip, userAgent);
    }

    public AccountView currentAccount(boolean includeKey) {
        return accountView(requireAccount(WorkspaceContext.requireId()), includeKey);
    }

    public List<Long> currentAdvertiserIds() {
        return advertiserIds(WorkspaceContext.requireId());
    }

    public List<StoreOption> discover(DiscoverRequest request) {
        String key = request == null ? null : request.mcpKey();
        String baseUrl = request == null ? null : request.baseUrl();
        if (!StringUtils.hasText(key)) {
            if (setupRequired()) {
                key = legacyProperties.apiKey();
                baseUrl = valueOr(baseUrl, legacyProperties.resolvedBaseUrl());
            } else {
                AccountRow account = requireAccount(WorkspaceContext.requireId());
                key = account.mcpKey();
                baseUrl = valueOr(baseUrl, account.mcpBaseUrl());
            }
        }
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("请先填写 X-Mcp-Key");
        }
        return discoverStores(valueOr(baseUrl, DEFAULT_MCP_URL), key);
    }

    public List<StoreOption> discoverStores(String baseUrl, String key) {
        LingXingClient client = new LingXingClient(baseUrl, key, 200, 15, 45, objectMapper, List.of());
        JsonNode data = client.callTool("get_multi_platform_shop_list", Map.of("platform_code", List.of("10008")));
        List<StoreOption> stores = new ArrayList<>();
        collectStores(data, stores);
        Map<String, StoreOption> unique = new LinkedHashMap<>();
        stores.forEach(store -> unique.putIfAbsent(store.storeId(), store));
        if (unique.isEmpty()) {
            throw new IllegalStateException("当前 MCP Key 未发现可访问的 Walmart 店铺");
        }
        return List.copyOf(unique.values());
    }

    @Transactional
    public AccountView updateMcp(StoreBindingRequest request, String ip, String userAgent) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        AccountRow current = requireAccount(principal.workspaceId());
        String key = valueOr(request.mcpKey(), current.mcpKey());
        String baseUrl = valueOr(request.baseUrl(), valueOr(current.mcpBaseUrl(), DEFAULT_MCP_URL));
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("请填写 X-Mcp-Key");
        }
        List<StoreOption> stores = discoverStores(baseUrl, key);
        StoreOption selected = stores.stream().filter(store -> store.storeId().equals(request.storeId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选店铺不在当前 MCP Key 的可访问范围内"));
        if (!selected.storeId().equals(current.storeId())) {
            throw new IllegalArgumentException("Store ID 创建后不可修改；请为新店铺创建独立账号");
        }
        List<String> prefixes = normalizedPrefixes(current.storeAbbreviation(), request.skuPrefixes());
        try {
            jdbc.update("""
                    update wm_ads_workspace
                       set mcp_base_url=?, mcp_key=?, mcp_key_last4=?, mcp_verified_at=now(6),
                           sku_prefixes=?, status='ACTIVE',
                           initialization_status='SYNCING', initialization_message='等待首次同步',
                           initialization_started_at=coalesce(initialization_started_at, now(6))
                     where id=?
                    """, baseUrl, key, last4(key), joinPrefixes(prefixes), principal.workspaceId());
            List<Long> advertisers = normalizedAdvertisers(request.advertiserIds(), List.of());
            replaceAdvertisers(principal.workspaceId(), advertisers);
            updateAdsSyncAvailability(principal.workspaceId(), !advertisers.isEmpty());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("该店铺或广告账号已绑定到其他工作区");
        }
        audit(principal.workspaceId(), principal.username(), "MCP_BINDING_UPDATE", true,
                "更新领星连接与店铺绑定", ip, userAgent);
        return accountView(requireAccount(principal.workspaceId()), false);
    }

    public String revealMcpKey(String purpose, String ip, String userAgent) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        AccountRow account = requireAccount(principal.workspaceId());
        audit(principal.workspaceId(), principal.username(), "copy".equalsIgnoreCase(purpose) ? "MCP_KEY_COPY" : "MCP_KEY_VIEW",
                true, "访问当前工作区 MCP Key", ip, userAgent);
        return account.mcpKey();
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request, String ip, String userAgent) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        AccountRow account = requireAccount(principal.workspaceId());
        validateConfirmedPassword(request.newPassword(), request.confirmPassword());
        jdbc.update("update wm_ads_workspace set password_hash=? where id=?",
                passwordEncoder.encode(request.newPassword()), account.id());
        audit(account.id(), account.username(), "PASSWORD_CHANGE", true, "修改密码；保留现有会话", ip, userAgent);
    }

    public List<ManagedAccountView> managedAccounts() {
        requirePinksir();
        return jdbc.query("""
                select id, username, store_id, store_name, store_abbreviation, status,
                       initialization_status, mcp_key is not null as mcp_configured
                  from wm_ads_workspace
                 order by username
                """, (rs, rowNum) -> new ManagedAccountView(
                rs.getLong("id"), rs.getString("username"), rs.getString("store_id"),
                rs.getString("store_name"), rs.getString("store_abbreviation"), rs.getString("status"),
                rs.getString("initialization_status"), rs.getBoolean("mcp_configured")));
    }

    @Transactional
    public void resetManagedPassword(long workspaceId, PasswordResetRequest request, String ip, String userAgent) {
        WorkspacePrincipal actor = requirePinksir();
        AccountRow target = requireAccount(workspaceId);
        if (target.username().equalsIgnoreCase("PINKSIR")) {
            throw new IllegalArgumentException("PINKSIR 请使用本机紧急重置入口或本人修改密码");
        }
        validateConfirmedPassword(request.newPassword(), request.confirmPassword());
        jdbc.update("update wm_ads_workspace set password_hash=? where id=?",
                passwordEncoder.encode(request.newPassword()), target.id());
        audit(target.id(), target.username(), "PASSWORD_RESET_BY_PINKSIR", true,
                "由 PINKSIR 重置密码；保留现有会话", ip, userAgent);
        audit(actor.workspaceId(), actor.username(), "MANAGED_PASSWORD_RESET", true,
                "重置店铺账号 " + target.username() + " 的密码", ip, userAgent);
    }

    @Transactional
    public void emergencyResetPinksir(PasswordResetRequest request, String ip, String userAgent) {
        validateConfirmedPassword(request.newPassword(), request.confirmPassword());
        AccountRow target = findByUsername("PINKSIR")
                .orElseThrow(() -> new IllegalArgumentException("PINKSIR 账号尚未创建"));
        jdbc.update("update wm_ads_workspace set password_hash=? where id=?",
                passwordEncoder.encode(request.newPassword()), target.id());
        audit(target.id(), target.username(), "PINKSIR_LOCAL_RESET", true,
                "通过服务器本机紧急重置密码；保留现有会话", ip, userAgent);
    }

    public List<SessionView> sessions() {
        WorkspacePrincipal current = WorkspaceContext.requirePrincipal();
        return jdbc.query("""
                select id, ip_address, user_agent, created_at, last_seen_at, expires_at
                  from wm_ads_workspace_session
                 where workspace_id=? and revoked_at is null and expires_at > now(6)
                 order by last_seen_at desc
                """, (rs, rowNum) -> new SessionView(
                rs.getLong("id"), rs.getLong("id") == current.sessionId(), rs.getString("ip_address"),
                rs.getString("user_agent"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("last_seen_at").toLocalDateTime(), rs.getTimestamp("expires_at").toLocalDateTime()),
                current.workspaceId());
    }

    public void revokeSession(long sessionId) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        jdbc.update("update wm_ads_workspace_session set revoked_at=now(6) where id=? and workspace_id=?",
                sessionId, principal.workspaceId());
    }

    public List<AuditView> auditLog() {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        return jdbc.query("""
                select event_type, success, summary, ip_address, created_at
                  from wm_ads_workspace_audit where workspace_id=? order by created_at desc limit 200
                """, (rs, rowNum) -> new AuditView(rs.getString("event_type"), rs.getBoolean("success"),
                rs.getString("summary"), rs.getString("ip_address"), rs.getTimestamp("created_at").toLocalDateTime()),
                principal.workspaceId());
    }

    public List<SyncSettingView> syncSettings() {
        return jdbc.query("""
                select sync_category, enabled, cadence, stagger_minute, last_started_at, last_completed_at,
                       last_status, last_message
                  from wm_ads_workspace_sync_setting where workspace_id=? order by sync_category
                """, (rs, rowNum) -> new SyncSettingView(rs.getString("sync_category"), rs.getBoolean("enabled"),
                rs.getString("cadence"), rs.getInt("stagger_minute"), timestamp(rs, "last_started_at"),
                timestamp(rs, "last_completed_at"), rs.getString("last_status"), rs.getString("last_message")),
                WorkspaceContext.requireId());
    }

    public void setSyncEnabled(String category, boolean enabled, String ip, String userAgent) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PRODUCTS", "SALES", "ADS", "INVENTORY", "REVIEWS", "HISTORY").contains(normalized)) {
            throw new IllegalArgumentException("未知同步类别");
        }
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        if (enabled && "ADS".equals(normalized) && advertiserIds(principal.workspaceId()).isEmpty()) {
            throw new IllegalArgumentException("请先填写广告账号 ID，再启用广告同步");
        }
        jdbc.update("update wm_ads_workspace_sync_setting set enabled=? where workspace_id=? and sync_category=?",
                enabled, principal.workspaceId(), normalized);
        audit(principal.workspaceId(), principal.username(), "SYNC_TOGGLE", true,
                (enabled ? "启用 " : "暂停 ") + normalized + " 同步", ip, userAgent);
    }

    private long insertAccount(
            String username, String password, String status, String storeId, String storeName, String storeAbbreviation,
            List<String> prefixes, String baseUrl, String key, boolean supplyChain, boolean primary,
            Long createdBy, String initializationStatus
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into wm_ads_workspace
                        (username, password_hash, status, store_id, store_name, store_abbreviation, sku_prefixes,
                         mcp_base_url, mcp_key, mcp_key_last4, mcp_verified_at, initialization_status,
                         supply_chain_enabled, primary_workspace, created_by_workspace_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, if(? is null, null, now(6)), ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, requireStoreName(username));
            statement.setString(2, passwordEncoder.encode(password));
            statement.setString(3, status);
            statement.setString(4, blankToNull(storeId));
            statement.setString(5, blankToNull(storeName));
            statement.setString(6, normalizeAbbreviation(storeAbbreviation));
            statement.setString(7, joinPrefixes(prefixes));
            statement.setString(8, valueOr(baseUrl, DEFAULT_MCP_URL));
            statement.setString(9, blankToNull(key));
            statement.setString(10, last4(key));
            statement.setString(11, blankToNull(key));
            statement.setString(12, initializationStatus);
            statement.setBoolean(13, supplyChain);
            statement.setBoolean(14, primary);
            if (createdBy == null) statement.setObject(15, null); else statement.setLong(15, createdBy);
            return statement;
        }, keyHolder);
        if (keyHolder.getKey() == null) {
            throw new IllegalStateException("创建工作区失败");
        }
        return keyHolder.getKey().longValue();
    }

    private void replaceAdvertisers(long workspaceId, List<Long> advertisers) {
        jdbc.update("delete from wm_ads_workspace_advertiser where workspace_id=?", workspaceId);
        for (Long advertiser : advertisers) {
            jdbc.update("insert into wm_ads_workspace_advertiser (workspace_id, advertiser_id) values (?, ?)",
                    workspaceId, advertiser);
        }
    }

    private List<Long> advertiserIds(long workspaceId) {
        return jdbc.queryForList("select advertiser_id from wm_ads_workspace_advertiser where workspace_id=? order by advertiser_id",
                Long.class, workspaceId);
    }

    private void seedSyncSettings(long workspaceId, boolean adsConfigured) {
        int minute = (int) Math.floorMod(workspaceId * 7, 50) + 5;
        for (String category : List.of("PRODUCTS", "SALES", "INVENTORY")) {
            jdbc.update("insert into wm_ads_workspace_sync_setting (workspace_id,sync_category,enabled,cadence,stagger_minute) values (?,?,1,'HOURLY',?)",
                    workspaceId, category, minute);
        }
        jdbc.update("""
                insert into wm_ads_workspace_sync_setting
                    (workspace_id,sync_category,enabled,cadence,stagger_minute,last_status,last_message)
                values (?,'ADS',?,'HOURLY',?,?,?)
                """, workspaceId, adsConfigured, minute,
                adsConfigured ? null : "UNCONFIGURED", adsConfigured ? null : "待填写广告账号 ID");
        jdbc.update("insert into wm_ads_workspace_sync_setting (workspace_id,sync_category,enabled,cadence,stagger_minute) values (?,?,1,'DAILY',?)",
                workspaceId, "REVIEWS", minute);
        jdbc.update("insert into wm_ads_workspace_sync_setting (workspace_id,sync_category,enabled,cadence,stagger_minute) values (?,?,1,'BACKGROUND',?)",
                workspaceId, "HISTORY", minute);
    }

    private void updateAdsSyncAvailability(long workspaceId, boolean adsConfigured) {
        jdbc.update("""
                update wm_ads_workspace_sync_setting
                   set enabled=?, last_status=?, last_message=?
                 where workspace_id=? and sync_category='ADS'
                """, adsConfigured, adsConfigured ? null : "UNCONFIGURED",
                adsConfigured ? null : "待填写广告账号 ID", workspaceId);
    }

    private AccountView accountView(AccountRow row, boolean includeKey) {
        return new AccountView(row.id(), row.username(), row.status(), row.storeId(), row.storeName(), row.storeAbbreviation(),
                splitPrefixes(row.skuPrefixes()), row.mcpBaseUrl(), row.mcpKeyLast4(), StringUtils.hasText(row.mcpKey()),
                includeKey ? row.mcpKey() : null, advertiserIds(row.id()), row.supplyChainEnabled(), row.primaryWorkspace(),
                row.initializationStatus(), row.initializationMessage());
    }

    private Optional<AccountRow> findByUsername(String username) {
        return jdbc.query("select * from wm_ads_workspace where lower(username)=lower(?)", accountMapper,
                username == null ? "" : username.trim()).stream().findFirst();
    }

    private AccountRow requireAccount(long id) {
        return jdbc.query("select * from wm_ads_workspace where id=?", accountMapper, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("工作区不存在"));
    }

    private WorkspacePrincipal principal(AccountRow row, long sessionId) {
        return new WorkspacePrincipal(row.id(), row.username(), row.status(), row.storeId(), row.storeName(),
                row.storeAbbreviation(), row.supplyChainEnabled(), sessionId);
    }

    private WorkspacePrincipal requirePinksir() {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        if (!principal.username().equalsIgnoreCase("PINKSIR")) {
            throw new IllegalArgumentException("仅 PINKSIR 店铺账号可以执行此操作");
        }
        return principal;
    }

    private void audit(Long workspaceId, String username, String type, boolean success, String summary, String ip, String userAgent) {
        jdbc.update("""
                insert into wm_ads_workspace_audit
                    (workspace_id, username, event_type, success, summary, ip_address, user_agent)
                values (?, ?, ?, ?, ?, ?, ?)
                """, workspaceId, truncate(username, 64), type, success, truncate(summary, 1000),
                truncate(ip, 64), truncate(userAgent, 500));
    }

    private void collectStores(JsonNode node, List<StoreOption> stores) {
        if (node == null || node.isNull() || node.isMissingNode()) return;
        if (node.isArray()) {
            node.forEach(child -> collectStores(child, stores));
            return;
        }
        if (!node.isObject()) return;
        String storeId = firstText(node, "store_id", "storeId", "sid", "seller_id", "sellerId");
        String platform = firstText(node, "platform_code", "platformCode", "platform");
        if (StringUtils.hasText(storeId) && (!StringUtils.hasText(platform) || platform.contains("10008") || platform.toLowerCase(Locale.ROOT).contains("walmart"))) {
            String name = firstText(node, "store_name", "storeName", "shop", "name", "seller_name");
            stores.add(new StoreOption(storeId, valueOr(name, "Walmart " + storeId), valueOr(platform, "10008"),
                    advertiserIdsFrom(node)));
        }
        node.fields().forEachRemaining(field -> collectStores(field.getValue(), stores));
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.isContainerNode() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static List<Long> advertiserIdsFrom(JsonNode node) {
        List<Long> values = new ArrayList<>();
        for (String field : List.of("walmart_advertiser_id", "walmartAdvertiserId", "advertiser_id",
                "advertiserId", "mpAdvertiserId", "advertiser_ids", "advertiserIds")) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isArray()) {
                value.forEach(item -> addAdvertiserIds(values, item.asText()));
            } else if (!value.isContainerNode()) {
                addAdvertiserIds(values, value.asText());
            }
        }
        return values.stream().filter(value -> value > 0).distinct().toList();
    }

    private static void addAdvertiserIds(List<Long> values, String raw) {
        if (!StringUtils.hasText(raw)) return;
        for (String value : raw.trim().split("[,;\\s]+")) {
            try {
                values.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // Ignore non-numeric labels returned alongside an advertiser identifier.
            }
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度需为 8–128 位");
        }
    }

    private static void validateConfirmedPassword(String password, String confirmation) {
        validatePassword(password);
        if (!password.equals(confirmation)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
    }

    private static String requireStoreName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("领星店铺名不能为空且不能超过 64 个字符");
        }
        return normalized;
    }

    private static String requireMcpKey(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("请填写领星 MCP Key");
        }
        if (normalized.length() > 512) {
            throw new IllegalArgumentException("领星 MCP Key 长度异常");
        }
        return normalized;
    }

    private static String normalizeAbbreviation(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,12}")) {
            throw new IllegalArgumentException("店铺缩写需为 2–12 位大写字母或数字");
        }
        return normalized;
    }

    private static List<String> normalizedPrefixes(String abbreviation, List<String> requested) {
        List<String> values = new ArrayList<>();
        values.add(normalizeAbbreviation(abbreviation));
        if (requested != null) values.addAll(requested);
        return values.stream().filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static List<Long> normalizedAdvertisers(List<Long> requested, List<Long> fallback) {
        List<Long> values = requested == null || requested.isEmpty() ? fallback : requested;
        return values == null ? List.of() : values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成会话摘要", exception);
        }
    }

    private static String valueOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String last4(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.substring(Math.max(0, trimmed.length() - 4));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static String joinPrefixes(List<String> prefixes) {
        if (prefixes == null) return null;
        String joined = String.join(",", prefixes.stream().filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList());
        return joined.isBlank() ? null : joined;
    }

    private static List<String> splitPrefixes(String prefixes) {
        if (!StringUtils.hasText(prefixes)) return List.of();
        return List.of(prefixes.split(","));
    }

    private static LocalDateTime timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private record AccountRow(long id, String username, String passwordHash, String status, long sessionVersion,
                              String storeId, String storeName, String storeAbbreviation, String skuPrefixes,
                              String mcpBaseUrl, String mcpKey, String mcpKeyLast4,
                              boolean supplyChainEnabled, boolean primaryWorkspace,
                              String initializationStatus, String initializationMessage) {
    }

    public record LegacyBootstrap(boolean configured, String storeId, List<Long> advertiserIds,
                                  String baseUrl, String keyLast4) {
    }

    public record StoreOption(String storeId, String storeName, String platformCode, List<Long> advertiserIds) {
    }

    public record AccountView(long id, String username, String status, String storeId, String storeName,
                              String storeAbbreviation, List<String> skuPrefixes, String mcpBaseUrl, String mcpKeyLast4,
                              boolean mcpConfigured, String mcpKey, List<Long> advertiserIds,
                              boolean supplyChainEnabled, boolean primaryWorkspace,
                              String initializationStatus, String initializationMessage) {
    }

    public record SetupResult(AccountView account) {
    }

    public record LoginResult(String token, int maxAgeSeconds, WorkspacePrincipal principal) {
    }

    public record SessionView(long id, boolean current, String ipAddress, String userAgent,
                              LocalDateTime createdAt, LocalDateTime lastSeenAt, LocalDateTime expiresAt) {
    }

    public record AuditView(String eventType, boolean success, String summary, String ipAddress,
                            LocalDateTime createdAt) {
    }

    public record SyncSettingView(String category, boolean enabled, String cadence, int staggerMinute,
                                  LocalDateTime lastStartedAt, LocalDateTime lastCompletedAt,
                                  String lastStatus, String lastMessage) {
    }

    public record SetupRequest(String password, String confirmPassword, String mcpKey, String baseUrl,
                               String storeId, String storeAbbreviation,
                               List<Long> advertiserIds) {
    }

    public record CreateAccountRequest(String password, String confirmPassword, String mcpKey, String baseUrl,
                                       String storeId, String storeAbbreviation, List<Long> advertiserIds) {
    }

    public record RegisterDiscoveryRequest(String mcpKey) {
    }

    public record RegisterAccountRequest(String password, String confirmPassword, String mcpKey,
                                         String storeId, String storeAbbreviation) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record DiscoverRequest(String mcpKey, String baseUrl) {
    }

    public record StoreBindingRequest(String mcpKey, String baseUrl, String storeId, String storeName,
                                      List<String> skuPrefixes, List<Long> advertiserIds) {
    }

    public record ChangePasswordRequest(String newPassword, String confirmPassword) {
    }

    public record PasswordResetRequest(String newPassword, String confirmPassword) {
    }

    public record ManagedAccountView(long id, String username, String storeId, String storeName,
                                     String storeAbbreviation, String status,
                                     String initializationStatus, boolean mcpConfigured) {
    }
}
