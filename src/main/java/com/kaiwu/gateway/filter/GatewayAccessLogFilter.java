package com.kaiwu.gateway.filter;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 记录 Gateway 外部入口的统一安全摘要，不读取请求体、响应体、凭据 Header 或查询参数。
 */
@Component
public final class GatewayAccessLogFilter implements GlobalFilter, Ordered {

    static final String USER_ID_ATTRIBUTE = GatewayAccessLogFilter.class.getName() + ".userId";
    static final String PROJECT_ID_ATTRIBUTE = GatewayAccessLogFilter.class.getName() + ".projectId";

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("kaiwu.http.access");
    private static final String EMPTY = "-";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\r\\n\\t]");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedAt = System.nanoTime();
        AtomicBoolean failed = new AtomicBoolean();
        return chain.filter(exchange)
                .doOnError(error -> failed.set(true))
                .doFinally(signal -> log(exchange, startedAt, failed.get()));
    }

    private static void log(ServerWebExchange exchange, long startedAt, boolean failed) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        int status = exchange.getResponse().getStatusCode() == null
                ? (failed ? 500 : 200)
                : exchange.getResponse().getStatusCode().value();
        ACCESS_LOG.info(
                "http_request service={} traceId={} method={} path={} routeId={} userId={} "
                        + "projectId={} status={} durationMs={} clientIp={}",
                "kaiwu-gateway-service",
                attribute(exchange, TraceIdFilter.TRACE_ATTRIBUTE),
                safe(exchange.getRequest().getMethod().name(), 16),
                safe(exchange.getRequest().getPath().value(), 512),
                routeId(exchange),
                attribute(exchange, USER_ID_ATTRIBUTE),
                attribute(exchange, PROJECT_ID_ATTRIBUTE),
                status,
                durationMs,
                clientIp(exchange));
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return safe(route == null ? null : route.getId(), 128);
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return safe(address == null ? null : address.getAddress().getHostAddress(), 64);
    }

    private static String attribute(ServerWebExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return safe(value == null ? null : value.toString(), 64);
    }

    private static String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        String normalized = CONTROL_CHARACTERS.matcher(value).replaceAll("_");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    @Override
    public int getOrder() {
        return -390;
    }
}
