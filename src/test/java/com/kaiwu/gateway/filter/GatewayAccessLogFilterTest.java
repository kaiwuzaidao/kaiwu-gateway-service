package com.kaiwu.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class GatewayAccessLogFilterTest {

    @Test
    void logsSafeRouteSummaryWithoutQueryOrCredentialHeaders(CapturedOutput output) {
        TraceIdFilter traceFilter = new TraceIdFilter();
        GatewayAccessLogFilter accessFilter = new GatewayAccessLogFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login?token=query-secret")
                        .header("Authorization", "Bearer access-secret")
                        .header("Cookie", "refresh=refresh-secret")
                        .header("X-Kaiwu-Context", "context-secret"));
        Route route = Route.async()
                .id("system-public-auth")
                .uri(URI.create("http://localhost"))
                .predicate(value -> true)
                .metadata(Map.of("accessMode", "PUBLIC", "audience", "kaiwu-system-service"))
                .build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        StepVerifier.create(traceFilter.filter(
                        exchange,
                        traced -> accessFilter.filter(traced, current -> {
                            current.getAttributes().put(GatewayAccessLogFilter.USER_ID_ATTRIBUTE, "10001");
                            current.getResponse().setStatusCode(HttpStatus.CREATED);
                            return current.getResponse().setComplete();
                        })))
                .verifyComplete();

        String traceId = exchange.getResponse().getHeaders().getFirst(TraceIdFilter.TRACE_HEADER);
        assertThat(traceId).matches("[a-f0-9]{32}");
        assertThat(exchange.getResponse().getHeaders().getValuesAsList(TraceIdFilter.TRACE_HEADER))
                .containsExactly(traceId);
        assertThat(output)
                .contains(
                        "http_request service=kaiwu-gateway-service traceId=" + traceId,
                        "method=POST path=/api/auth/login routeId=system-public-auth userId=10001",
                        "status=201");
        assertThat(output).doesNotContain("query-secret", "access-secret", "refresh-secret", "context-secret");
    }

    @Test
    void recordsServerErrorWhenReactiveChainFails(CapturedOutput output) {
        GatewayAccessLogFilter accessFilter = new GatewayAccessLogFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/projects/1"));

        StepVerifier.create(accessFilter.filter(
                        exchange, current -> Mono.error(new IllegalStateException("downstream failure"))))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(output).contains("path=/api/projects/1", "status=500");
    }
}
