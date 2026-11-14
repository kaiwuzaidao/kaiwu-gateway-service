package com.kaiwu.gateway.agent.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kaiwu.gateway.agent.dto.AgentWriteRemoteRequest;
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
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.route.*;
import org.springframework.data.redis.core.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;

class AgentWriteDelegationControllerTest {
    static KeyPair systemKey, gatewayKey;
    static final JsonMapper JSON = JsonMapper.builder().build();
    HttpServer system, business;
    GatewayAuthProperties auth;
    SessionSnapshotRepository sessions;
    ReactiveStringRedisTemplate redis;
    ReactiveValueOperations<String, String> values;
    ProjectAccessProperties props;
    RouteDefinition route;
    AtomicReference<String> context = new AtomicReference<>();
    AtomicReference<Object> scope = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicInteger checks = new AtomicInteger(), calls = new AtomicInteger();
    AtomicBoolean allowed = new AtomicBoolean(true),
            confirmed = new AtomicBoolean(true),
            tampered = new AtomicBoolean(false);
    AgentWriteRemoteRequest preview, execute;

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
        system.createContext("/api/internal/gateway/agent-write-access", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Kaiwu-Internal-Token"))
                    .isEqualTo("internal-test");
            var check = JSON.readTree(exchange.getRequestBody().readAllBytes());
            checks.incrementAndGet();
            assertThat(check.path("userId").isMissingNode()).isTrue();
            assertThat(check.path("paramsJson").isMissingNode()).isTrue();
            String operation = check.path("operation").asString();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", "user-1");
            data.put("projectId", "123");
            data.put("projectCode", "demo");
            data.put("operation", operation);
            data.put("code", check.path("code").asString());
            data.put("version", check.path("version").asString());
            data.put("manifestHash", check.path("manifestHash").asString());
            data.put(
                    "paramsHash",
                    tampered.get() ? "f".repeat(64) : check.path("paramsHash").asString());
            data.put(
                    "preconditionsHash",
                    check.path("preconditionsHash").isNull()
                            ? null
                            : check.path("preconditionsHash").asString());
            data.put(
                    "previewHash",
                    check.path("previewHash").isNull()
                            ? null
                            : check.path("previewHash").asString());
            data.put(
                    "actionId",
                    check.path("actionId").isNull()
                            ? null
                            : check.path("actionId").asString());
            data.put(
                    "actionExpiresAt",
                    check.path("actionExpiresAt").isNull()
                            ? null
                            : check.path("actionExpiresAt").asLong());
            data.put("correlationId", check.path("correlationId").asString());
            data.put("confirmed", !"PREVIEW".equals(operation) && confirmed.get());
            data.put("timeoutMs", 2000);
            data.put("permissions", List.of("demo:record:update"));
            data.put("projectRoleCodes", List.of("editor"));
            if (scope.get() != null) data.put("dataScope", scope.get());
            byte[] response = JSON.writeValueAsBytes(Map.of("code", 0, "data", data));
            exchange.sendResponseHeaders(allowed.get() ? 200 : 403, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        system.start();
        business = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        business.createContext("/api/internal/agent/capabilities/demo.record.update/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            context.set(exchange.getRequestHeaders().getFirst("X-Kaiwu-Context"));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("sessionId").isMissingNode()).isTrue();
            assertThat(body.path("userId").isMissingNode()).isTrue();
            byte[] response = "{\"state\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
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
        Set<String> used = new ConcurrentSkipListSet<>();
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenAnswer(call -> Mono.fromSupplier(() -> used.add(call.getArgument(0))));
        route = new RouteDefinition();
        route.setId("demo");
        route.setUri(URI.create("http://127.0.0.1:" + business.getAddress().getPort()));
        route.setMetadata(Map.of("accessMode", "PROJECT", "projectCode", "demo", "audience", "demo-service"));
        preview = new AgentWriteRemoteRequest(
                "session-1",
                "PREVIEW",
                "demo.record.update",
                "1",
                "a".repeat(64),
                "{\"name\":\"new\"}",
                null,
                null,
                null,
                null,
                "corr-1");
        execute = new AgentWriteRemoteRequest(
                "session-1",
                "EXECUTE",
                "demo.record.update",
                "1",
                "a".repeat(64),
                "{\"name\":\"new\"}",
                "{\"revision\":1}",
                "b".repeat(64),
                "9ff3e37c-a5bb-4b0e-8a3f-94f5ab9e894b",
                Instant.now().plusSeconds(300).toEpochMilli(),
                "corr-1");
    }

    @AfterEach
    void stop() {
        system.stop(0);
        business.stop(0);
    }

    AgentWriteDelegationController controller(RouteDefinition... routes) {
        ObjectProvider balancer = mock(ObjectProvider.class);
        return new AgentWriteDelegationController(
                auth, new GatewayContextIssuer(auth), sessions, redis, () -> Flux.fromArray(routes), props, balancer);
    }

    byte[] body(AgentWriteRemoteRequest request) {
        return JSON.writeValueAsBytes(request);
    }

    String assertion(String type, String audience, byte[] body) {
        return Jwts.builder()
                .header()
                .type(type)
                .and()
                .issuer("kaiwu-system-service")
                .subject("kaiwu-system-service")
                .audience()
                .add(audience)
                .and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(30)))
                .claim("bodyHash", AgentDelegationVerifier.digest(body))
                .signWith(systemKey.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    MockServerHttpRequest http(String assertion, byte[] body) {
        return MockServerHttpRequest.post("/api/internal/agent/write-delegate")
                .header("Authorization", "Bearer " + assertion)
                .body(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void previewUsesReadOnlyUnconfirmedContextAndFixedPath() {
        var service = controller(route);
        byte[] body = body(preview);
        String assertion = assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body);
        assertThat(service.delegate(http(assertion, body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var jwt = Jwts.parser().verifyWith(gatewayKey.getPublic()).build().parseSignedClaims(context.get());
        assertThat(jwt.getHeader().getType()).isEqualTo("kaiwu-agent-write-context+jwt");
        assertThat(jwt.getPayload())
                .containsEntry("operation", "PREVIEW")
                .containsEntry("confirmed", false)
                .containsEntry("readOnly", true)
                .containsEntry(
                        "paramsHash",
                        AgentDelegationVerifier.digest(preview.paramsJson().getBytes(StandardCharsets.UTF_8)));
        assertThat(path.get()).endsWith("/preview");
        assertThat(service.delegate(http(assertion, body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(checks).hasValue(1);
        assertThat(calls).hasValue(1);
    }

    /** System 返回数据范围时写委托 Context 原样带出 ds；没有时不出现该键；形态非法则 403 且不触达业务服务。 */
    @Test
    void writeContextCarriesDataScopeOnlyWhenSystemReturnsIt() {
        var service = controller(route);
        byte[] body = body(preview);
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(Jwts.parser()
                        .verifyWith(gatewayKey.getPublic())
                        .build()
                        .parseSignedClaims(context.get())
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
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(Jwts.parser()
                        .verifyWith(gatewayKey.getPublic())
                        .build()
                        .parseSignedClaims(context.get())
                        .getPayload())
                .containsEntry("ds", scope.get());

        int before = calls.get();
        scope.set(List.of("not", "an", "object"));
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(calls).hasValue(before);
    }

    @Test
    void executeNeedsSystemConfirmationAndBoundDigests() {
        var service = controller(route);
        byte[] body = body(execute);
        confirmed.set(false);
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(calls).hasValue(0);
        confirmed.set(true);
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var jwt = Jwts.parser().verifyWith(gatewayKey.getPublic()).build().parseSignedClaims(context.get());
        assertThat(jwt.getPayload())
                .containsEntry("operation", "EXECUTE")
                .containsEntry("confirmed", true)
                .containsEntry("readOnly", false)
                .containsEntry(
                        "preconditionsHash",
                        AgentDelegationVerifier.digest(
                                execute.preconditionsJson().getBytes(StandardCharsets.UTF_8)))
                .containsEntry("previewHash", execute.previewHash())
                .containsEntry("actionId", execute.actionId());
        assertThat(jwt.getPayload().getExpiration().getTime()).isLessThanOrEqualTo(execute.actionExpiresAt());
        assertThat(path.get()).endsWith("/execute");
    }

    @Test
    void resultUsesOnlyReadOnlyContextEvenAfterActionExpiry() {
        var result = new AgentWriteRemoteRequest(
                execute.sessionId(),
                "RESULT",
                execute.code(),
                execute.version(),
                execute.manifestHash(),
                execute.paramsJson(),
                execute.preconditionsJson(),
                execute.previewHash(),
                execute.actionId(),
                Instant.now().minusSeconds(60).toEpochMilli(),
                execute.correlationId());
        byte[] body = body(result);
        assertThat(controller(route)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var jwt = Jwts.parser().verifyWith(gatewayKey.getPublic()).build().parseSignedClaims(context.get());
        assertThat(jwt.getPayload())
                .containsEntry("operation", "RESULT")
                .containsEntry("confirmed", true)
                .containsEntry("readOnly", true);
        assertThat(path.get()).endsWith("/result");
    }

    @Test
    void rejectsReadAssertionsRevokedSessionsAndNonProjectRoutes() {
        var service = controller(route);
        byte[] body = body(preview);
        assertThat(service.delegate(http(assertion("kaiwu-agent-delegation+jwt", "kaiwu-gateway-agent", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", new byte[0]),
                                body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        when(sessions.find("session-1")).thenReturn(Mono.empty());
        assertThat(service.delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        when(sessions.find("session-1")).thenReturn(Mono.just(new SessionSnapshot("user-1", "user", Set.of(), "v1")));
        route.setMetadata(Map.of("accessMode", "PLATFORM", "projectCode", "demo", "audience", "demo-service"));
        assertThat(controller(route)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        assertThat(calls).hasValue(0);
    }

    /** 写委托仅路由到 default，不把 Workflow 纳入候选目标。 */
    @Test
    void writeDelegationIgnoresNonDefaultService() {
        var workflow = new RouteDefinition();
        workflow.setId("demo.workflow-project");
        workflow.setUri(URI.create("http://workflow.invalid"));
        workflow.setMetadata(Map.of(
                "accessMode", "PROJECT", "projectCode", "demo", "serviceCode", "workflow", "audience", "workflow"));
        byte[] payload = body(preview);
        assertThat(controller(route, workflow)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", payload),
                                payload))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(controller(workflow)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", payload),
                                payload))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
    }

    @Test
    void refusesTamperedAuthorityAndAmbiguousOrUnsafeTargets() {
        byte[] body = body(preview);
        tampered.set(true);
        assertThat(controller(route)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        tampered.set(false);
        var second = new RouteDefinition();
        second.setId("second");
        second.setUri(URI.create("http://other.invalid"));
        second.setMetadata(route.getMetadata());
        assertThat(controller(route, second)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        route.setUri(URI.create("http://user:password@target.invalid"));
        assertThat(controller(route)
                        .delegate(http(
                                assertion("kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write", body), body))
                        .block()
                        .getStatusCode()
                        .value())
                .isEqualTo(503);
        assertThat(calls).hasValue(0);
    }
}
