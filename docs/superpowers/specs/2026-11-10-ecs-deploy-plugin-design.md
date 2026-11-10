# Cloud Toolkit — ECS Deploy 插件设计规格

- 日期：2026-11-10
- 状态：对话设计已批准，等待书面规格审查
- 适用范围：第一个子项目「ECS 部署主链路」

## 1. 目标

在 `d:/GitHub/idea-plugins/cloud-toolkit` 从零实现一个 IntelliJ IDEA 插件，让开发者在 IDE 内完成「构建 Java 应用 → 上传到 ECS → 远程重启服务 → 查看部署日志」，无需切换到外部 SSH/SCP 工具。目标平台为 IDEA `262.*` 及以上。

交互形态参照 `Alibaba Cloud Toolkit` 的 `Deploy to ECS`，但不接入阿里云 OpenAPI，不复用其代码、名称或图标资源。

## 2. 已达成一致的理解

### 2.1 用户明确要求

1. 仿照 Toolkit 的功能重新实现一个插件，支持 IDEA `262.*` 以上。
2. 本期只做 ECS 单目标端，要求端到端真实可用。
3. 部署链路：连接配置 → 构建打包 → SFTP 上传 → SSH 执行重启脚本 → 日志控制台。
4. 联调条件：有真实 ECS 可用于实证，Maven 依赖可正常下载。
5. **最终调整**：完全去掉云 API 层 —— 不接阿里云 OpenAPI、不引入阿里云 SDK、不要 AK/Region；ECS 主机与 SSH 认证在 Settings 里存成命名连接。

原先确认的「有可用 AK」因第 5 条调整不再需要；真实 ECS 仍是联调与验收的必要条件。

### 2.2 共同假设

- 交互形态为自定义 Run Configuration，`Before launch` 由平台执行 Maven/Gradle `package`，插件不自行触发构建。
- SSH 密码与私钥口令存 IntelliJ `PasswordSafe`；非敏感配置存 `PersistentStateComponent`。
- 代码仅使用 Java 21 语言特性，使用 JDK 25 toolchain 编译。
- 插件显示名 `Cloud Toolkit — ECS Deploy`，插件 ID `com.cloudtoolkit.ecsdeploy`，包名 `com.cloudtoolkit.ecsdeploy`；不使用阿里云官方名称或图标，避免与官方插件混淆。命名偏好可在规格审查时提出。
- 界面文案为中文。

### 2.3 本期明确不做

EDAS / SAE / ACK / 函数计算部署；ECS 实例自动发现与云资源树；内置 SSH 终端；多账号切换、RAM 与 STS；部署回滚；远端健康检查；发布到 JetBrains Marketplace。

## 3. 约束与环境

| 项目 | 约束 |
| --- | --- |
| 目标平台 | IntelliJ IDEA `262.*`（2026.2）及以上；`plugin.xml` 设 `since-build="262"`，不设 `until-build` |
| 平台依赖 | `com.intellij.modules.platform`、`com.intellij.modules.java`；Maven/Gradle 不列为插件依赖，构建由平台 Before launch 机制提供 |
| 构建 JDK | JDK 25（`D:\Program\java\zulu25`，本机为 25.0.5.0.3）。2026.2 平台类由 JDK 25 编译，用 JDK 21 编译会报 `bad class file` |
| 编译目标 | `--release 21`，字节码与语言特性保持在 Java 21，运行时由平台 JBR 25 承载 |
| 构建工具 | Gradle Wrapper 固定 `9.6.1`，`distributionUrl` 写入 `gradle-wrapper.properties`；系统已安装的 Gradle 7.3.3 不参与构建 |
| 构建插件 | IntelliJ Platform Gradle Plugin `2.18.1`（`org.jetbrains.intellij.platform` 系列） |
| 目标 IDE 解析 | 优先 `localPath` 指向本机 `D:\Program\JetBrains\idea-2026.2.4.win`；解析失败时回退 `intellijIdeaCommunity("2026.2")` |
| 运行依赖 | `org.apache.sshd:sshd-core` 与 `org.apache.sshd:sshd-sftp`，两者版本一致，取 `2.19.0` |
| 工作区现状 | 空目录、无 git 仓库；本次先纳入规格文档，工程文件在实现阶段建立 |
| 凭据 | 不入库、不写日志、不进 Run Configuration 持久化 |

