package com.kaiwu.gateway.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 按 URI scheme 装配出站 {@link WebClient}（ADR 0024）。
 *
 * <p>Kaiwu 不引入部署模式开关，寻址能力由 scheme 自己表达：</p>
 * <ul>
 *   <li>{@code http(s)://} —— 地址可由底层网络直接解析，平台不参与应用层服务发现。
 *       固定地址、内网 DNS 与 Kubernetes Service DNS 都走这一条。</li>
 *   <li>{@code lb://} —— 目标是逻辑服务名，交给 Spring Cloud LoadBalancer 解析。
 *       背后是 Nacos 还是 Spring Cloud Kubernetes 由运行时依赖决定，本类不关心。</li>
 * </ul>
 *
 * <p><b>这里是 Gateway 唯一允许判断 scheme 的地方。</b>装配完成后调用方只持有一个已经可用的
 * 客户端，不再有 {@code if (lb) else} 散落到各处——那与部署模式开关等价，同样是双轨逻辑。</p>
 *
 * <p>配置 {@code lb://} 却缺少 LoadBalancer 时<b>启动即失败</b>。裸 {@link WebClient} 遇到
 * {@code lb} scheme 只会在真正发请求时抛 {@code Invalid scheme [lb]}，而 Gateway 的入口授权
 * 是失败关闭的，表现出来是 PROJECT 路由 503——与内部令牌缺失的症状完全一致，足以让排查
 * 跑偏。宁可起不来。</p>
 */
public final class ServiceWebClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ServiceWebClientFactory.class);

    private static final String LOAD_BALANCED_SCHEME = "lb";
    private static final List<String> DIRECT_SCHEMES = List.of("http", "https");

    private ServiceWebClientFactory() {}

    /**
     * 依据 {@code baseUrl} 的 scheme 装配客户端。
     *
     * @param baseUrl            目标服务地址，{@code http(s)://host:port} 或 {@code lb://service-name}
     * @param responseTimeout    响应超时
     * @param loadBalancerFactory LoadBalancer 工厂，可为 {@code null}；仅 {@code lb://} 需要
     * @return 已装配好的客户端
     * @throws IllegalStateException scheme 不被支持，或 {@code lb://} 缺少 LoadBalancer
     */
    public static WebClient create(
            String baseUrl,
            Duration responseTimeout,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
        String normalized = normalize(baseUrl);
        String scheme = schemeOf(normalized);

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(normalized)
                .clientConnector(
                        new ReactorClientHttpConnector(HttpClient.create().responseTimeout(responseTimeout)));

        if (LOAD_BALANCED_SCHEME.equals(scheme)) {
            if (loadBalancerFactory == null) {
                throw new IllegalStateException("配置了 " + LOAD_BALANCED_SCHEME + ":// 但当前没有 Spring Cloud LoadBalancer："
                        + "请添加 spring-cloud-starter-loadbalancer 以及一个 DiscoveryClient 实现"
                        + "（如 spring-cloud-starter-alibaba-nacos-discovery），"
                        + "或把地址改为 http(s):// 形式");
            }
            builder.filter(new ReactorLoadBalancerExchangeFilterFunction(loadBalancerFactory, List.of()));
            // 只记录服务名，不记录完整地址：逻辑名不含凭据，但保持与直连分支一致的最小披露。
            log.info("出站客户端采用 LoadBalancer 解析：service={}", hostOf(normalized));
            return builder.build();
        }

        if (!DIRECT_SCHEMES.contains(scheme)) {
            throw new IllegalStateException("不支持的服务地址 scheme：" + scheme + "；只接受 http、https 或 " + LOAD_BALANCED_SCHEME);
        }
        log.info("出站客户端采用网络直接解析：host={}", hostOf(normalized));
        return builder.build();
    }

    /**
     * 去掉尾部斜杠，避免与 WebClient 的 uri 拼接出双斜杠；但保留 {@code scheme://} 的双斜杠，
     * 否则 {@code lb://} 会被规整成 {@code lb:}，错误信息会指向 URI 语法而不是真正的原因。
     */
    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("服务地址不能为空");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/") && !normalized.endsWith("://")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String schemeOf(String baseUrl) {
        String scheme = uriOf(baseUrl).getScheme();
        if (scheme == null) {
            throw new IllegalStateException("服务地址缺少 scheme：" + baseUrl + "；需要 http://、https:// 或 lb:// 前缀");
        }
        return scheme.toLowerCase(Locale.ROOT);
    }

    private static String hostOf(String baseUrl) {
        String host = uriOf(baseUrl).getHost();
        return host == null ? baseUrl : host;
    }

    private static URI uriOf(String baseUrl) {
        try {
            return new URI(baseUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("服务地址不是合法 URI：" + baseUrl, exception);
        }
    }
}
