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
| 1 | `buildPlugin` 产出可安装 zip | 通过（`build/distributions/cloud-toolkit-ecs-deploy-0.1.0.zip`） |
| 2 | `verifyPlugin` 对 2026.2 无 error | 通过（`Compatible`，仅 1 处 deprecated API：`FileChooserDescriptorFactory.createSingleFileDescriptor()`） |
| 3 | `runIde` 启动 262，Settings 连接页与 Run Configuration 可见 | 待测（需 GUI 沙箱环境） |
| 4 | 真实 ECS 全链路部署（上传 + 重启 + 日志） | 待测（需真实 ECS 主机） |
| 5 | 错误凭据 / 断网时给出分类提示，IDE 可交互 | 待测（需真实 ECS 与交互） |
| 6 | 部署中可取消且会话关闭 | 待测（需真实 ECS） |

步骤 1、2 于 `2026-11-11` 在本机构建链路验证通过（`.\gradlew.bat clean buildPlugin verifyPlugin -x test --no-daemon` → `BUILD SUCCESSFUL`）。步骤 3-6 需在带 GUI 的 IDEA 沙箱与真实 ECS 主机上人工验收，本无头构建环境无法执行。

真实 ECS 凭据只在本机 IDE 的 Settings 中输入，不写入本仓库。

设计与实现计划见 `docs/superpowers/specs/` 与 `docs/superpowers/plans/`。
