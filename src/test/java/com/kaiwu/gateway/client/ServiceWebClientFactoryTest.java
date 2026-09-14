package com.kaiwu.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;

/**
 * 服务寻址契约（ADR 0024）。
 *
 * <p>这些用例守的是一条 spike 实测出来的坑：裸 WebClient 遇到 {@code lb://} 只会在真正发请求时
 * 抛 {@code Invalid scheme [lb]}，而入口授权是失败关闭的，症状是 PROJECT 路由 503——与内部
 * 令牌缺失完全一致。所以配置错误必须在装配期就炸掉。</p>
 */
class ServiceWebClientFactoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @SuppressWarnings("unchecked")
    private static ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancer() {
        return mock(ReactiveLoadBalancer.Factory.class);
    }

    /** Kubernetes Service DNS 与固定地址走同一条分支：交给底层网络解析，不需要 LoadBalancer。 */
    @Test
    void buildsDirectClientForHttpSchemesWithoutLoadBalancer() {
        assertThat(ServiceWebClientFactory.create("http://kaiwu-kaiwu-system:8080", TIMEOUT, null))
                .isNotNull();
        assertThat(ServiceWebClientFactory.create("https://system.internal", TIMEOUT, null))
                .isNotNull();
        assertThat(ServiceWebClientFactory.create("http://127.0.0.1:8080", TIMEOUT, null))
                .isNotNull();
    }

    /** lb:// 需要 LoadBalancer，具体背后是 Nacos 还是别的实现由运行时依赖决定。 */
    @Test
    void buildsLoadBalancedClientWhenSchemeIsLb() {
        assertThat(ServiceWebClientFactory.create("lb://kaiwu-system-service", TIMEOUT, loadBalancer()))
                .isNotNull();
    }

    /** 配了 lb:// 却没有 LoadBalancer：必须启动即失败，且错误要能自解释。 */
    @Test
    void failsFastWhenLbSchemeHasNoLoadBalancer() {
        assertThatThrownBy(() -> ServiceWebClientFactory.create("lb://kaiwu-system-service", TIMEOUT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring-cloud-starter-loadbalancer")
                .hasMessageContaining("DiscoveryClient");
    }

    /** scheme 白名单之外一律拒绝，不做「猜一个默认值」这种事。 */
    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> ServiceWebClientFactory.create("ftp://system", TIMEOUT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的服务地址 scheme");
    }

    /** 漏写 scheme 是常见笔误，错误信息要直接说需要什么前缀。 */
    @Test
    void rejectsAddressWithoutScheme() {
        assertThatThrownBy(() -> ServiceWebClientFactory.create("kaiwu-system-service:8080", TIMEOUT, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankAddress() {
        assertThatThrownBy(() -> ServiceWebClientFactory.create("  ", TIMEOUT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能为空");
    }

    /** 尾部斜杠会和 WebClient 的 uri 拼出双斜杠，装配时就该规整掉。 */
    @Test
    void normalizesTrailingSlashes() {
        assertThat(ServiceWebClientFactory.create("http://127.0.0.1:8080///", TIMEOUT, null))
                .isNotNull();
    }
}
