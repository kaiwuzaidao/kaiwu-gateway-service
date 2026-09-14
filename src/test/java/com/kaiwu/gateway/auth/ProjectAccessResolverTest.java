package com.kaiwu.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 入口授权解析的三种结果：有权、明确无权、拿不到结论。
 *
 * <p>用 {@link ExchangeFunction} 打桩而不是起 MockWebServer：不引入新的测试依赖，
 * 也就不给供应链扫描增加面积；这里要断言的是解析和缓存逻辑，不是 HTTP 传输本身。</p>
 */
class ProjectAccessResolverTest {

    private final List<ClientRequest> requests = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    private ProjectAccessResolver resolverReturning(ClientResponse... responses) {
        ProjectAccessProperties properties = new ProjectAccessProperties();
        properties.setSystemBaseUrl("http://system.invalid");
        properties.setInternalToken("internal-secret");
        ExchangeFunction exchange = request -> {
            requests.add(request);
            int index = Math.min(calls.getAndIncrement(), responses.length - 1);
            return Mono.just(responses[index]);
        };
        return new ProjectAccessResolver(
                WebClient.builder()
                        .baseUrl(properties.getSystemBaseUrl())
                        .exchangeFunction(exchange)
                        .build(),
                properties);
    }

    @Test
    void returnsProjectFactsAndSendsInternalTokenOnly() {
        ProjectAccessResolver resolver = resolverReturning(
                json(
                        """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"order-center",
                "permissions":["order:order:list"],"projectRoleCodes":["project-admin"]}}"""));

        ProjectAccess access = resolver.resolve("order-center", "10001").block();

        assertThat(access).isNotNull();
        assertThat(access.projectId()).isEqualTo("9000000000000001001");
        assertThat(access.permissions()).containsExactly("order:order:list");
        assertThat(access.projectRoleCodes()).containsExactly("project-admin");

        ClientRequest request = requests.getFirst();
        assertThat(request.headers().getFirst("X-Kaiwu-Internal-Token")).isEqualTo("internal-secret");
        // 外部凭据绝不能被转发进内部接口。
        assertThat(request.headers().getFirst("Authorization")).isNull();
        assertThat(request.url().getQuery()).contains("projectCode=order-center", "userId=10001");
    }

    /** 明确无权也要缓存：否则不断重试的越权调用方等于拿鉴权失败当 DDoS。 */
    @Test
    void cachesDeniedDecisionSoRetriesDoNotHitSystem() {
        ProjectAccessResolver resolver = resolverReturning(status(HttpStatus.FORBIDDEN));

        assertThat(resolver.resolve("order-center", "10001").block()).isNull();
        assertThat(resolver.resolve("order-center", "10001").block()).isNull();

        assertThat(calls.get()).isEqualTo(1);
    }

    /** System 不可用必须失败关闭：不能放行，也不能降级成「进得去但没权限」。 */
    @Test
    void failsClosedWhenSystemIsUnavailable() {
        ProjectAccessResolver resolver = resolverReturning(status(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> resolver.resolve("order-center", "10001").block())
                .isInstanceOf(ProjectAccessResolver.ProjectAccessUnavailableException.class);
    }

    /** 返回的项目与 Route 绑定的项目不一致，等于换了个项目放行，必须拒绝。 */
    @Test
    void rejectsResponseForADifferentProject() {
        ProjectAccessResolver resolver = resolverReturning(
                json(
                        """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"other-project",
                "permissions":[],"projectRoleCodes":[]}}"""));

        assertThatThrownBy(() -> resolver.resolve("order-center", "10001").block())
                .isInstanceOf(ProjectAccessResolver.ProjectAccessUnavailableException.class);
    }

    @Test
    void cachesGrantedDecision() {
        ProjectAccessResolver resolver = resolverReturning(
                json(
                        """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"order-center",
                "permissions":[],"projectRoleCodes":[]}}"""));

        assertThat(resolver.resolve("order-center", "10001").block()).isNotNull();
        assertThat(resolver.resolve("order-center", "10001").block()).isNotNull();

        assertThat(calls.get()).isEqualTo(1);
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ClientResponse status(HttpStatus status) {
        return ClientResponse.create(status).build();
    }
}
