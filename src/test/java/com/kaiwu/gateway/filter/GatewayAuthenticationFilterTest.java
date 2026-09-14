package com.kaiwu.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.kaiwu.gateway.auth.AccessPrincipal;
import com.kaiwu.gateway.auth.ExternalAccessTokenVerifier;
import com.kaiwu.gateway.auth.GatewayContextIssuer;
import com.kaiwu.gateway.auth.ProjectAccess;
import com.kaiwu.gateway.auth.ProjectAccessResolver;
import com.kaiwu.gateway.auth.SessionSnapshot;
import com.kaiwu.gateway.auth.SessionSnapshotRepository;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayAuthenticationFilterTest {

    private final ExternalAccessTokenVerifier tokenVerifier = mock(ExternalAccessTokenVerifier.class);
    private final SessionSnapshotRepository sessionRepository = mock(SessionSnapshotRepository.class);
    private final GatewayContextIssuer contextIssuer = mock(GatewayContextIssuer.class);
    private final ProjectAccessResolver resolver = mock(ProjectAccessResolver.class);

    private final AccessPrincipal principal = new AccessPrincipal("1", "session-1", "admin");
    private final SessionSnapshot snapshot = new SessionSnapshot("1", "admin", Set.of("system:platform:read"), "v1");

    @Test
    void forwardsAuthenticatedRequestWithoutFallingIntoEmptySessionBranch() {
        authenticated();
        when(contextIssuer.issue(principal, snapshot, "kaiwu-system-service")).thenReturn("signed-context");
        MockServerWebExchange exchange = platformExchange();
        AtomicReference<String> forwardedContext = new AtomicReference<>();

        StepVerifier.create(filter(null).filter(exchange, captureContext(forwardedContext)))
                .verifyComplete();

        assertThat(forwardedContext.get()).isEqualTo("signed-context");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /** PROJECT 路由：是成员时签发绑定该项目的 Context，并清掉客户端提交的项目标识。 */
    @Test
    void issuesProjectBoundContextForMembers() {
        authenticated();
        ProjectAccess access = new ProjectAccess(
                "9000000000000001001", "order-center", List.of("order:order:list"), List.of("project-admin"));
        when(resolver.resolve("order-center", "1")).thenReturn(Mono.just(access));
        when(contextIssuer.issueForProject(principal, snapshot, "kaiwu-order-center-service", access))
                .thenReturn("project-context");
        MockServerWebExchange exchange = projectExchange("9000000000000001001");
        AtomicReference<String> forwardedContext = new AtomicReference<>();
        AtomicReference<String> forwardedProjectHeader = new AtomicReference<>();
        GatewayFilterChain chain = forwarded -> {
            forwardedContext.set(forwarded.getRequest().getHeaders().getFirst("X-Kaiwu-Context"));
            forwardedProjectHeader.set(forwarded.getRequest().getHeaders().getFirst("X-Project-Id"));
            return Mono.empty();
        };

        StepVerifier.create(filter(resolver).filter(exchange, chain)).verifyComplete();

        assertThat(forwardedContext.get()).isEqualTo("project-context");
        // 客户端提交的项目标识不得透传给业务服务，它只用于与权威 ID 比对。
        assertThat(forwardedProjectHeader.get()).isNull();
        // 转发成功后不得再写响应：Mono<Void> 正常完成也是空的，
        // 用 switchIfEmpty 兜底会让成功请求事后被写一次 403。
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /** 不是项目成员：403，且请求不得到达下游。 */
    @Test
    void rejectsNonMemberWithForbidden() {
        authenticated();
        when(resolver.resolve("order-center", "1")).thenReturn(Mono.empty());

        assertRejected(projectExchange(null), HttpStatus.FORBIDDEN);
    }

    /** 客户端提交的 X-Project-Id 与权威 ID 不一致：403，不能让它成为事实来源。 */
    @Test
    void rejectsMismatchedClientProjectId() {
        authenticated();
        when(resolver.resolve("order-center", "1"))
                .thenReturn(Mono.just(new ProjectAccess("9000000000000001001", "order-center", List.of(), List.of())));

        assertRejected(projectExchange("9000000000000009999"), HttpStatus.FORBIDDEN);
    }

    /** System 拿不到结论：503 失败关闭，绝不放行。 */
    @Test
    void failsClosedWhenProjectAccessIsUnavailable() {
        authenticated();
        when(resolver.resolve("order-center", "1"))
                .thenReturn(Mono.error(
                        new ProjectAccessResolver.ProjectAccessUnavailableException(new RuntimeException())));

        assertRejected(projectExchange(null), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** 未配置入口授权时 PROJECT 请求必须 503，而不是退化成放行。 */
    @Test
    void failsClosedWhenResolverIsNotConfigured() {
        authenticated();

        assertRejected(projectExchange(null), HttpStatus.SERVICE_UNAVAILABLE, null);
    }

    /** PROJECT 路由没绑定 projectCode 属于配置错误，同样不能放行。 */
    @Test
    void failsClosedWhenRouteHasNoProjectBinding() {
        authenticated();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/order-center-api/api/order")
                        .header("Authorization", "Bearer access-token"));
        exchange.getAttributes()
                .put(
                        GATEWAY_ROUTE_ATTR,
                        Route.async()
                                .id("order-center-project")
                                .uri(URI.create("http://localhost"))
                                .predicate(value -> true)
                                .metadata(Map.of(
                                        "accessMode", "PROJECT",
                                        "audience", "kaiwu-order-center-service"))
                                .build());

        assertRejected(exchange, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void authenticated() {
        when(tokenVerifier.verify("access-token")).thenReturn(principal);
        when(sessionRepository.find("session-1")).thenReturn(Mono.just(snapshot));
    }

    private void assertRejected(MockServerWebExchange exchange, HttpStatus expected) {
        assertRejected(exchange, expected, resolver);
    }

    private void assertRejected(MockServerWebExchange exchange, HttpStatus expected, ProjectAccessResolver available) {
        AtomicBoolean reachedDownstream = new AtomicBoolean();
        GatewayFilterChain chain = forwarded -> {
            reachedDownstream.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter(available).filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expected);
        assertThat(reachedDownstream).isFalse();
    }

    private GatewayFilterChain captureContext(AtomicReference<String> holder) {
        return forwarded -> {
            holder.set(forwarded.getRequest().getHeaders().getFirst("X-Kaiwu-Context"));
            return Mono.empty();
        };
    }

    @SuppressWarnings("unchecked")
    private GatewayAuthenticationFilter filter(ProjectAccessResolver available) {
        ObjectProvider<ProjectAccessResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new GatewayAuthenticationFilter(tokenVerifier, sessionRepository, contextIssuer, provider);
    }

    private static MockServerWebExchange platformExchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/probe").header("Authorization", "Bearer access-token"));
        exchange.getAttributes()
                .put(
                        GATEWAY_ROUTE_ATTR,
                        Route.async()
                                .id("system-platform")
                                .uri(URI.create("http://localhost"))
                                .predicate(value -> true)
                                .metadata(Map.of(
                                        "accessMode", "PLATFORM",
                                        "audience", "kaiwu-system-service"))
                                .build());
        return exchange;
    }

    private static MockServerWebExchange projectExchange(String declaredProjectId) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest.get("/order-center-api/api/order").header("Authorization", "Bearer access-token");
        if (declaredProjectId != null) {
            builder = builder.header("X-Project-Id", declaredProjectId);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from((MockServerHttpRequest.BodyBuilder) builder);
        exchange.getAttributes()
                .put(
                        GATEWAY_ROUTE_ATTR,
                        Route.async()
                                .id("order-center-project")
                                .uri(URI.create("http://localhost"))
                                .predicate(value -> true)
                                .metadata(Map.of(
                                        "accessMode", "PROJECT",
                                        "audience", "kaiwu-order-center-service",
                                        "projectCode", "order-center"))
                                .build());
        return exchange;
    }
}