## 4. 架构

### 4.1 分层

去掉 `cloud` 层后，插件分为四层，依赖方向单向向下：

```
ui ──> deploy ──> transfer
 │                   │
 └──────> settings <─┘
```

- `settings`：命名连接的增删改查、非敏感字段持久化、敏感字段的 `PasswordSafe` 读写、连接测试。
- `transfer`：SSH 会话建立与主机密钥校验、SFTP 上传、远程命令执行，全部面向接口，便于注入假实现。
- `deploy`：部署流水线编排、阶段状态、取消、异常分类；`DeployTarget` 是唯一的扩展点。
- `ui`：Settings 配置页、Run Configuration 及其编辑器、部署 Console、进度与通知。

### 4.2 包结构

```
src/main/java/com/cloudtoolkit/ecsdeploy/
├── settings/
│   ├── EcsConnection.java              # 连接模型（非敏感字段 + 凭据引用键）
│   ├── EcsConnectionState.java         # PersistentStateComponent 状态
│   ├── EcsConnectionsService.java      # 应用层服务，连接的 CRUD 与查询
│   ├── CredentialStore.java            # PasswordSafe 封装
│   └── EcsConnectionsConfigurable.java # Settings 页与「测试连接」
├── transfer/
│   ├── SshSessionFactory.java          # 会话、认证、known_hosts 校验
│   ├── RemoteFileTransfer.java         # mkdir -p、SFTP 上传、进度回调
│   ├── RemoteCommandExecutor.java      # exec channel、流式输出、退出码
│   ├── TransferProgress.java           # 已传字节数 / 总字节数
│   └── HostKeyPolicy.java              # 已知主机校验与首次确认
├── deploy/
│   ├── DeployTarget.java               # 扩展点接口
│   ├── EcsSshTarget.java               # 本期唯一实现
│   ├── DeployContext.java              # 一次部署的输入快照
│   ├── DeployStage.java                # 阶段枚举
│   ├── DeployPipeline.java             # 阶段编排、取消、资源释放
│   └── DeployException.java            # 及分类子类
├── ui/
│   ├── EcsDeployConfigurationType.java
│   ├── EcsDeployRunConfiguration.java
│   ├── EcsDeployRunConfigurationOptions.java
│   ├── EcsDeploySettingsEditor.java
│   ├── EcsDeployRunProfileState.java
│   ├── DeployProcessHandler.java       # 支持停止按钮与取消传播
│   ├── DeployConsole.java              # ConsoleView 封装与分阶段着色
│   └── DeployNotifier.java
└── util/
    ├── PathUtils.java                  # 远端路径规范化、本地 ~ 展开
    └── OutputSanitizer.java            # 日志脱敏与 UTF-8 解码
src/main/resources/
├── META-INF/plugin.xml
└── messages/EcsDeployBundle.properties
src/test/java/com/cloudtoolkit/ecsdeploy/   # 与被测单元对应的 JUnit 5 测试
```

### 4.3 组件职责边界

| 单元 | 职责 | 依赖 | 对外契约 |
| --- | --- | --- | --- |
| `EcsConnectionsService` | 保存、列出、删除命名连接 | `CredentialStore`、平台持久化 | `List<EcsConnection>`、`findById` |
| `CredentialStore` | 按连接 ID 读写密码 / 私钥口令 | `PasswordSafe` | `getSecret(id, kind)`、`setSecret` |
| `SshSessionFactory` | 建立并认证 `ClientSession`，连接与认证各 15 秒超时 | MINA sshd、`HostKeyPolicy` | `open(EcsConnection, secret)` |
| `RemoteFileTransfer` | 逐级创建远端目录、上传到临时文件后原子 rename | `ClientSession`、`TransferProgress` | `upload(local, remoteDir, callback)` |
| `RemoteCommandExecutor` | 执行一条命令，流式回传 stdout/stderr，返回退出码；单命令默认 5 分钟超时，执行中轮询取消状态 | `ClientSession` | `execute(command, sink, canceled)` → `int` |
| `DeployPipeline` | 按阶段编排、检查取消、保证会话关闭、抛出分类异常 | `DeployTarget`、`ProgressIndicator` | `run(context)` |
| `EcsSshTarget` | 实现 `DeployTarget`，把流水线阶段映射到 transfer 层调用并持有会话 | `transfer` 层两个接口 | `prepare` / `upload` / `stop` / `start` / `close` |
| `DeployConsole` | 分阶段输出、进度条、错误着色、脱敏 | `ConsoleView` | `stage(...)`、`line(...)`、`error(...)` |
| `DeployProcessHandler` | 把 IDE 停止按钮转成流水线取消 | `DeployPipeline` | `destroyProcessImpl()` |

