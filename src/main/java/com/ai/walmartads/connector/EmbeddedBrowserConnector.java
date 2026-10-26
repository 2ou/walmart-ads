package com.ai.walmartads.connector;

import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.inventory.application.LingxingDomesticInventoryService;
import com.ai.walmartads.inventory.application.LingxingWfsShipmentService;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 由 Spring Boot 直接管理的浏览器采集器。只复用系统 Chrome 程序，不读取个人 Chrome 配置；
 * Cookie/LocalStorage 通过 Playwright storageState 加密存库，每次任务完成即关闭浏览器。
 */
@Service
public class EmbeddedBrowserConnector {

    private static final String INVENTORY_PAGE = "https://czyx.lingxing.com/erp/msupply/warehouseDetail";
    private static final String WFS_PAGE = "https://czyx.lingxing.com/erp/mmulti/pwShipment";
    private static final String SUPPLY_PAGE = "https://czyxgyl.shangyiku.top/produce/deliverGoods";

    private final DataConnectorProperties properties;
    private final DataConnectorSessionService sessions;
    private final ObjectMapper objectMapper;

    public EmbeddedBrowserConnector(DataConnectorProperties properties,
                                    DataConnectorSessionService sessions,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    public LingxingSnapshot readLingxing(WorkspacePrincipal principal, ConnectorSource source) {
        if (source != ConnectorSource.LINGXING_INVENTORY && source != ConnectorSource.LINGXING_WFS) {
            throw new IllegalArgumentException("不支持的领星数据源");
        }
        String state = sessions.browserState(principal.workspaceId(), ConnectorSystem.LINGXING);
        if (!StringUtils.hasText(state)) {
            throw new ConnectorLoginRequiredException("领星尚未登录，请在数据连接页面点击“登录领星”");
        }
        SourceSpec spec = source == ConnectorSource.LINGXING_INVENTORY
                ? new SourceSpec(INVENTORY_PAGE, LingxingDomesticInventoryService.SOURCE_PATH)
                : new SourceSpec(WFS_PAGE, LingxingWfsShipmentService.SOURCE_PATH);
        try (BrowserHandle handle = open(state, true)) {
            CapturedTemplate captured = captureTemplate(handle.context(), spec, properties.captureTimeout());
            JsonNode payload = readAllPages(captured.frame(), captured.template(), source,
                    principal.storeAbbreviation());
            sessions.save(principal.workspaceId(), ConnectorSystem.LINGXING,
                    handle.context().storageState(), null, false, "领星会话验证成功");
            return new LingxingSnapshot(captured.template().url(), payload, LocalDateTime.now());
        } catch (ConnectorLoginRequiredException exception) {
            sessions.requireLogin(principal.workspaceId(), ConnectorSystem.LINGXING,
                    exception.getMessage(), false);
            throw exception;
        }
    }

    public String captureSupplyCredential(WorkspacePrincipal principal) {
        String state = sessions.browserState(principal.workspaceId(), ConnectorSystem.SUPPLY_CHAIN);
        if (!StringUtils.hasText(state)) {
            throw new ConnectorLoginRequiredException("供应链尚未登录，请在数据连接页面点击“登录供应链”");
        }
        try (BrowserHandle handle = open(state, true)) {
            String authorization = captureSupplyAuthorization(handle.context(), properties.captureTimeout());
            sessions.save(principal.workspaceId(), ConnectorSystem.SUPPLY_CHAIN,
                    handle.context().storageState(), authorization, false, "供应链会话验证成功");
            return authorization;
        } catch (ConnectorLoginRequiredException exception) {
            sessions.requireLogin(principal.workspaceId(), ConnectorSystem.SUPPLY_CHAIN,
                    exception.getMessage(), true);
            throw exception;
        }
    }

    /** 打开可见登录窗口，检测成功后保存加密会话并自动关闭。 */
    public LoginResult login(WorkspacePrincipal principal, ConnectorSystem system) {
        String state;
        try {
            state = sessions.browserState(principal.workspaceId(), system);
        } catch (RuntimeException ignored) {
            state = null;
        }
        try (BrowserHandle handle = open(state, false)) {
            if (system == ConnectorSystem.LINGXING) {
                SourceSpec spec = new SourceSpec(INVENTORY_PAGE, LingxingDomesticInventoryService.SOURCE_PATH);
                captureTemplate(handle.context(), spec, properties.loginTimeout());
                sessions.save(principal.workspaceId(), system, handle.context().storageState(), null,
                        true, "领星登录成功");
            } else {
                String credential = captureSupplyAuthorization(handle.context(), properties.loginTimeout());
                sessions.save(principal.workspaceId(), system, handle.context().storageState(), credential,
                        true, "供应链登录成功");
            }
            return new LoginResult(system.name(), "CONNECTED", system.label() + "登录成功", LocalDateTime.now());
        } catch (RuntimeException exception) {
            sessions.requireLogin(principal.workspaceId(), system, rootMessage(exception),
                    system == ConnectorSystem.SUPPLY_CHAIN);
            throw exception;
        }
    }

    public Path chromeExecutable() {
        List<String> candidates = List.of(
                value(properties.chromePath()),
                "C:/Program Files/Google/Chrome/Application/chrome.exe",
                "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
                value(System.getenv("LOCALAPPDATA")) + "/Google/Chrome/Application/chrome.exe"
        );
        return candidates.stream()
                .filter(StringUtils::hasText)
                .map(Path::of)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到系统 Chrome，请先安装 Chrome 或配置 WALMART_ADS_CHROME_PATH"));
    }

    private BrowserHandle open(String storageState, boolean headless) {
        // We intentionally reuse the machine's Chrome executable. Prevent Playwright Java's
        // first-run bootstrap from downloading a second 140+ MB Chromium distribution.
        Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(
                Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        try {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(chromeExecutable())
                    .setHeadless(headless)
                    .setArgs(headless
                            ? List.of("--disable-session-crashed-bubble", "--no-first-run")
                            : List.of("--start-maximized", "--disable-session-crashed-bubble", "--no-first-run")));
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setAcceptDownloads(false)
                    .setViewportSize(1440, 900);
            if (StringUtils.hasText(storageState)) options.setStorageState(storageState);
            BrowserContext context = browser.newContext(options);
            context.setDefaultTimeout(properties.navigationTimeout().toMillis());
            context.setDefaultNavigationTimeout(properties.navigationTimeout().toMillis());
            return new BrowserHandle(playwright, browser, context);
        } catch (RuntimeException exception) {
            playwright.close();
            throw exception;
        }
    }

    private CapturedTemplate captureTemplate(BrowserContext context, SourceSpec source, Duration timeout) {
        Page page = context.newPage();
        AtomicReference<CapturedTemplate> captured = new AtomicReference<>();
        page.onRequest(request -> {
            if (captured.get() != null || !matchesPath(request.url(), source.apiPath())) return;
            captured.compareAndSet(null, new CapturedTemplate(request.frame(), template(request)));
        });
        try {
            page.navigate(source.pageUrl(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeout().toMillis()));
        } catch (RuntimeException ignored) {
            // SPA 的次要资源错误不妨碍网络请求捕获。
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextRedirect = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (captured.get() == null && System.nanoTime() < deadline) {
            ensurePageOpen(page);
            if (System.nanoTime() >= nextRedirect
                    && isLingxingApplication(page.url())
                    && !matchesPath(page.url(), URI.create(source.pageUrl()).getPath())) {
                try {
                    page.navigate(source.pageUrl(), new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(properties.navigationTimeout().toMillis()));
                } catch (RuntimeException ignored) {
                    // 登录后的 SPA 跳转可能中断导航；请求监听仍继续生效。
                }
                nextRedirect = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            }
            waitBriefly(page);
        }
        if (captured.get() == null) {
            String suffix = looksLikeLogin(page.url()) ? "，当前登录已失效" : "";
            safeClose(page);
            throw new ConnectorLoginRequiredException("领星页面没有发出目标数据请求" + suffix);
        }
        return captured.get();
    }

    private String captureSupplyAuthorization(BrowserContext context, Duration timeout) {
        Page page = context.newPage();
        AtomicReference<String> authorization = new AtomicReference<>();
        page.onRequest(request -> {
            try {
                URI uri = URI.create(request.url());
                if (!"czyxgyl.shangyiku.top".equalsIgnoreCase(uri.getHost())
                        || !uri.getPath().startsWith("/prod-api/")) return;
                String value = request.allHeaders().get("authorization");
                if (value != null && value.matches("(?i)^Bearer\\s+\\S+")) authorization.set(value.trim());
            } catch (RuntimeException ignored) {
                // 非业务请求忽略。
            }
        });
        try {
            page.navigate(SUPPLY_PAGE, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.navigationTimeout().toMillis()));
        } catch (RuntimeException ignored) {
            // 登录窗口继续等待用户完成登录。
        }
        waitFor(page, timeout, () -> StringUtils.hasText(authorization.get()));
        if (!StringUtils.hasText(authorization.get())) {
            String suffix = looksLikeLogin(page.url()) ? "，当前登录已失效" : "";
            safeClose(page);
            throw new ConnectorLoginRequiredException("没有捕获到供应链登录凭证" + suffix);
        }
        return authorization.get();
    }

    private JsonNode readAllPages(Frame frame, RequestTemplate template, ConnectorSource source,
                                  String storeAbbreviation) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("template", Map.of(
                "url", template.url(), "method", template.method(),
                "headers", template.headers(), "body", value(template.body())));
        args.put("kindName", source == ConnectorSource.LINGXING_INVENTORY ? "inventory" : "wfs");
        args.put("inventoryPrefix", value(storeAbbreviation).trim().toUpperCase(Locale.ROOT));
        Object result = frame.evaluate(PAGED_FETCH_SCRIPT, args);
        return objectMapper.valueToTree(result);
    }

