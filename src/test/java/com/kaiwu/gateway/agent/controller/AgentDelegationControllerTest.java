package com.kaiwu.gateway.agent.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kaiwu.gateway.agent.dto.AgentRemoteRequest;
import com.kaiwu.gateway.agent.service.AgentDelegationVerifier;
import com.kaiwu.gateway.auth.config.GatewayAuthProperties;
import com.kaiwu.gateway.auth.config.ProjectAccessProperties;
import com.kaiwu.gateway.auth.model.SessionSnapshot;
import com.kaiwu.gateway.auth.repository.SessionSnapshotRepository;
import com.kaiwu.gateway.auth.service.GatewayContextIssuer;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.route.*;
import org.springframework.data.redis.core.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;

class AgentDelegationControllerTest {
    static KeyPair systemKey, gatewayKey;
    static final JsonMapper JSON = JsonMapper.builder().build();
    HttpServer system, business;
    GatewayAuthProperties auth;
    ProjectAccessProperties props;
    SessionSnapshotRepository sessions;
    ReactiveStringRedisTemplate redis;
    ReactiveValueOperations<String, String> values;
    RouteDefinition route;
    AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
    AtomicBoolean allowed = new AtomicBoolean(true);
    AtomicReference<String> received = new AtomicReference<>();
    AtomicReference<Object> scope = new AtomicReference<>();
    byte[] body;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        systemKey = generator.generateKeyPair();
        gatewayKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setup() throws Exception {
        auth = new GatewayAuthProperties();
        auth.setAccessPublicKey(
                Base64.getEncoder().encodeToString(systemKey.getPublic().getEncoded()));
        auth.setContextPrivateKey(
                Base64.getEncoder().encodeToString(gatewayKey.getPrivate().getEncoded()));
        system = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        system.createContext("/api/internal/gateway/agent-access", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Kaiwu-Internal-Token"))
                    .isEqualTo("internal-test");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("session-1").doesNotContain("userId", "paramsJson");
            checks.incrementAndGet();
            Map<String, Object> data = new LinkedHashMap<>(Map.of(
                    "userId",
                    "user-1",
                    "projectId",
                    "123",
                    "projectCode",
                    "demo",
                    "code",
                    "demo.record.list",
                    "version",
                    "1",
                    "manifestHash",
                    "a".repeat(64),
                    "timeoutMs",
                    1000,
                    "permissions",
                    List.of("demo:record:list"),
                    "projectRoleCodes",
                    List.of("reader")));
            if (scope.get() != null) data.put("dataScope", scope.get());
            byte[] response = JSON.writeValueAsBytes(Map.of("code", 0, "data", data));
            exchange.sendResponseHeaders(allowed.get() ? 200 : 403, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        system.start();
        business = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        business.createContext("/api/internal/agent/capabilities/demo.record.list/invoke", exchange -> {
            calls.incrementAndGet();
            received.set(exchange.getRequestHeaders().getFirst("X-Kaiwu-Context"));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            String invocation = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(invocation).contains("paramsJson").doesNotContain("sessionId", "userId", "targetUrl");
            byte[] response = "{\"rows\":[{\"name\":\"test\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        business.start();
        props = new ProjectAccessProperties();
        props.setSystemBaseUrl("http://127.0.0.1:" + system.getAddress().getPort());
        props.setInternalToken("internal-test");
        sessions = mock(SessionSnapshotRepository.class);
        when(sessions.find("session-1"))
                .thenReturn(Mono.just(new SessionSnapshot("user-1", "user", Set.of("system:agent:ask"), "v1")));
        redis = mock(ReactiveStringRedisTemplate.class);
        values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Set<String> used = new java.util.concurrent.ConcurrentSkipListSet<>();
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenAnswer(call -> Mono.fromSupplier(() -> used.add(call.getArgument(0))));
        route = new RouteDefinition();
        route.setId("demo");
        route.setUri(URI.create("http://127.0.0.1:" + business.getAddress().getPort()));
        route.setMetadata(Map.of("accessMode", "PROJECT", "projectCode", "demo", "audience", "demo-service"));
        body = JSON.writeValueAsBytes(
                new AgentRemoteRequest("session-1", "demo.record.list", "1", "a".repeat(64), "{}", "request-1"));
    }

    @AfterEach
    void stop() {
        system.stop(0);
        business.stop(0);
    }

    AgentDelegationController controller(RouteDefinition... routes) {
        ObjectProvider balancer = mock(ObjectProvider.class);
        return new AgentDelegationController(
                auth, new GatewayContextIssuer(auth), sessions, redis, () -> Flux.fromArray(routes), props, balancer);
    }

    String assertion(String type, byte[] bytes, int seconds) {
        return Jwts.builder()
                .header()
                .type(type)
                .and()
                .issuer("kaiwu-system-service")
                .subject("kaiwu-system-service")
                .audience()
                .add("kaiwu-gateway-agent")
                .and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(seconds)))
                .claim("bodyHash", AgentDelegationVerifier.digest(bytes))
                .signWith(systemKey.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    MockServerHttpRequest request(String token, byte[] bytes) {
        return MockServerHttpRequest.post("/api/internal/agent/delegate")
                .header("Authorization", "Bearer " + token)
                .body(new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void forwardsOnlyToBoundRouteAndIssuesNarrowContext() {
        var service = controller(route);
        String assertion = assertion("kaiwu-agent-delegation+jwt", body, 30);
        var response = service.delegate(request(assertion, body)).block(Duration.ofSeconds(5));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var token = Jwts.parser().verifyWith(gatewayKey.getPublic()).build().parseSignedClaims(received.get());
        assertThat(token.getHeader().getType()).isEqualTo("kaiwu-agent-action-context+jwt");
        assertThat(token.getPayload().getSubject()).isEqualTo("user-1");
        assertThat(token.getPayload().getAudience()).containsExactly("demo-service");
        assertThat(token.getPayload())
                .containsEntry("projectId", "123")
                .containsEntry("projectCode", "demo")
                .containsEntry("paramsHash", AgentDelegationVerifier.digest("{}".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("manifestHash", "a".repeat(64))
                .containsEntry("capability", "demo.record.list")
                .containsEntry("readOnly", true);
        assertThat(service.delegate(request(assertion, body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(calls).hasValue(1);
        assertThat(checks).hasValue(1);
    }

    /** System 返回数据范围时读委托 Context 原样带出 ds；没有时不出现该键；形态非法则 403 且不触达业务服务。 */
    @Test
    void readContextCarriesDataScopeOnlyWhenSystemReturnsIt() {
        var service = controller(route);
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(Jwts.parser()
                        .verifyWith(gatewayKey.getPublic())
                        .build()
                        .parseSignedClaims(received.get())
                        .getPayload())
                .doesNotContainKey("ds");

        scope.set(Map.of(
                "v",
                1,
                "primaryDeptId",
                "101",
                "depts",
                List.of("101", "102"),
                "rules",
                Map.of("demo:record", Map.of("set", List.of(0, 1))),
                "fp",
                "0123456789abcdef"));
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(Jwts.parser()
                        .verifyWith(gatewayKey.getPublic())
                        .build()
                        .parseSignedClaims(received.get())
                        .getPayload())
                .containsEntry("ds", scope.get());

        scope.set("oops");
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(calls).hasValue(2);
    }

    @Test
    void refusesBrowserTokensChangedBodyAndOverlongLifetime() {
        var service = controller(route);
        for (String invalid : List.of(
                assertion("JWT", body, 30),
                assertion("kaiwu-agent-delegation+jwt", new byte[0], 30),
                assertion("kaiwu-agent-delegation+jwt", body, 31)))
            assertThat(service.delegate(request(invalid, body))
                            .block()
                            .getStatusCode()
                            .value())
                    .isEqualTo(401);
        assertThat(calls).hasValue(0);
        assertThat(checks).hasValue(0);
    }

    @Test
    void rechecksPermissionOnEveryCallAndFailsClosed() {
        var service = controller(route);
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        allowed.set(false);
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        assertThat(calls).hasValue(1);
        assertThat(checks).hasValue(2);
    }

    @Test
    void revokedSessionAndRedisFailureNeverReachTarget() {
        var service = controller(route);
        when(sessions.find("session-1")).thenReturn(Mono.empty());
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("redis")));
        assertThat(service.delegate(request(assertion("kaiwu-agent-delegation+jwt", body, 30), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        assertThat(calls).hasValue(0);
        assertThat(checks).hasValue(0);
    }

    /** 非 default 服务不能使 Agent 委托发生歧义或成为目标。 */
    @Test
    void agentUsesOnlyDefaultService() {
        var workflow = new RouteDefinition();
        workflow.setId("demo.workflow-project");
        workflow.setUri(URI.create("http://workflow.invalid"));
        workflow.setMetadata(Map.of(
                "accessMode", "PROJECT", "projectCode", "demo", "serviceCode", "workflow", "audience", "workflow"));
        assertThat(controller(route, workflow).target("demo").block().uri()).isEqualTo(route.getUri());
        assertThatThrownBy(() -> controller(workflow).target("demo").block()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesAbsentAmbiguousPlatformAndMalformedTargets() {
        assertThatThrownBy(() -> controller().target("demo").block()).isInstanceOf(RuntimeException.class);
        var other = new RouteDefinition();
        other.setId("other");
        other.setUri(URI.create("http://other.invalid"));
        other.setMetadata(route.getMetadata());
        assertThatThrownBy(() -> controller(route, other).target("demo").block())
                .isInstanceOf(RuntimeException.class);
        for (String uri :
                List.of("http://user:pass@target", "http://target/path", "http://target?query", "file:///tmp/test")) {
            route.setUri(URI.create(uri));
            assertThatThrownBy(() -> controller(route).target("demo").block()).isInstanceOf(RuntimeException.class);
        }
        route.setMetadata(Map.of("accessMode", "PLATFORM", "projectCode", "demo", "audience", "demo-service"));
        assertThatThrownBy(() -> controller(route).target("demo").block()).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(0);
    }
}
