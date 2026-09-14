package com.kaiwu.gateway.readiness;

import java.util.Map;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Gateway 至少加载一条显式 Route 才可接流量。
 */
@RestController
public class GatewayReadinessController {

    private final RouteLocator routeLocator;

    public GatewayReadinessController(RouteLocator routeLocator) {
        this.routeLocator = routeLocator;
    }

    @GetMapping("/ready")
    public Mono<ResponseEntity<Map<String, Object>>> ready() {
        return routeLocator.getRoutes().hasElements().map(ready -> ResponseEntity.status(
                        ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("ready", ready)));
    }
}
