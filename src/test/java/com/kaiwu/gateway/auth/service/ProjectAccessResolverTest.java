package com.kaiwu.gateway.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.gateway.auth.config.ProjectAccessProperties;
import com.kaiwu.gateway.auth.model.ProjectAccess;
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
        return resolverReturning(new ProjectAccessProperties(), responses);
    }

    /** 用指定配置构造解析器，System 响应按顺序取自 responses。 */
    private ProjectAccessResolver resolverReturning(ProjectAccessProperties properties, ClientResponse... responses) {
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

    private static final String SCOPE =
            """
            {"v":1,"primaryDeptId":"101","depts":["101","102"],"rules":{"crm:customer":{"set":[0,1]}},"fp":"0123456789abcdef"}""";

    /** dataScope 原样透传，键序不变，Gateway 不解释其内容。 */
    @Test
    void passesDataScopeThroughUnchangedAndKeepsOldShapeWithout() {
        ProjectAccessResolver with = resolverReturning(json(
                """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"order-center",
                "permissions":[],"projectRoleCodes":[],"dataScope":%s}}"""
                        .formatted(SCOPE)));
        ProjectAccess access = with.resolve("order-center", "10001").block();
        assertThat(access.dataScope()).isNotNull();
        assertThat(access.dataScope().keySet()).containsExactly("v", "primaryDeptId", "depts", "rules", "fp");
        assertThat(access.dataScope()).containsEntry("fp", "0123456789abcdef");

        ProjectAccessResolver without = resolverReturning(
                json(
                        """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"order-center",
                "permissions":[],"projectRoleCodes":[]}}"""));
        assertThat(without.resolve("order-center", "10002").block().dataScope()).isNull();
    }

    /** dataScope 存在但不是对象：看不清就失败关闭，不当作「没有范围」放行。 */
    @Test
    void failsClosedWhenDataScopeIsNotAnObject() {
        ProjectAccessResolver resolver = resolverReturning(
                json(
                        """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"order-center",
                "permissions":[],"projectRoleCodes":[],"dataScope":"oops"}}"""));
        assertThatThrownBy(() -> resolver.resolve("order-center", "10001").block())
                .isInstanceOf(ProjectAccessResolver.ProjectAccessUnavailableException.class);
    }

    /** System 理应已拒绝超限范围；万一仍返回，Gateway 也按拒绝处理，不截断、不放行。 */
    @Test
    void deniesOversizedDataScopeEvenIfSystemReturnedIt() {
        String big = "{\"v\":1,\"depts\":[" + "\"1234567890123456789\",".repeat(400)
                + "\"1\"],\"rules\":{},\"fp\":\"0123456789abcdef\"}";
        ProjectAccessResolver resolver = resolverReturning(json(
                """
                {"code":0,"data":{"projectId":"9000000000000001001","projectCode":"order-center",
                "permissions":[],"projectRoleCodes":[],"dataScope":%s}}"""
                        .formatted(big)));
        assertThat(resolver.resolve("order-center", "10001").block()).isNull();
    }

    /** DATA_SCOPE_TOO_LARGE：拒绝，缓存时间用较短的 TTL，日志不含部门 ID。 */
    @Test
    void dataScopeTooLargeIsDeniedCachedBrieflyAndLoggedWithoutDepartmentIds() throws Exception {
        ProjectAccessProperties properties = new ProjectAccessProperties();
        properties.setDataScopeDenyCacheTtlSeconds(1);
        ClientResponse tooLarge = ClientResponse.create(HttpStatus.FORBIDDEN)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(
                        """
                {"code":403,"message":"x","messageKey":"api.project.dataScopeTooLarge",
                "messageArgs":{"reason":"DATA_SCOPE_TOO_LARGE","limitBytes":6144},"data":null}""")
                .build();
        ProjectAccessResolver resolver = resolverReturning(properties, tooLarge);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProjectAccessResolver.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(resolver.resolve("order-center", "10001").block()).isNull();
            assertThat(resolver.resolve("order-center", "10001").block()).isNull();
            assertThat(calls.get()).isEqualTo(1);
            Thread.sleep(1300);
            assertThat(resolver.resolve("order-center", "10001").block()).isNull();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("DATA_SCOPE_TOO_LARGE", "order-center")
                .doesNotContain("depts");
    }

    /** 普通的「不是成员」拒绝仍按原缓存时间保留，不被缩短。 */
    @Test
    void ordinaryDenialKeepsTheNormalCacheTtl() throws Exception {
        ProjectAccessProperties properties = new ProjectAccessProperties();
        properties.setDataScopeDenyCacheTtlSeconds(1);
        ProjectAccessResolver resolver = resolverReturning(properties, status(HttpStatus.FORBIDDEN));
        assertThat(resolver.resolve("order-center", "10001").block()).isNull();
        Thread.sleep(1300);
        assertThat(resolver.resolve("order-center", "10001").block()).isNull();
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