每个单元可以单独注入假依赖进行单元测试；`ui` 层不直接引用 MINA sshd 类型。

### 4.4 扩展点

`DeployTarget` 定义与流水线阶段一一对应的方法：`prepare(ctx)`、`upload(ctx, progress)`、`stop(ctx, sink)`、`start(ctx, sink)`、`close()`。`DeployPipeline` 按 `DeployStage` 顺序调用，并在 `finally` 中调用 `close()`。本期只有 `EcsSshTarget` 一个实现；这些方法对应本期真实存在的远程动作，不包含面向未来目标端的抽象。不预先定义 EDAS/SAE/ACK/FC 的类型层级，后续新增目标端时在 `deploy` 层增加实现并让 Run Configuration 提供目标端选择，`settings` 与 `transfer` 层保持不变。

## 5. 详细设计

### 5.1 连接配置与凭据

`EcsConnection` 字段：`id`（UUID 字符串）、`displayName`、`host`、`port`（默认 22）、`username`、`authType`（`PASSWORD` / `PRIVATE_KEY`）、`privateKeyPath`（仅私钥文件路径，不存私钥内容）、`privateKeyPassphraseRef`（布尔，表示口令在 `PasswordSafe` 中）。密码和口令一律不进入该模型。

`EcsConnectionsService` 是 application 级服务，`@State(storages = @Storage("cloud-toolkit-ecs.xml"))`，只序列化 `EcsConnection` 列表。敏感值通过 `CredentialStore` 以 `CredentialAttributes("com.cloudtoolkit.ecsdeploy/" + id + "/" + kind)` 存取。删除连接时同时清除对应凭据。

`EcsConnectionsConfigurable` 提供表格 + 新增/编辑/删除 + 「测试连接」。测试连接在后台线程执行：建立会话、认证成功后立即关闭，结果以通知反馈；不执行任何远程写操作。认证方式由 `authType` 决定：`PRIVATE_KEY` 时从 `privateKeyPath` 读取私钥、从 `PasswordSafe` 取口令；`PASSWORD` 时从 `PasswordSafe` 取密码。

主机密钥策略：`HostKeyPolicy` 默认读取用户 `~/.ssh/known_hosts`。遇到未知主机时弹出一次性确认对话框，展示主机与指纹，确认后写入 `known_hosts`；拒绝则连接失败。Settings 提供「跳过主机密钥校验」开关，默认关闭，开启时在配置页显示风险提示。

### 5.2 Run Configuration 与编辑器

`EcsDeployConfigurationType` 注册 ID `EcsDeployRunConfiguration`，显示名 `Cloud Toolkit: Deploy to ECS`。

`EcsDeployRunConfigurationOptions` 持久化：`connectionId`、本地产物路径 `artifactPath`、远端目录 `remoteDirectory`（默认 `/opt/app`）、`stopCommand`、`startCommand`。所有路径与命令都是用户配置，不含凭据。

`EcsDeploySettingsEditor` 提供：连接下拉（从 `EcsConnectionsService` 读取，空列表时给出跳转 Settings 的链接）、本地 jar/war 文件选择器、远端目录输入、stop/start 命令输入。校验在编辑器层完成：连接存在、产物路径非空、远端目录为绝对路径、start 命令非空。`stopCommand` 允许为空，表示不停止旧进程。

`EcsDeployRunProfileState.execute(...)` 创建 `ConsoleView`、构造 `DeployContext`，把流水线放进 `Task.Backgroundable` 执行，通过 `DeployProcessHandler` 关联 IDE 的运行/停止生命周期；`ProgressIndicator` 同时驱动 Run Tool Window 的进度条。

