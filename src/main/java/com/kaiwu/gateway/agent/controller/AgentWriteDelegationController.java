package com.kaiwu.gateway.agent.controller;

import com.kaiwu.gateway.agent.dto.AgentWriteRemoteRequest;
import com.kaiwu.gateway.agent.service.AgentDelegationVerifier;
import com.kaiwu.gateway.auth.config.GatewayAuthProperties;
import com.kaiwu.gateway.auth.config.ProjectAccessProperties;
import com.kaiwu.gateway.auth.model.ProjectAccess;
import com.kaiwu.gateway.auth.repository.SessionSnapshotRepository;
import com.kaiwu.gateway.auth.service.GatewayContextIssuer;
import com.kaiwu.gateway.client.ServiceWebClientFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 固定写用途委托：每次从 System 读取权威状态，只转发到唯一 PROJECT Handler，绝不自动重试。 */
@RestController
@ConditionalOnProperty(prefix = "kaiwu.gateway.agent", name = "write-enabled", havingValue = "true")
public class AgentWriteDelegationController {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final AgentDelegationVerifier verifier;
    private final GatewayContextIssuer issuer;
    private final SessionSnapshotRepository sessions;
    private final ReactiveStringRedisTemplate redis;
    private final RouteDefinitionLocator routes;
    private final WebClient system;
    private final String internalToken;
    private final ReactiveLoadBalancer.Factory<ServiceInstance> balancer;

    public AgentWriteDelegationController(
            GatewayAuthProperties auth,
            GatewayContextIssuer issuer,
            SessionSnapshotRepository sessions,
            ReactiveStringRedisTemplate redis,
            @Qualifier("routeDefinitionLocator") RouteDefinitionLocator routes,
            ProjectAccessProperties access,
            ObjectProvider<ReactiveLoadBalancer.Factory<ServiceInstance>> balancer) {
        this.verifier = new AgentDelegationVerifier(auth);
        this.issuer = issuer;
        this.sessions = sessions;
        this.redis = redis;
        this.routes = routes;
        this.balancer = balancer.getIfAvailable();
        this.internalToken = access.getInternalToken();
        if (internalToken == null || internalToken.isBlank())
            throw new IllegalStateException("Agent authorization unavailable");
        this.system = ServiceWebClientFactory.create(access.getSystemBaseUrl(), Duration.ofSeconds(3), this.balancer);
    }

