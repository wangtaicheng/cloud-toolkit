# Cloud Toolkit — ECS Deploy

一个 IntelliJ IDEA 插件：在 IDE 内把 Java 应用部署到 ECS（构建 → SFTP 上传 → SSH 远程重启 → 部署日志）。不接入阿里云 OpenAPI，ECS 主机与 SSH 认证在 Settings 中配置。

## 环境要求

- IntelliJ IDEA `262.*`（2026.2）及以上
- 构建需要 JDK 25（本机 `D:/Program/java/zulu25`），编译目标为 Java 21
- 本机 IDEA 安装：`D:/Program/JetBrains/idea-2026.2.4.win`

## 常用命令（Windows PowerShell）

```powershell
.\gradlew.bat test --no-daemon            # 运行测试
.\gradlew.bat buildPlugin --no-daemon     # 产出 build/distributions/*.zip
.\gradlew.bat verifyPlugin --no-daemon    # 对 2026.2 做兼容性校验
.\gradlew.bat runIde --no-daemon          # 启动沙箱 IDE 验证插件
```

## 验收清单

| # | 项目 | 结果 |
| --- | --- | --- |
| 1 | `buildPlugin` 产出可安装 zip | 待测 |
| 2 | `verifyPlugin` 对 2026.2 无 error | 待测 |
| 3 | `runIde` 启动 262，Settings 连接页与 Run Configuration 可见 | 待测 |
| 4 | 真实 ECS 全链路部署（上传 + 重启 + 日志） | 待测 |
| 5 | 错误凭据 / 断网时给出分类提示，IDE 可交互 | 待测 |
| 6 | 部署中可取消且会话关闭 | 待测 |

真实 ECS 凭据只在本机 IDE 的 Settings 中输入，不写入本仓库。

设计与实现计划见 `docs/superpowers/specs/` 与 `docs/superpowers/plans/`。
