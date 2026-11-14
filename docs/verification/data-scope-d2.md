# Data Scope D2 验证记录（Gateway 透传 `ds`）

依据：System 仓 `docs/DATA_SCOPE.md` §4–§5、ADR 0044。分支 `feat/data-scope-d2`，基于 `origin/dev`。

## 改动

- `ProjectAccess` 增加 `dataScope`（保持键序的 Map，Gateway 不解释语义）；保留四字段构造器。
- `ProjectAccessResolver` 透传 `dataScope`；存在但不是对象 → 503 失败关闭；超过 6144 字节 → 拒绝（不截断）。
- System 返回 403 且 `messageArgs.reason=DATA_SCOPE_TOO_LARGE` → 拒绝、缓存 5 秒（新增配置
  `kaiwu.gateway.project-access.data-scope-deny-cache-ttl-seconds`）、记一条只含项目/用户/原因的日志。
  普通「不是成员」拒绝缓存时间不变（30 秒）。缓存改为按条目 TTL。
- `GatewayContextIssuer`：有 `dataScope` 才写 `ds`；PROJECT Context 新增 16 KiB 上限，超限抛
  `ContextTooLargeException`，过滤器返回 403。
- 两个 Agent 委托控制器取 System 响应里的 `dataScope`，写入单次读/写 Context；形态非法或超限 → 403，不触达业务服务。

## 同时修了 D1 的一个遗漏（System 仓 `feat/data-scope-d1`，提交 53699c3）

`agent-access` 与 `agent-write-access` 的响应此前丢掉了 `ProjectAccessView.dataScope`，Gateway 无从透传。
已补字段（为空时不出现）并加测试。

## 实测体积（`GatewayContextIssuerDataScopeTest.measuredHeadroomWithMaximumDataScope`）

ds 为 128 个 19 位部门 × 7 个资源完全重叠引用，5908 字节（上限 6144）：

| 项目权限码数 | Context 字符数 | 距 16384 余量 |
|---|---|---|
| 4（`origin/dev` 现有清单中最多） | 8849 | 7535 |
| 150（压力值，远超现实） | 15534 | 850 |

结论：「单用户可见部门 ≤ 128」口径成立。ds 顶到 6144 时，权限码约在 140 个（平均 36 字符）以内才有余量；
超出时 Gateway 返回 403，不放行。现有项目最多 4 个权限码，余量充足。
注：现有清单只扫描了工作区里的 `kaiwu/menus.yml`（最多 4 个权限码），未覆盖 System 自身。

## 测试

- 无 dataScope 时 Context 声明集合与改动前完全相同。
- 有 dataScope 时 `ds` 原样写入；PLATFORM Context 不带 `ds`。
- 超限（6144 字节 / 16 KiB）均 403，不放行也不截断；TOO_LARGE 缓存 5 秒、普通拒绝缓存不变；日志不含部门 ID。
- 读/写两条 Agent 委托链路都带 `ds`；没有时不出现；非法形态 403 且业务服务未被调用。
- `./scripts/verify.sh` 通过：75 个测试，0 失败。

## 未覆盖

- 未对着真实 System 联调（System 的 D1 仍是本地分支）；两仓联调放在 D6。
- 权限码数量只统计了工作区内的项目清单。