    @PostMapping(value = "/api/internal/agent/write-delegate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<byte[]>> delegate(ServerHttpRequest http) {
        return DataBufferUtils.join(http.getBody(), 65_536)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST)))
                .flatMap(buffer -> {
                    byte[] body = new byte[buffer.readableByteCount()];
                    buffer.read(body);
                    DataBufferUtils.release(buffer);
                    String jti = verifier.verifyWrite(http.getHeaders().getFirst(HttpHeaders.AUTHORIZATION), body);
                    AgentWriteRemoteRequest request = JSON.readValue(body, AgentWriteRemoteRequest.class);
                    request.validate();
                    return redis.opsForValue()
                            .setIfAbsent("kaiwu:agent:write-delegation:" + jti, "1", Duration.ofSeconds(60))
                            .filter(Boolean.TRUE::equals)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                            .flatMap(used -> invoke(request));
                })
                .map(body -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(body))
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(failure -> {
                    int status = failure instanceof ResponseStatusException typed
                            ? typed.getStatusCode().value()
                            : 503;
                    return Mono.just(ResponseEntity.status(status)
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .body(JSON.writeValueAsBytes(
                                    Map.of("code", status, "message", "Agent invocation unavailable"))));
                });
    }

    private Mono<byte[]> invoke(AgentWriteRemoteRequest request) {
        String paramsHash = AgentDelegationVerifier.digest(request.paramsJson().getBytes(StandardCharsets.UTF_8));
        String preconditionsHash = request.preconditionsJson() == null
                ? null
                : AgentDelegationVerifier.digest(request.preconditionsJson().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("sessionId", request.sessionId());
        check.put("operation", request.operation());
        check.put("code", request.code());
        check.put("version", request.version());
        check.put("manifestHash", request.manifestHash());
        check.put("paramsHash", paramsHash);
        check.put("preconditionsHash", preconditionsHash);
        check.put("previewHash", request.previewHash());
        check.put("actionId", request.actionId());
        check.put("actionExpiresAt", request.actionExpiresAt());
        check.put("correlationId", request.correlationId());
        return sessions.find(request.sessionId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(session -> system.post()
                        .uri("/api/internal/gateway/agent-write-access")
                        .header("X-Kaiwu-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(check)
                        .retrieve()
                        .bodyToMono(byte[].class)
                        .timeout(Duration.ofSeconds(3))
                        .flatMap(body -> {
                            var root = JSON.readTree(body);
                            var data = root.path("data");
                            if (root.path("code").asInt(-1) != 0
                                    || !session.userId()
                                            .equals(data.path("userId").asString())
                                    || !request.operation()
                                            .equals(data.path("operation").asString())
                                    || !request.code().equals(data.path("code").asString())
                                    || !request.version()
                                            .equals(data.path("version").asString())
                                    || !request.manifestHash()
                                            .equals(data.path("manifestHash").asString())
                                    || !paramsHash.equals(
                                            data.path("paramsHash").asString())
                                    || !Objects.equals(preconditionsHash, nullable(data.path("preconditionsHash")))
                                    || !Objects.equals(request.previewHash(), nullable(data.path("previewHash")))
                                    || !Objects.equals(request.actionId(), nullable(data.path("actionId")))
                                    || !Objects.equals(
                                            request.actionExpiresAt(), nullableLong(data.path("actionExpiresAt")))
                                    || !request.correlationId()
                                            .equals(data.path("correlationId").asString())
                                    || !data.path("confirmed").isBoolean()
                                    || data.path("confirmed").asBoolean() == "PREVIEW".equals(request.operation()))
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                            String projectId = data.path("projectId").asString();
                            String projectCode = data.path("projectCode").asString();
                            int timeoutMs = data.path("timeoutMs").asInt();
                            if (!projectId.matches("[1-9][0-9]{0,19}")
                                    || !projectCode.matches("[a-z][a-z0-9-]{0,63}")
                                    || !data.path("permissions").isArray()
                                    || !data.path("projectRoleCodes").isArray()
                                    || timeoutMs < 100
                                    || timeoutMs > 10_000)
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                            ProjectAccess access = new ProjectAccess(
                                    projectId,
                                    projectCode,
                                    JSON.convertValue(data.path("permissions"), new TypeReference<List<String>>() {}),
                                    JSON.convertValue(
                                            data.path("projectRoleCodes"), new TypeReference<List<String>>() {}),
                                    AgentDelegationController.dataScope(data));
                            return target(projectCode).flatMap(target -> {
                                String context = issuer.issueForAgentWrite(
                                        session.userId(),
                                        request.sessionId(),
                                        target.audience(),
                                        access,
                                        request.operation(),
                                        request.code(),
                                        request.version(),
                                        request.manifestHash(),
                                        paramsHash,
                                        preconditionsHash,
                                        request.previewHash(),
                                        request.actionId(),
                                        request.actionExpiresAt(),
                                        request.correlationId(),
                                        data.path("confirmed").asBoolean());
                                String suffix =
                                        switch (request.operation()) {
                                            case "PREVIEW" -> "preview";
                                            case "EXECUTE" -> "execute";
                                            case "RESULT" -> "result";
                                            default -> throw new IllegalStateException("Invalid Agent write operation");
                                        };
                                Map<String, Object> invocation = new LinkedHashMap<>();
                                invocation.put("code", request.code());
                                invocation.put("version", request.version());
                                invocation.put("manifestHash", request.manifestHash());
                                invocation.put("paramsJson", request.paramsJson());
                                invocation.put("preconditionsJson", request.preconditionsJson());
                                invocation.put("previewHash", request.previewHash());
                                invocation.put("actionId", request.actionId());
                                invocation.put("actionExpiresAt", request.actionExpiresAt());
                                invocation.put("correlationId", request.correlationId());
                                return ServiceWebClientFactory.create(
                                                target.uri().toString(), Duration.ofMillis(timeoutMs), balancer)
                                        .post()
                                        .uri("/api/internal/agent/capabilities/" + request.code() + "/" + suffix)
                                        .header("X-Kaiwu-Context", context)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(invocation)
                                        .exchangeToMono(response ->
                                                response.statusCode().value() == 200
                                                        ? response.bodyToMono(byte[].class)
                                                                .filter(bytes -> bytes.length <= 65_536)
                                                                .switchIfEmpty(Mono.error(new ResponseStatusException(
                                                                        HttpStatus.SERVICE_UNAVAILABLE)))
                                                        : response.releaseBody()
                                                                .then(Mono.error(new ResponseStatusException(
                                                                        HttpStatus.SERVICE_UNAVAILABLE))))
                                        .timeout(Duration.ofMillis(timeoutMs));
                            });
                        }));
    }

    private static String nullable(tools.jackson.databind.JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static Long nullableLong(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isIntegralNumber()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return node.asLong();
    }

    private record Target(URI uri, String audience) {}

    private Mono<Target> target(String projectCode) {
        return routes.getRouteDefinitions()
                .filter(route -> "PROJECT".equals(route.getMetadata().get("accessMode"))
                        && projectCode.equals(route.getMetadata().get("projectCode"))
                        && "default"
                                .equals(Objects.toString(
                                        route.getMetadata().getOrDefault("serviceCode", "default"), "")))
                .map(route -> new Target(
                        route.getUri(), Objects.toString(route.getMetadata().get("audience"), "")))
                .distinct()
                .take(2)
                .collectList()
                .flatMap(targets -> {
                    if (targets.size() != 1)
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
                    Target target = targets.getFirst();
                    URI uri = target.uri();
                    if (target.audience().isBlank()
                            || uri.getHost() == null
                            || uri.getUserInfo() != null
                            || uri.getQuery() != null
                            || uri.getFragment() != null
                            || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                            || !Set.of("http", "https", "lb").contains(uri.getScheme()))
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
                    return Mono.just(target);
                });
    }
}
