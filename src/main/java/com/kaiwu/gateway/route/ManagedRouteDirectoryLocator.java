package com.kaiwu.gateway.route;

import java.nio.file.Path;

/**
 * 从部署者挂载的目录读取受管业务路由。
 *
 * <p>与本地目录不同，受管路由任一文件无效、权限元数据缺失或 id 重复都会使 Gateway
 * 启动失败，避免业务入口以不完整的 PROJECT 绑定上线。</p>
 */
public class ManagedRouteDirectoryLocator extends ExplicitRouteDirectoryLocator {

    public ManagedRouteDirectoryLocator(Path directory) {
        super(directory, true, "受管");
    }
}
