package com.kaiwu.gateway.agent.controller;

import com.kaiwu.gateway.agent.dto.AgentRemoteRequest;
import com.kaiwu.gateway.agent.service.AgentDelegationVerifier;
import com.kaiwu.gateway.auth.config.GatewayAuthProperties;
import com.kaiwu.gateway.auth.config.ProjectAccessProperties;
import com.kaiwu.gateway.auth.model.ProjectAccess;
import com.kaiwu.gateway.auth.repository.SessionSnapshotRepository;
import com.kaiwu.gateway.auth.service.DataScopeClaim;
import com.kaiwu.gateway.auth.service.GatewayContextIssuer;
import com.kaiwu.gateway.client.ServiceWebClientFactory;
import java.net.URI;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 内部委托专用入口，服务断言认证后仍逐次读取会话与 System 授权，绝不采用缓存。 */
@RestController
@ConditionalOnProperty(prefix = "kaiwu.gateway.agent", name = "enabled", havingValue = "true")
public class AgentDelegationController {
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

    public AgentDelegationController(
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

    @PostMapping(value = "/api/internal/agent/delegate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<byte[]>> delegate(ServerHttpRequest http) {
        return DataBufferUtils.join(http.getBody(), 65536)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST)))
                .flatMap(buffer -> {
                    byte[] body = new byte[buffer.readableByteCount()];
                    buffer.read(body);
                    DataBufferUtils.release(buffer);
                    String jti = verifier.verify(http.getHeaders().getFirst(HttpHeaders.AUTHORIZATION), body);
                    var request = JSON.readValue(body, AgentRemoteRequest.class);
                    request.validate();
                    return redis.opsForValue()
                            .setIfAbsent("kaiwu:agent:delegation:" + jti, "1", Duration.ofSeconds(60))
                            .filter(Boolean.TRUE::equals)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                            .flatMap(claimed -> invoke(request));
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

    private Mono<byte[]> invoke(AgentRemoteRequest request) {
        return sessions.find(request.sessionId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(session -> system.post()
                        .uri("/api/internal/gateway/agent-access")
                        .header("X-Kaiwu-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "sessionId",
                                request.sessionId(),
                                "code",
                                request.code(),
                                "version",
                                request.version(),
                                "manifestHash",
                                request.manifestHash()))
                        .retrieve()
                        .bodyToMono(byte[].class)
                        .timeout(Duration.ofSeconds(3))
                        .flatMap(body -> {
                            var data = JSON.readTree(body).path("data");
                            if (!session.userId().equals(data.path("userId").asString())
                                    || !request.code().equals(data.path("code").asString())
                                    || !request.version()
                                            .equals(data.path("version").asString())
                                    || !request.manifestHash()
                                            .equals(data.path("manifestHash").asString()))
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                            String projectCode = data.path("projectCode").asString();
                            String projectId = data.path("projectId").asString();
                            int timeoutMs = data.path("timeoutMs").asInt();
                            if (projectCode == null || projectId == null || timeoutMs < 100 || timeoutMs > 10000)
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                            var access = new ProjectAccess(
                                    projectId,
                                    projectCode,
                                    JSON.convertValue(
                                            data.path("permissions"),
                                            new tools.jackson.core.type.TypeReference<List<String>>() {}),
                                    JSON.convertValue(
                                            data.path("projectRoleCodes"),
                                            new tools.jackson.core.type.TypeReference<List<String>>() {}),
                                    dataScope(data));
                            return target(projectCode).flatMap(target -> {
                                String context = issuer.issueForAgent(
                                        session.userId(),
                                        request.sessionId(),
                                        target.audience(),
                                        access,
                                        request.code(),
                                        request.version(),
                                        request.manifestHash(),
                                        AgentDelegationVerifier.digest(
                                                request.paramsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                        request.correlationId());
                                return ServiceWebClientFactory.create(
                                                target.uri().toString(), Duration.ofMillis(timeoutMs), balancer)
                                        .post()
                                        .uri("/api/internal/agent/capabilities/" + request.code() + "/invoke")
                                        .header("X-Kaiwu-Context", context)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(Map.of(
                                                "code",
                                                request.code(),
                                                "version",
                                                request.version(),
                                                "manifestHash",
                                                request.manifestHash(),
                                                "paramsJson",
                                                request.paramsJson(),
                                                "correlationId",
                                                request.correlationId()))
                                        .exchangeToMono(response ->
                                                response.statusCode().value() == 200
                                                        ? response.bodyToMono(byte[].class)
                                                        : response.releaseBody()
                                                                .then(Mono.error(new ResponseStatusException(
                                                                        HttpStatus.SERVICE_UNAVAILABLE))))
                                        .timeout(Duration.ofMillis(timeoutMs));
                            });
                        }));
    }

    /** 取 System 授权响应里的数据范围；形态非法或超限一律 403，不放行也不截断。 */
    static Map<String, Object> dataScope(tools.jackson.databind.JsonNode data) {
        try {
            return DataScopeClaim.fromNode(data.path("dataScope"));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    record Target(URI uri, String audience) {}

    /** 只读Agent委托只选default服务，不能让同项目的worker登记制造歧义或接收用户委托。 */
    Mono<Target> target(String projectCode) {
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
