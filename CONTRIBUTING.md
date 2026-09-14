# Contributing to Kaiwu Gateway Service

感谢参与 Kaiwu。提交变更前请先创建 Issue 说明问题或设计，安全漏洞请遵循
[SECURITY.md](SECURITY.md)，不要公开披露。

## 开发流程

1. 从最新开发分支创建短生命周期分支。
2. 保持提交聚焦，不提交密钥、内部地址、生成产物或无关格式化。
3. 执行 `mvn -s .mvn/settings.xml verify`。
4. 提交 Merge Request，并说明路由、身份边界、测试和兼容性影响。

禁止启用 discovery locator。每条外部路由必须明确声明访问模式和唯一 audience，并保持
`/internal` 及客户端伪造身份头失败关闭。
