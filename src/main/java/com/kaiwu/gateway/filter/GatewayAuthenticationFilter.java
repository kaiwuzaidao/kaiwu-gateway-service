package com.kaiwu.gateway.filter;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.kaiwu.gateway.auth.AccessPrincipal;
import com.kaiwu.gateway.auth.ExternalAccessTokenVerifier;
import com.kaiwu.gateway.auth.GatewayContextIssuer;
import com.kaiwu.gateway.auth.ProjectAccess;
import com.kaiwu.gateway.auth.ProjectAccessResolver;
import com.kaiwu.gateway.auth.SessionSnapshot;
import com.kaiwu.gateway.auth.SessionSnapshotRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 统一验证外部 JWT、Redis 会话并注入签名内部 Context。
 */
@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String CONTEXT_HEADER = "X-Kaiwu-Context";
    private static final String PROJECT_ID_HEADER = "X-Project-Id";

    private final ExternalAccessTokenVerifier tokenVerifier;
    private final SessionSnapshotRepository sessionRepository;
    private final GatewayContextIssuer contextIssuer;
    private final ObjectProvider<ProjectAccessResolver> projectAccessResolver;

    public GatewayAuthenticationFilter(
            ExternalAccessTokenVerifier tokenVerifier,
            SessionSnapshotRepository sessionRepository,
            GatewayContextIssuer contextIssuer,
            ObjectProvider<ProjectAccessResolver> projectAccessResolver) {
        this.tokenVerifier = tokenVerifier;
        this.sessionRepository = sessionRepository;
        this.contextIssuer = contextIssuer;
        this.projectAccessResolver = projectAccessResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        String accessMode = text(route.getMetadata().get("accessMode")).toUpperCase(Locale.ROOT);
        if ("PUBLIC".equals(accessMode)) {
            return chain.filter(exchange);
        }
        String bearer = bearerToken(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (bearer == null) {
            return GatewayResponses.reject(
                    exchange, HttpStatus.UNAUTHORIZED, "未登录或登录已过期", "gateway.authenticationRequired");
        }

        AccessPrincipal principal;
        try {
            principal = tokenVerifier.verify(bearer);
            exchange.getAttributes().put(GatewayAccessLogFilter.USER_ID_ATTRIBUTE, principal.userId());
        } catch (IllegalArgumentException exception) {
            return GatewayResponses.reject(
                    exchange, HttpStatus.UNAUTHORIZED, "未登录或登录已过期", "gateway.authenticationRequired");
        }

        String audience = text(route.getMetadata().get("audience"));
        String projectCode = text(route.getMetadata().get("projectCode"));
        boolean projectRoute = "PROJECT".equals(accessMode);
        if (projectRoute && !StringUtils.hasText(projectCode)) {
            // PROJECT 路由没绑定项目编码就无从校验成员，属于配置错误，不能放行。
            return GatewayResponses.reject(
                    exchange, HttpStatus.SERVICE_UNAVAILABLE, "路由缺少项目绑定", "gateway.routeProjectMissing");
        }

        return sessionRepository
                .find(principal.sessionId())
                .filter(snapshot -> principal.userId().equals(snapshot.userId()))
                .switchIfEmpty(Mono.error(new MissingSessionException()))
                .flatMap(snapshot -> projectRoute
                        ? forwardProjectRequest(exchange, chain, principal, snapshot, audience, projectCode)
                        : forwardPlatformRequest(exchange, chain, principal, snapshot, audience))
                .onErrorResume(
                        MissingSessionException.class,
                        exception -> GatewayResponses.reject(
                                exchange, HttpStatus.UNAUTHORIZED, "会话已失效，请重新登录", "gateway.sessionExpired"))
                .onErrorResume(exception -> exchange.getResponse().isCommitted()
                        ? Mono.error(exception)
                        : GatewayResponses.reject(
                                exchange,
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "会话校验暂不可用",
                                "gateway.sessionVerificationUnavailable"));
    }

    /** PLATFORM/PUBLIC 之外的既有路径：Context 不携带项目事实。 */
    private Mono<Void> forwardPlatformRequest(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            AccessPrincipal principal,
            SessionSnapshot snapshot,
            String audience) {
        return chain.filter(withContext(exchange, contextIssuer.issue(principal, snapshot, audience)));
    }

    /**
     * PROJECT 路由：先确认这个人是这个项目的 ACTIVE 成员，再签发绑定该项目的 Context。
     *
     * <p>三条边界：解析器未装配（配置缺失）→ 503；明确不是成员 → 403；
     * 拿不到结论（System 不可达）→ 503 失败关闭，绝不放行。</p>
     */
    private Mono<Void> forwardProjectRequest(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            AccessPrincipal principal,
            SessionSnapshot snapshot,
            String audience,
            String projectCode) {
        ProjectAccessResolver resolver = projectAccessResolver.getIfAvailable();
        if (resolver == null) {
            return GatewayResponses.reject(
                    exchange, HttpStatus.SERVICE_UNAVAILABLE, "项目鉴权未配置", "gateway.projectAuthUnavailable");
        }
        String declaredProjectId = exchange.getRequest().getHeaders().getFirst(PROJECT_ID_HEADER);
        // 先把「有没有授权」收敛成 Optional 再决定放行还是拒绝。
        // 不能直接对整条链用 switchIfEmpty：chain.filter 返回的 Mono<Void> 正常完成时也是空的，
        // 那会让每个**转发成功**的请求在事后再被写一次 403。
        return resolver.resolve(projectCode, principal.userId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(resolved -> {
                    if (resolved.isEmpty()) {
                        return GatewayResponses.reject(
                                exchange, HttpStatus.FORBIDDEN, "无权访问该项目", "gateway.projectAccessDenied");
                    }
                    ProjectAccess access = resolved.get();
                    // 客户端提交的项目 ID 只能与权威 ID 比对，永远不能成为事实来源。
                    if (StringUtils.hasText(declaredProjectId) && !declaredProjectId.equals(access.projectId())) {
                        return GatewayResponses.reject(
                                exchange, HttpStatus.FORBIDDEN, "项目标识与授权不一致", "gateway.projectMismatch");
                    }
                    exchange.getAttributes().put(GatewayAccessLogFilter.PROJECT_ID_ATTRIBUTE, access.projectId());
                    return chain.filter(withContext(
                            exchange, contextIssuer.issueForProject(principal, snapshot, audience, access)));
                })
                .onErrorResume(
                        ProjectAccessResolver.ProjectAccessUnavailableException.class,
                        exception -> GatewayResponses.reject(
                                exchange,
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "项目鉴权暂不可用",
                                "gateway.projectAuthUnavailable"));
    }

    /** 换上内部 Context，并清掉外部凭据与客户端提交的项目标识。 */
    private static ServerWebExchange withContext(ServerWebExchange exchange, String context) {
        return exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove(PROJECT_ID_HEADER);
                    headers.set(CONTEXT_HEADER, context);
                }))
                .build();
    }

    private static String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private static final class MissingSessionException extends RuntimeException {}
}
