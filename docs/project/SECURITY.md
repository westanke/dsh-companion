# 安全

- HTTP 明确允许但持续警告；HTTPS 使用系统信任锚，不提供忽略证书错误开关。
- 令牌使用 Android Keystore AES-GCM 加密，不写日志、剪贴板、崩溃信息或状态文档。
- 状态文档、开发日志与示例输出中的设备令牌一律脱敏为**等长占位符**（长度不变，避免破坏文中长度结论）。实测产生的真实令牌一旦不再需要即吊销，且不留在任何文档里。
- 应用数据明确排除在 Android 云备份和设备迁移之外，避免令牌密文离开生成密钥的设备。
- 正式 PKCS12 签名位于工作区 `.secrets`，不在 App 仓库内。
- App 不拥有服务器绝对路径，只处理 `rootId + relativePath`。
- App 不调用管理 API、DSH 私有 `/api` 或无约束 RPC 转发。
- UI 依据设备 scope 禁用写入、删除和聊天提交操作；服务器仍是最终授权边界。
- 供应商列表只读取凭据是否存在及其来源，不读取密钥正文；新增密钥仅通过 `settings.write` 发往插件并由 DSH credential service 保存，输入框不会用服务器值回填。
- 模型目录和供应商读取要求 `settings.read`，供应商配置/凭据修改要求 `settings.write`；`chat.write` 不能替代设置权限。
- 斜杠命令由插件按当前会话、root grant 和 `chat.read/chat.write` 校验。App 不提供任意 DSH RPC；完整访问权限预设必须经过独立确认对话框和勾选确认。