### 5.3 文件传输与远程执行

上传使用 `SftpClientFactory` 创建客户端，先逐级 `mkdir -p` 远端目录，再把文件上传为 `<remoteDir>/<fileName>.<timestamp>.tmp`，完成后 rename 覆盖目标文件，避免服务在半写入状态下被重启。进度通过 `TransferProgress` 回调到 Console 与 `ProgressIndicator`。

远程命令使用 exec channel，stdout 与 stderr 分别以 UTF-8 解码并按行推给 `DeployConsole`，同时记录最后 200 行用于失败诊断。命令结束后读取退出码；`execute` 在执行期间轮询 `canceled` 回调（由 `DeployPipeline` 传入 `ProgressIndicator::isCanceled`），为真时关闭 channel 并终止流水线。

### 5.4 部署流水线

`DeployPipeline.run` 按顺序执行 `DeployStage`：

1. `VALIDATE` — 配置完整、连接存在、本地产物是存在的文件、凭据可读取。
2. `CONNECT` — 建立会话并完成认证与主机密钥校验。
3. `PREPARE` — 创建远端目录。
4. `UPLOAD` — SFTP 上传并原子替换。
5. `STOP` — `stopCommand` 非空时执行；非零退出码**不中止**，记录警告（服务可能本就未运行）。
6. `START` — 执行 `startCommand`；非零退出码判定部署失败，携带退出码与最后 200 行输出。
7. `FINISH` — 调用 `DeployTarget.close()` 关闭会话与 SFTP 客户端，输出阶段汇总，发送结果通知。

每个阶段前检查取消；`FINISH` 与 `DeployTarget.close()` 在 `finally` 中执行，保证任何失败或取消后会话都被关闭。取消抛出 `DeploymentCanceledException`，在 Console 中显示为「已取消」而非红色错误。

### 5.5 日志与结果反馈

Console 按阶段输出标题行（中文）、命令回显、逐行远程输出、上传百分比、阶段耗时。错误以红色显示，警告以黄色显示。部署结束通过 `DeployNotifier` 发送气球通知：成功显示目标主机与产物文件名，失败显示异常分类与一句话建议。

## 6. 错误处理

| 异常 | 触发条件 | 用户提示 |
| --- | --- | --- |
| `ConfigurationException` | 连接缺失、产物不存在、远端目录非绝对路径、start 命令为空 | 指出具体字段并给出修改位置 |
| `SshConnectException` | 主机不可达、端口不通、连接超时 | 检查 host/port、安全组与本机网络 |
| `SshAuthenticationException` | 密码错误、私钥无效、口令错误、未知主机被拒绝 | 检查认证方式或重新确认主机指纹 |
| `SftpTransferException` | 目录无写权限、磁盘空间不足、传输中断 | 显示远端路径与底层原因 |
| `RemoteCommandException` | start 命令非零退出 | 显示退出码与最后 200 行输出 |
| `DeploymentCanceledException` | 用户点停止或取消 | 显示「已取消」 |

所有网络操作在后台线程执行，UI 更新统一走 `ApplicationManager.getApplication().invokeLater`；异常不向 EDT 抛出，IDE 不会卡死。

## 7. 安全

- SSH 密码、私钥口令只存 `PasswordSafe`；私钥内容只从用户指定的本地文件读取，不复制进配置、不上传。
- Console、通知、日志文件中不输出密码、口令、私钥内容；`OutputSanitizer` 对已知敏感串做掩码。
- 用户填写的 stop/start 命令属于用户自己的配置，按原样回显；插件不擅自注入或改写命令。
- 默认启用 `known_hosts` 校验，跳过校验必须显式开启并附风险提示。
- 真实 ECS 凭据不写入仓库、不写入自动化测试与验收清单文件。

## 8. 测试策略

