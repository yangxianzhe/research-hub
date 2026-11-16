package com.researchhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchhub.agent.LlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class LlmClientTest {
    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private LlmClient client() {
        return new LlmClient(new HubProperties(new HubProperties.Jobs(1, 3, 120, 500),
                new HubProperties.Llm("openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test-model", 1)), json);
    }

    @Test
    void sendsCompatibleRequestAndParsesStructuredResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"items\\\":[]}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var reply = client().generate("extract", Map.of("minutes", "待办：检查引用"));
        assertThat(reply.data().path("items")).isEmpty();
        assertThat(reply.promptTokens()).isEqualTo(10L);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(json.readTree(body.get()).path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void classifiesRateLimitsAsRetryable() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        assertThatThrownBy(() -> client().generate("extract", Map.of())).isInstanceOfSatisfying(LlmClient.GenerationException.class,
                error -> assertThat(error.retryable()).isTrue());
    }

    @Test
    void malformedOrTruncatedResponsesDoNotBecomeSuccesses() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"length\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        assertThatThrownBy(() -> client().generate("extract", Map.of())).isInstanceOfSatisfying(LlmClient.GenerationException.class,
                error -> assertThat(error.retryable()).isFalse());
    }

    @Test
    void responseBodyHasATotalDeadline() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { Thread.sleep(1800); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> client().generate("extract", Map.of())).isInstanceOf(LlmClient.GenerationException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(1700));
    }
}
