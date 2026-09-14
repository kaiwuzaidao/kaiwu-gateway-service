package com.kaiwu.gateway.route;

import java.nio.file.Path;

/**
 * 从本地目录读取业务项目的显式路由（只在 {@code local} profile 生效）。
 *
 * <p>解决的是一个具体的手工步骤：项目工厂生成的仓库自带
 * {@code docs/gateway-route-local.yml}，此前要求开发者把这段 YAML **人工粘贴进本仓库的
 * application-local.yml**——编辑别人的仓库、容易缩进错、多个项目并存时互相覆盖，
 * 而且改完忘了改回去会污染提交。现在把文件放进 {@code routes.local.d/} 即可。</p>
 *
 * <p>这不是服务发现：路由仍然是**逐条显式声明**的文件，只是换了个存放位置。
 * discovery locator 依旧关闭，生产环境不加载本目录（{@code local} 之外没有这个 Bean）。</p>
 *
 * <p>文件格式与 application-local.yml 里的 routes 元素完全一致，可以是单个映射或列表：</p>
 *
 * <pre>{@code
 * - id: order-center-project-local
 *   uri: http://127.0.0.1:8316
 *   predicates:
 *     - Path=/order-center-api/**
 *   filters:
 *     - RewritePath=/order-center-api/(?<segment>.*), /${segment}
 *   metadata:
 *     accessMode: PROJECT
 *     audience: kaiwu-order-center-service
 *     projectCode: order-center
 * }</pre>
 */
public class LocalRouteDirectoryLocator extends ExplicitRouteDirectoryLocator {

    public LocalRouteDirectoryLocator(Path directory) {
        super(directory, false, "本地");
    }
}