- JUnit 5 纯 JVM 单元测试（不启动 IDE）：`DeployPipeline` 在 `FakeDeployTarget`（逐阶段记录调用，可注入各阶段失败）下断言阶段顺序、STOP 非零不中止、START 非零失败、上传失败不执行 start、取消时 `close()` 被调用、各分支都走到 `FINISH`；`RemoteFileTransfer` 与 `RemoteCommandExecutor` 分别用假会话做单元测试，验证进度回传、输出解析与取消行为。
- `EcsConnectionState` 序列化测试：序列化后的 XML 中断言不含 `password`、`passphrase`、`privateKeyContent` 字段。
- `PathUtils` 测试：远端路径规范化、Windows 本地路径与 `~` 展开、文件名含空格的处理。
- `OutputSanitizer` 测试：敏感串被掩码、UTF-8 中文输出不乱码。
- 平台相关部分（Settings 页、Run Configuration 持久化）用 IntelliJ Platform 测试框架的轻量用例覆盖配置读写与编辑器校验。
- 真实 ECS 联调属于人工验收，不自动化；凭据由执行者在本机 Settings 中输入。

## 9. 验收标准

1. `./gradlew buildPlugin` 成功，产出可安装的 `build/distributions/*.zip`。
2. `./gradlew verifyPlugin` 对 2026.2 目标无兼容性 error。
3. `./gradlew runIde` 用 262 启动；Settings 中可新增命名连接，「测试连接」返回成功。
4. 新建 `Cloud Toolkit: Deploy to ECS` Run Configuration，把 Maven/Gradle `package` 加入 Before launch；执行后远端目录出现新产物、目标进程被重启、Console 有分阶段日志与上传进度、结束有结果通知。
5. 填错密码或断开网络时，给出对应分类错误提示，IDE 保持可交互。
6. 部署过程中点击停止可取消，会话被关闭，Console 显示「已取消」。

## 10. 依赖与构建配置

```kotlin
// build.gradle.kts 的关键配置约束，实现阶段据此编写完整脚本
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile> { options.release = 21 }
dependencies {
    intellijPlatform { localPath("D:/Program/JetBrains/idea-2026.2.4.win") }
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
intellijPlatform { pluginConfiguration { ideaVersion { sinceBuild = "262" } } }
```

`localPath` 解析失败时，把该行替换为 `intellijIdeaCommunity("2026.2")`。`plugin.xml` 的 `<depends>` 声明 `com.intellij.modules.platform` 与 `com.intellij.modules.java`，并注册：`com.intellij.applicationService`（`EcsConnectionsService`）、`com.intellij.applicationConfigurable`（`EcsConnectionsConfigurable`）、`com.intellij.configurationType`（`EcsDeployConfigurationType`）、`com.intellij.notificationGroup`（部署结果）。

`sshd-core` / `sshd-sftp` 会传递引入 `slf4j-api`；打包时排除传递的 slf4j 绑定，只保留 `slf4j-api`，由平台提供实现，避免与 IDEA 自带日志实现冲突。`buildPlugin` 产物的 `lib/` 目录需核对没有重复或冲突的 jar。

## 11. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 2026.2 平台 API 存在不兼容变更或弃用移除 | 只使用稳定 API；`verifyPlugin` 与官方 2026 API Changes 列表逐项核对，报错时按目标平台修正调用 |
| Gradle 9.6.1 与构建插件 2.18.1 组合报版本不兼容 | 按该插件官方 Requirements 的最低版本调整 `distributionUrl`，属于构建配置调整，不改变设计 |
| Maven Central 上 `sshd 2.19.0` 不可用 | 改用 `org/apache/sshd/sshd-sftp` 目录中最新的 `2.1x` 稳定版，`sshd-core` 与 `sshd-sftp` 保持同版本 |
| 本机 `idea-2026.2.4.win` 为 Ultimate 版导致 `localPath` 解析异常 | 构建配置回退到 `intellijIdeaCommunity("2026.2")`，`runIde` 仍可用本机安装验证 |
| Windows 与远端路径、编码差异 | 远端路径统一 `/`，本地用 `Path` API 并支持 `~`；远程输出强制 UTF-8 解码 |
| SSH 库与平台自带 netty/slf4j 冲突 | 依赖最小化，打包后核对 `lib/`，必要时用 `exclude` 收敛传递依赖 |
| 用户 stop/start 命令写法导致服务未真正重启 | 编辑器层校验 start 命令非空，Console 显示退出码与最后 200 行输出，便于快速定位 |
