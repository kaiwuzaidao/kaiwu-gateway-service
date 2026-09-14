package com.kaiwu.gateway.filter;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

final class GatewayResponses {

    private GatewayResponses() {}

    static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message, String messageKey) {
        byte[] bytes = ("{\"code\":" + status.value()
                        + ",\"message\":\"" + message
                        + "\",\"messageKey\":\"" + messageKey + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
