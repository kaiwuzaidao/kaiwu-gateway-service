# Kaiwu Gateway Service 开发约定

先读工作区 ADR 0001、ADR 0003 和 `docs/ARCHITECTURE.md`。

- Gateway 负责外部认证、会话和项目入口策略，不维护第二套权限库。
- 禁止查询平台库或业务库，禁止执行业务资源规则。
- 禁止启用 discovery locator。
- PLATFORM 与 PROJECT 均已启用 Gateway 鉴权（ADR 0022）。PROJECT 走 System 的项目入口授权快照，
  有界缓存 + 失败关闭：拿不到结论一律 503，绝不放行。未配置 `kaiwu.gateway.project-access` 时同样 503。
- 本地联调业务项目：把生成仓库的 `docs/gateway-route-local.yml` 放进 `routes.local.d/`
  （只在 `local` profile 加载，目录不进版本库）。路由仍逐条显式声明，discovery locator 保持关闭。
- 部署环境通过 `kaiwu.gateway.managed-routes-dir` 只读挂载受审查的业务 route；受管目录必须
  失败关闭，不能照搬本地目录的“坏文件跳过”行为。该目录只允许完整 PROJECT 元数据。
- 改动后执行 `./scripts/verify.sh`；脚本会先拒绝非 JDK 21 环境。
  该脚本跑 `mvn verify`，spotless 绑在 `validate` 阶段：排版不合规会在编译前就失败，
  连编译都到不了。修复只有一条命令 `mvn spotless:apply`（palantirJavaFormat），
  不要手工对齐——手工对齐的结果和 palantir 的输出几乎不会一致。
- 提交前的固定动作：`mvn spotless:apply && ./scripts/verify.sh`。依据 ADR 0029。