    private static RequestTemplate template(Request request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.allHeaders().forEach((name, value) -> {
            if (!name.matches("(?i)^(cookie|host|content-length|origin|referer|accept-encoding|connection|sec-.*|proxy-.*)$")) {
                headers.put(name, value);
            }
        });
        return new RequestTemplate(request.url(), request.method(), headers, request.postData());
    }

    private static void waitFor(Page page, Duration timeout, Condition condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.done() && System.nanoTime() < deadline) {
            ensurePageOpen(page);
            waitBriefly(page);
        }
    }

    private static void ensurePageOpen(Page page) {
        if (page.isClosed()) {
            throw new ConnectorLoginRequiredException("登录窗口已关闭，尚未检测到登录成功");
        }
    }

    private static void waitBriefly(Page page) {
        try {
            page.waitForTimeout(500);
        } catch (RuntimeException exception) {
            if (page.isClosed() || rootMessage(exception).contains("Target page, context or browser has been closed")) {
                throw new ConnectorLoginRequiredException("登录窗口已关闭，尚未检测到登录成功");
            }
            throw exception;
        }
    }

    private static boolean matchesPath(String url, String expectedPath) {
        try {
            return URI.create(url).getPath().replaceAll("/+$", "").equals(expectedPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean looksLikeLogin(String url) {
        return value(url).matches("(?i).*?(login|passport|signin|auth).*?");
    }

    private static boolean isLingxingApplication(String url) {
        try {
            URI uri = URI.create(url);
            return "czyx.lingxing.com".equalsIgnoreCase(uri.getHost()) && !looksLikeLogin(url);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return current.getClass().getSimpleName();
        return message.lines().findFirst().orElse(message).trim();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void safeClose(Page page) {
        try {
            page.close();
        } catch (RuntimeException ignored) {
            // no-op
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean done();
    }

    private record SourceSpec(String pageUrl, String apiPath) {
    }

    private record RequestTemplate(String url, String method, Map<String, String> headers, String body) {
    }

    private record CapturedTemplate(Frame frame, RequestTemplate template) {
    }

    private record BrowserHandle(Playwright playwright, Browser browser,
                                 BrowserContext context) implements AutoCloseable {
        @Override
        public void close() {
            try {
                context.close();
            } finally {
                try {
                    browser.close();
                } finally {
                    playwright.close();
                }
            }
        }
    }

    public record LingxingSnapshot(String sourceUrl, JsonNode response, LocalDateTime capturedAt) {
    }

    public record LoginResult(String system, String status, String message, LocalDateTime completedAt) {
    }

    private static final String PAGED_FETCH_SCRIPT = """
            async ({template, kindName, inventoryPrefix}) => {
              const firstArray = (payload) => [payload?.data?.list, payload?.data?.rows,
                payload?.data?.records, payload?.data?.items, payload?.list, payload?.rows,
                payload?.records, payload?.items].find(Array.isArray) || null;
              const responseTotal = (payload) => [payload?.data?.total, payload?.data?.total_count,
                payload?.data?.totalCount, payload?.total, payload?.total_count,
                payload?.totalCount, payload?.count].map(Number).find(Number.isFinite) ?? null;
              const transformBody = (source, transform) => {
                if (!source.body) return source;
                try { return {...source, body: JSON.stringify(transform(JSON.parse(source.body)))}; }
                catch (_) {
                  const params = new URLSearchParams(source.body);
                  return {...source, body: new URLSearchParams(transform(Object.fromEntries(params.entries()))).toString()};
                }
              };
              let current = template;
              if (kindName === 'inventory') {
                if (!inventoryPrefix) throw new Error('工作台店铺缩写为空，无法限定领星库存范围');
                current = transformBody(current, value => ({...value, search_field:'sku',
                  search_value:inventoryPrefix, offset:0, length:500}));
              } else {
                current = transformBody(current, value => ({...value, queryValues:[], queryValue:'',
                  cargoStatusList:[], cargoLocalStatusList:[], shipStatus:null, hasShippingList:null,
                  shippingListStatuses:[], diffOfDeclareAndShip:null, diffOfDeclareAndSign:null,
                  diffOfSignAndDeclare:null, startTime:null, endTime:null, offset:0, length:500}));
              }
              const paging = (source) => {
                if (!source.body) return null;
                let value, json=true;
                try { value=JSON.parse(source.body); }
                catch (_) { json=false; value=Object.fromEntries(new URLSearchParams(source.body).entries()); }
                const pageKeys=['page','page_no','pageNo','page_num','pageNum','current','current_page'];
                const sizeKeys=['length','page_size','pageSize','limit','per_page','perPage'];
                const pageKey=Object.hasOwn(value,'offset') ? 'offset' : pageKeys.find(k=>Object.hasOwn(value,k));
                const sizeKey=sizeKeys.find(k=>Object.hasOwn(value,k));
                if (!pageKey || !sizeKey) return null;
                const size=Number(value[sizeKey]); if (!Number.isFinite(size) || size<=0) return null;
                return {size, apply(page) { const next={...value,[pageKey]:pageKey==='offset'?(page-1)*size:page};
                  return {...source,body:json?JSON.stringify(next):new URLSearchParams(next).toString()}; }};
              };
              const fetchJson = async (source) => {
                const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),60000);
                try {
                  const response=await fetch(source.url,{method:source.method,headers:source.headers,
                    body:['GET','HEAD'].includes(String(source.method).toUpperCase())?undefined:source.body,
                    credentials:'include',cache:'no-store',signal:controller.signal});
                  const payload=await response.json().catch(()=>null);
                  if (!response.ok || payload==null || (kindName==='wfs' && Number(payload.code)!==1))
                    throw new Error(`页面接口读取失败（HTTP ${response.status}）`);
                  return payload;
                } finally { clearTimeout(timer); }
              };
              const pager=paging(current); const first=await fetchJson(pager?pager.apply(1):current);
              const firstRows=firstArray(first); const total=responseTotal(first);
              if (!firstRows || total==null || firstRows.length>=total || !pager) return first;
              const rows=[...firstRows]; const pageCount=Math.min(100,Math.ceil(total/pager.size));
              for (let page=2;page<=pageCount;page++) {
                const payload=await fetchJson(pager.apply(page)); const next=firstArray(payload);
                if (!next) throw new Error(`第 ${page} 页结构无法识别`);
                rows.push(...next); if (!next.length || rows.length>=total) break;
              }
              if (rows.length<total) throw new Error(`分页不完整：只读取到 ${rows.length}/${total} 条`);
              return {...first,data:{...(first.data||{}),list:rows,total}};
            }
            """;
}
