package com.kaiwu.gateway.route.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import reactor.core.publisher.Flux;

/** 聚合路由的跨来源冲突必须失败关闭。 */
class AggregatedRouteValidationTest {
    /** 不同 ID、受众和路径可共存，任一重复都拒绝整个集合。 */
    @Test
    void rejectsCrossSourceConflicts() {
        var one = route("a-project", "kaiwu-a-service", "/a-api/**");
        var two = route("a.workflow-project", "kaiwu-a.workflow-service", "/a-svc/workflow/**");
        assertThat(validate(one, two).collectList().block()).hasSize(2);
        assertThatThrownBy(() -> validate(one, route("a-project", "other", "/other/**"))
                        .collectList()
                        .block())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(one, route("other", "kaiwu-a-service", "/other/**"))
                        .collectList()
                        .block())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(one, route("other", "other", "/a-api/nested/**"))
                        .collectList()
                        .block())
                .isInstanceOf(IllegalStateException.class);
        var wildcard = route("wildcard", "kaiwu-system-service", "/a-*/nested/**");
        wildcard.setMetadata(Map.of("accessMode", "PLATFORM", "audience", "kaiwu-system-service"));
        assertThatThrownBy(() -> validate(one, wildcard).collectList().block())
                .isInstanceOf(IllegalStateException.class);
        var platform = route("platform", "kaiwu-system-service", "/a-api/**");
        platform.setMetadata(Map.of("accessMode", "PLATFORM", "audience", "kaiwu-system-service"));
        assertThatThrownBy(() -> validate(one, platform).collectList().block())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(one, route("other", "other", "/a-api/**"))
                        .collectList()
                        .block())
                .isInstanceOf(IllegalStateException.class);
    }

    /** 模拟框架聚合多个来源并读取路由。 */
    private Flux<RouteDefinition> validate(RouteDefinition... routes) {
        RouteDefinitionLocator source = () -> Flux.fromIterable(List.of(routes));
        var wrapped = (RouteDefinitionLocator)
                new AggregatedRouteValidation().postProcessAfterInitialization(source, "routeDefinitionLocator");
        return wrapped.getRouteDefinitions();
    }

    /** 创建用于冲突测试的项目路由。 */
    private RouteDefinition route(String id, String audience, String path) {
        var result = new RouteDefinition();
        result.setId(id);
        result.setUri(URI.create("http://localhost"));
        result.setMetadata(Map.of("accessMode", "PROJECT", "audience", audience));
        result.getPredicates().add(new PredicateDefinition("Path=" + path));
        return result;
    }
}
