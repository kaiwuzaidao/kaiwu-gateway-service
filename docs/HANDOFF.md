
## Workflow C1 Gateway 初步适配（2026-11-01）

- `feat/workflow-c1-gateway` 校验受管路由的默认/非默认服务编码、确定性 ID、audience、精确 Path 和 RewritePath，禁止额外过滤器及 URI 用户信息/路径/查询。旧无 serviceCode 路由按 default；读/写 Agent 委托只选择 default。
- 受管目录启用时，框架聚合的路由定义在发布前检查跨来源 ID、项目 audience 和相同或末尾 `/**` 前缀重叠。非法路由失败关闭；该初版不声称已证明所有任意 Ant 路径表达式相交情况。
- `mvn spotless:apply` 和 `./scripts/verify.sh` 通过，65 项测试、0 失败；包括新旧服务和 Agent 选路、跨来源精确及前缀冲突的单元测试。首次全量因新方法缺中文 Javadoc 失败，补齐后通过。
- 尚未完成实际 HTTP 转发、多个 Gateway 实例和真实入口的验证；System 登记和前端尚未完成，不可发布非 default 片段。退出试点需先撤销非 default 配置再回退 Gateway。

C1 兼容性续核：default 旧路由沿用既有 URI 路径和附加过滤器行为；非 default 仍限制安全上游和唯一 RewritePath。新增旧式 URI 与附加过滤器测试，全套 `./scripts/verify.sh` 复测通过；实际入口尚未测试。

C1 路由聚合续核：项目路由现在也与 PLATFORM/PUBLIC 来源的相同 audience、相同或末尾 `/**` 前缀路径检查冲突；平台内部的多条公共/平台路由仍可共享其 System audience。对应跨类型冲突测试与完整检查通过；任意通配模式交集与真实入口仍待验证。

C1 通配冲突再验证：聚合校验把 `*`、`?` 和模板变量之前的固定前缀用于保守判重，无法证明不相交时拒绝，而不是只比较末尾 `/**`；新增跨 PLATFORM 通配覆盖测试，`./scripts/verify.sh` 通过。此规则可能拒绝实际不相交的复杂路由，应精确配置，不可视为真实入口联调。


## 2026-11-03 步骤4 C1当前交接

本次在当前dev及已审前置之上整合C1，默认开关保持关闭。真实门禁与本地37项联调通过，尚未合并/发布/部署。该批 C1 完整交接保存在当时的内部工作区，未随公开仓导出。本文保留当时的验证记录，当前接入规则见[仓库说明](../README.md#项目多服务路由)。
