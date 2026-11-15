# Kaiwu Gateway Service

Kaiwu 平台的**唯一外部 API 入口**。所有来自浏览器和外部客户端的请求都从这里进入，
经过身份、会话和项目成员校验后，换成一枚短期、audience-bound 的内部 Context 再转发给后端服务。

> 这是 [Kaiwu](https://github.com/kaiwuzaidao/kaiwu) 的组成部分之一，不单独使用。
> 想先看平台整体，请从入口仓开始；那里有一条 `./kaiwu up` 的本地全链路。

## 它负责什么

- 验证外部 Access JWT（RS256）与 Redis 中的在线会话
- 按 Route 声明的访问模式决定放行策略
- 签发 60 秒有效、绑定目标服务 audience 的内部 Context
- 清除客户端伪造的身份类请求头

## 它不负责什么

这条边界是有意画死的，越界会让平台出现第二套权限事实：

- **不维护任何权限数据**。权限的唯一事实源是 System 的 `sys_project_*`。
- **不查询平台库或业务库**。Gateway 没有数据库连接。
- **不执行业务资源规则**。资源归属与状态授权由各业务服务用 Starter 本地判断。

## 三种访问模式

每条 Route 必须在 `metadata` 里声明 `accessMode` 和唯一 `audience`：

| 模式 | 校验 | 用于 |
|---|---|---|
| `PUBLIC` | 不校验身份 | 登录、刷新、健康检查 |
| `PLATFORM` | Access JWT + 在线会话 | 平台控制面接口 |
| `PROJECT` | 再加一次项目成员校验 | 业务项目接口 |

`PROJECT` 会向 System 查询项目入口授权快照（ADR 0022），结果带有界缓存。
**拿不到权威结论时失败关闭返回 503，绝不放行**——入口授权是安全判断，
"暂时看不清就先让过"是错的。

## 硬约束

- `discovery.locator.enabled=false`。路由必须逐条显式声明；自动把注册表里的服务
  变成路由，等于把未登记的服务直接暴露到公网入口。
- `/internal` 任意路径段永不对外。
- 客户端提交的 Context、用户、角色、权限头一律删除。
- Gateway 只持有 Access JWT 公钥与 Context 私钥，不持有签发用户令牌的能力。
- 本地业务路由从 `routes.local.d/` 读取，部署路由需显式配置
  `kaiwu.gateway.managed-routes-dir`。受管路由格式错误、id 重复或 PROJECT 元数据不全时
  **启动失败**。这两者都不是 discovery locator。

## 服务寻址

`KAIWU_SYSTEM_SERVICE_URI` 的 **scheme 决定由谁解析地址**（ADR 0024），没有部署模式开关：

```
http://127.0.0.1:8080          本地固定地址
http://kaiwu-system:8080       Kubernetes，交给 CoreDNS 与 Service
lb://kaiwu-system-service      交给 Spring Cloud LoadBalancer
```

用 `lb://` 需自行添加 loadbalancer 与一个 DiscoveryClient 实现，缺依赖时**启动即失败**
并打印所需依赖名。注意 `lb://` 是显式路由的地址写法，与被禁止的 discovery locator 不是一回事。

## 本地运行

Gateway 依赖 Redis 与 System。首次运行请在入口仓执行 `./kaiwu up`，它会准备完整链路。

开发本仓需要 JDK 21 和 Maven 3.9+。将 `JAVA_HOME` 指向本机 JDK 21 的安装目录后，在本仓根目录执行：

```bash
./scripts/verify.sh
```

脚本会检查 Java 和 Maven 实际使用的 JDK 版本，再运行完整 Maven 校验。

## 参与开发

改动前请读 [AGENTS.md](AGENTS.md)、[CLAUDE.md](CLAUDE.md) 与 System 仓的[架构决策](https://github.com/kaiwuzaidao/kaiwu-system-service/tree/v0.2.0/docs/adr)，重点查看 ADR 0001、0003、0022、0024。
Pull Request 请说明变更、风险与可直接执行的验证步骤。

## License

本项目基于 [Apache License 2.0](LICENSE) 开源。

## 项目多服务路由

默认服务使用 `/{project}-api/**`。其他服务使用 `/{project}-svc/{service}/**`，必须声明
`serviceCode`，并使用固定的 route ID `{project}.{service}-project` 与 audience
`kaiwu-{project}.{service}-service`。非默认服务只接受规范原点 URI 和精确的 `RewritePath`。

旧默认片段缺少 `serviceCode` 时按 `default` 处理，保留原上游路径和过滤器兼容性。
项目编码最长 64 个字符。配置 `kaiwu.gateway.managed-routes-dir` 后，Gateway 检查所有来源的
路由 ID、audience 和路径冲突，无法证明互不重叠的模板路径也会被拒绝。

Agent 读写委托从默认服务中选择唯一目标。在 System 登记后端服务后，还需发布其路由片段。
停用或回退非默认服务时，先停止相关调用并撤销对应片段。新旧副本混用时，保持多服务开关关闭。升级次序见 [System 发行手册](https://github.com/kaiwuzaidao/kaiwu-system-service/blob/v0.2.0/docs/UPGRADING.md#平台组件的升级顺序)。
