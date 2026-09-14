package com.kaiwu.gateway.filter;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.util.Locale;
import java.util.Map;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 所有外部 Route 必须显式声明访问模式和目标 audience。
 *
 * <p>本过滤器只校验静态路由安全元数据，认证由后续 GatewayAuthenticationFilter 执行。</p>
 */
@Component
public class RoutePolicyFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        Map<String, Object> metadata = route.getMetadata();
        String accessMode = string(metadata.get("accessMode"));
        String audience = string(metadata.get("audience"));
        if (!StringUtils.hasText(accessMode) || !StringUtils.hasText(audience)) {
            return GatewayResponses.reject(
                    exchange, HttpStatus.SERVICE_UNAVAILABLE, "路由安全元数据缺失", "gateway.routePolicyMissing");
        }

        String normalized = accessMode.toUpperCase(Locale.ROOT);
        if ("PUBLIC".equals(normalized) || "PLATFORM".equals(normalized) || "PROJECT".equals(normalized)) {
            return chain.filter(exchange);
        }
        return GatewayResponses.reject(
                exchange, HttpStatus.SERVICE_UNAVAILABLE, "未知路由访问模式", "gateway.routePolicyInvalid");
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
