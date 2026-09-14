package com.kaiwu.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.route.RouteDefinition;

class LocalRouteDirectoryLocatorTest {

    /** 生成项目交付的 docs/gateway-route-local.yml 原样放进目录就该生效，不需要人工改格式。 */
    @Test
    void loadsGeneratedProjectRouteFileAsIs(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("order-center.yml"),
                """
                # 复制到 kaiwu-gateway-service 的 local routes 下；保持显式 PROJECT 绑定。
                - id: order-center-project-local
                  uri: http://127.0.0.1:8316
                  predicates:
                    - Path=/order-center-api/**
                  filters:
                    - RewritePath=/order-center-api/(?<segment>.*), /${segment}
                  metadata:
                    accessMode: PROJECT
                    audience: kaiwu-order-center-service
                    projectCode: order-center
                """);

        List<RouteDefinition> routes = new LocalRouteDirectoryLocator(directory)
                .getRouteDefinitions()
                .collectList()
                .block();

        assertThat(routes).hasSize(1);
        RouteDefinition route = routes.getFirst();
        assertThat(route.getId()).isEqualTo("order-center-project-local");
        assertThat(route.getUri()).hasToString("http://127.0.0.1:8316");
        assertThat(route.getPredicates()).singleElement().satisfies(predicate -> assertThat(predicate.getName())
                .isEqualTo("Path"));
        assertThat(route.getFilters()).singleElement().satisfies(filter -> assertThat(filter.getName())
                .isEqualTo("RewritePath"));
        // metadata 是权限事实的一部分：accessMode 丢了会让 PROJECT 路由退化成无绑定路由。
        assertThat(route.getMetadata())
                .containsEntry("accessMode", "PROJECT")
                .containsEntry("audience", "kaiwu-order-center-service")
                .containsEntry("projectCode", "order-center");
    }

    @Test
    void ignoresMissingDirectory(@TempDir Path directory) {
        LocalRouteDirectoryLocator locator = new LocalRouteDirectoryLocator(directory.resolve("不存在"));

        assertThat(locator.getRouteDefinitions().collectList().block()).isEmpty();
    }

    /** 本地文件写坏了只跳过这一个文件：Gateway 起不来会连带挡住所有其它项目的联调。 */
    @Test
    void skipsBrokenFileWithoutFailingStartup(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("broken.yml"), "- uri: http://127.0.0.1:9999\n");
        Files.writeString(
                directory.resolve("good.yml"),
                """
                - id: good-local
                  uri: http://127.0.0.1:8317
                  predicates:
                    - Path=/good-api/**
                """);

        List<RouteDefinition> routes = new LocalRouteDirectoryLocator(directory)
                .getRouteDefinitions()
                .collectList()
                .block();

        assertThat(routes).extracting(RouteDefinition::getId).containsExactly("good-local");
    }
}
