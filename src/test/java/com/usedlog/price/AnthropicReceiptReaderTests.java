package com.usedlog.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;
import com.usedlog.price.ReceiptReader.Photo;
import com.usedlog.price.ReceiptReader.ReceiptDraft;

/** The Claude client against a local stand-in for the Messages API: request shape and reply handling. */
class AnthropicReceiptReaderTests {

    private static final String DRAFT_JSON = """
            {"storeName":"Giant","purchaseDate":"2026-10-20","purchaseTime":"14:05","currency":"USD",
             "items":[{"receiptText":"GNT LG EGGS 12CT","name":"Eggs, large, 12 ct","quantity":1,"unit":"pcs",
                       "unitPrice":3.49,"lineTotal":3.49,"category":"Dairy & eggs"}],
             "subtotal":3.49,"tax":0.21,"total":3.70,"notes":null}
            """;

    private HttpServer server;
    private volatile int status;
    private volatile String reply;
    private volatile String requestBody;
    private volatile String authorizationHeader;
    private volatile String workspaceHeader;
    private volatile String versionHeader;
    private AnthropicReceiptReader reader;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            workspaceHeader = exchange.getRequestHeaders().getFirst("anthropic-workspace-id");
            versionHeader = exchange.getRequestHeaders().getFirst("anthropic-version");
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });
        server.start();
        reader = new AnthropicReceiptReader("test-key", "", "claude-haiku-5-5", baseUrl());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsPhotosWithTheSchemaAndParsesTheReply() {
        status = 200;
        reply = message("end_turn", DRAFT_JSON);

        ReceiptDraft draft = reader.read(
                List.of(new Photo(new byte[] {1, 2, 3}, "image/jpeg")),
                List.of("Milk 2%, 1 gal"), List.of("Giant"));

        assertThat(draft.storeName()).isEqualTo("Giant");
        assertThat(draft.items()).hasSize(1);
        assertThat(draft.items().get(0).name()).isEqualTo("Eggs, large, 12 ct");
        assertThat(draft.items().get(0).unitPrice()).isEqualByComparingTo(new BigDecimal("3.49"));
        assertThat(draft.total()).isEqualByComparingTo(new BigDecimal("3.70"));

        assertThat(authorizationHeader).isEqualTo("Bearer test-key");
        assertThat(workspaceHeader).isNull();
        assertThat(versionHeader).isEqualTo("2023-06-01");
        assertThat(requestBody)
                .contains("\"model\":\"claude-haiku-5-5\"")
                .contains("\"output_config\":{\"format\":")
                .contains("\"type\":\"json_schema\"")
                .contains("\"media_type\":\"image/jpeg\"")
                .contains("\"data\":\"AQID\"")
                .contains("Milk 2%, 1 gal")
                .contains("existing stores: Giant")
                .doesNotContain("temperature");
    }

    @Test
    void overloadedApiIsABadGateway() {
        status = 529;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        assertThatThrownBy(this::readOnePhoto)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void rejectedKeyIsServiceUnavailable() {
        status = 401;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
        assertThatThrownBy(this::readOnePhoto)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getReason()).contains("ANTHROPIC_API_KEY");
                });
    }

    @Test
    void refusalOrCutOffReplyIsUnreadable() {
        status = 200;
        reply = message("max_tokens", "{\"storeName\":");
        assertThatThrownBy(this::readOnePhoto)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void isOffWithoutAKey() {
        assertThat(new AnthropicReceiptReader(" ", "", "claude-haiku-5-5", "http://127.0.0.1:1").isConfigured()).isFalse();
        assertThat(reader.isConfigured()).isTrue();
    }

    @Test
    void sendsTheWorkspaceWhenConfigured() {
        status = 200;
        reply = message("end_turn", DRAFT_JSON);
        new AnthropicReceiptReader("test-key", "wrkspc_123", "claude-haiku-5-5", baseUrl()).read(
                List.of(new Photo(new byte[] {1}, "image/png")), List.of(), List.of());
        assertThat(workspaceHeader).isEqualTo("wrkspc_123");
    }

    @Test
    void unscopedKeyWithoutWorkspaceExplainsTheFix() {
        status = 400;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                + "\"This API key is not scoped to a workspace, so this request must include the anthropic-workspace-id header\"}}";
        assertThatThrownBy(this::readOnePhoto)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getReason()).contains("ANTHROPIC_WORKSPACE_ID");
                });
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ReceiptDraft readOnePhoto() {
        return reader.read(List.of(new Photo(new byte[] {1}, "image/png")), List.of(), List.of());
    }

    /** A Messages API reply with a thinking block first, as adaptive-thinking models send. */
    private static String message(String stopReason, String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":[{"type":"thinking","thinking":"Reading the receipt.","signature":"sig"},
                            {"type":"text","text":"%s"}],
                 "stop_reason":"%s","usage":{"input_tokens":1200,"output_tokens":300}}
                """.formatted(escaped, stopReason);
    }
}
