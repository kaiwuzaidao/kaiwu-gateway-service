# Kaiwu Gateway Service Agent Contract

- 禁止直接在 `main` 等受保护分支实现；保留调用者当前分支，必要时创建描述性 feature/fix 分支。
- 所有外部 Route 显式声明 accessMode、audience 和可选 projectCode。
- 不得信任客户端身份 Header。
- `/internal` 不得配置外部 Route。
- 鉴权依赖不可用时使用 503 失败关闭，不得使用过期快照放行。
