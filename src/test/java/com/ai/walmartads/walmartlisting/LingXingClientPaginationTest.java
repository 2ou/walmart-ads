package com.ai.walmartads.walmartlisting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LingXingClientPaginationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aFullFirstPageMustNotBeTreatedAsTheLastPage() {
        assertThat(LingXingClient.hasFullListingPage(200, 200)).isTrue();
    }

    @Test
    void supportsAllKnownTotalFieldNamesWithoutDependingOnThemForTermination() throws Exception {
        assertThat(LingXingClient.listingTotal(objectMapper.readTree("{\"count\":1020}"), null))
                .isEqualTo(1020);
        assertThat(LingXingClient.listingTotal(objectMapper.readTree("{\"total\":\"1020\"}"), null))
                .isEqualTo(1020);
        assertThat(LingXingClient.listingTotal(objectMapper.readTree("{}"), 1020))
                .isEqualTo(1020);
    }

    @Test
    void sendsOnlyStatelessToolCallsAcrossClients() throws Exception {
        List<String> methods = new ArrayList<>();
        List<String> sessionHeaders = new ArrayList<>();
        List<String> protocolHeaders = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String method = objectMapper.readTree(request).path("method").asText();
                methods.add(method);
                sessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                protocolHeaders.add(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                String inner = objectMapper.writeValueAsString(Map.of(
                        "data", Map.of("success", true, "data", Map.of("ok", true))));
                respond(exchange, 200, objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", 2,
                        "result", Map.of("content", List.of(Map.of("type", "text", "text", inner)))
                )));
            } catch (Exception exception) {
                respond(exchange, 500, exception.getMessage());
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            LingXingClient first = new LingXingClient(url, "session-test-key", 20,
                    5, 5, objectMapper, List.of());
            LingXingClient second = new LingXingClient(url, "session-test-key", 20,
                    5, 5, objectMapper, List.of());

            assertThat(first.callTool("read_one", Map.of()).path("ok").asBoolean()).isTrue();
            assertThat(second.callTool("read_two", Map.of()).path("ok").asBoolean()).isTrue();

            assertThat(methods).containsExactly("tools/call", "tools/call");
            assertThat(sessionHeaders).containsExactly(null, null);
            assertThat(protocolHeaders).containsExactly(null, null);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void firstBusinessCallUsesExactlyOneHttpRequest() throws Exception {
        List<String> methods = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String method = objectMapper.readTree(request).path("method").asText();
                methods.add(method);
                String inner = objectMapper.writeValueAsString(Map.of(
                        "data", Map.of("success", true, "data", Map.of("mode", "stateless"))));
                respond(exchange, 200, objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "id", 2,
                        "result", Map.of("content", List.of(Map.of("type", "text", "text", inner)))
                )));
            } catch (Exception exception) {
                respond(exchange, 500, exception.getMessage());
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            LingXingClient client = new LingXingClient(url, "stateless-test-key", 20,
                    5, 5, objectMapper, List.of());
            assertThat(client.callTool("read", Map.of()).path("mode").asText()).isEqualTo("stateless");
            assertThat(methods).containsExactly("tools/call");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
