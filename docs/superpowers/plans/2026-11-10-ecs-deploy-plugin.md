# Cloud Toolkit — ECS Deploy 插件实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 subagent-driven-development（推荐）或 executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从零实现一个支持 IDEA `262.*` 及以上的插件，在 IDE 内完成「构建 Java 应用 → SFTP 上传到 ECS → SSH 重启服务 → 部署日志」的端到端流程。

**架构：** 去掉云 API 层后的四层单向依赖：`ui → deploy → transfer → settings`。`settings` 保存命名 SSH 连接（敏感值进 `PasswordSafe`），`transfer` 用 Apache MINA sshd 做会话/上传/命令，`deploy` 用 `DeployTarget` 接口编排 7 阶段流水线，`ui` 提供 Settings 配置页、自定义 Run Configuration 与 Console。不接入阿里云 OpenAPI。

**技术栈：** Java 21 语法 / JDK 25 toolchain、Gradle Wrapper 9.6.1、IntelliJ Platform Gradle Plugin 2.18.1、IntelliJ IDEA 2026.2 (`262`)、Apache MINA sshd 2.19.0、JUnit 5 + IntelliJ Platform Test Framework。

**规格：** `docs/superpowers/specs/2026-11-10-ecs-deploy-plugin-design.md`（执行者必须同时读规格与本计划）。

## 全局约束

- 目标平台 IDEA `262.*`（2026.2）及以上；`plugin.xml` 设 `since-build="262"`，不设 `until-build`。
- 平台依赖 `com.intellij.modules.platform`、`com.intellij.modules.java`；Maven/Gradle 不列为插件依赖，构建由平台 Before launch 机制提供。
- JDK 25（`D:\Program\java\zulu25`）toolchain 编译，`--release 21`；2026.2 平台类由 JDK 25 编译，用 JDK 21 编译会报 `bad class file`。
- Gradle Wrapper 固定 `9.6.1`；系统已安装的 Gradle 7.3.3 只用于生成 wrapper，不参与构建。
- 构建插件 `org.jetbrains.intellij.platform` `2.18.1`；`localPath` 优先 `D:/Program/JetBrains/idea-2026.2.4.win`，解析失败回退 `intellijIdeaCommunity("2026.2")`。
- 运行依赖 `org.apache.sshd:sshd-core:2.19.0` 与 `org.apache.sshd:sshd-sftp:2.19.0`，两者版本一致；排除传递的 slf4j 绑定，只留 `slf4j-api`。
- 插件 ID `com.cloudtoolkit.ecsdeploy`，显示名 `Cloud Toolkit — ECS Deploy`，包名 `com.cloudtoolkit.ecsdeploy`；界面文案中文；不使用阿里云官方名称或图标。
- SSH 密码、私钥口令只存 `PasswordSafe`；不写日志、不入库、不进 Run Configuration 持久化；`DeployContext.toString()` 不得包含凭据。
- 远端路径统一 `/`，远程输出强制 UTF-8 解码；连接与认证超时各 15 秒，单命令 5 分钟。
- stop 命令非零退出**不中止**，start 命令非零退出**判失败**并携带退出码与最后 200 行输出。
- 不接阿里云 OpenAPI、不引阿里云 SDK、不要 AK/Region；本期不做 EDAS/SAE/ACK/FC、资源树、内置终端、多账号、回滚、健康检查、Marketplace 发布。
- 命令在 Windows PowerShell 下用 `.\gradlew.bat`；Git Bash 下等价为 `./gradlew`。真实 ECS 凭据由执行者在本机 Settings 输入，不写进仓库与自动化测试。
- 本机是 Windows：测试一律不依赖系统 shell；`TestSshServer` 用 JVM 内 `CommandFactory` 解释命令（见任务 4）。

## 审查重点（Review Focus）

1. 连接配置被删除后，已保存的 Run Configuration 仍引用旧 `connectionId` → 期待编辑器提示「连接不存在，请重新选择」、`VALIDATE` 抛 `ConfigurationException`，不出现 NPE。
2. 产物路径在 Before launch 构建前不存在、被填成目录、或为 0 字节文件 → 期待 `VALIDATE` 明确指出产物问题，且不建立 SSH 连接、不上传。
3. `known_hosts` 中主机密钥变更（ECS 重装或 IP 复用常见） → 期待拒绝连接并明确提示「主机密钥已变更」，不接受连接。
4. 私钥文件不存在、内容损坏、或需要口令但用户未填 → 期待 `SshAuthenticationException` 带可操作原因，后台线程结束且 EDT 不阻塞。
5. 部署中用户点停止 / 关闭 IDE，或 start 命令执行途中网络断开 → 期待取消生效、`close()` 被调用、后台线程收尾、不向 EDT 抛未捕获异常，Console 显示「已取消」。

---

## 文件结构

| 文件 | 职责 | 任务 |
| --- | --- | --- |
| `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties` | Gradle 工程、平台依赖、toolchain、测试框架 | 1 |
| `gradle/wrapper/*`、`gradlew`、`gradlew.bat`、`.gitignore`、`README.md` | Wrapper、仓库卫生、构建/验收命令说明 | 1 |
| `src/main/resources/META-INF/plugin.xml` | 插件声明与扩展点注册（随任务递增） | 1/3/7/8/9/10 |
| `src/main/resources/messages/EcsDeployBundle.properties` | 中文文案 | 1 |
| `settings/EcsConnection.java` | 连接模型与 `AuthType` 枚举（无敏感字段） | 2 |
| `deploy/DeployStage.java`、`deploy/DeployContext.java`、`deploy/DeployException.java` | 7 阶段枚举、输入快照、基类 + 6 个嵌套分类子类 | 2 |
| `util/PathUtils.java`、`util/OutputSanitizer.java` | 路径规范化、脱敏与 UTF-8 解码 | 2 |
| `settings/CredentialStore.java` | `PasswordSafe` 封装与 `SecretKind` 枚举 | 3 |
| `settings/EcsConnectionState.java`、`settings/EcsConnectionsService.java` | 可序列化状态 POJO 与连接 CRUD 服务（服务持有状态） | 3 |
| `transfer/HostKeyPolicy.java`、`transfer/SshSessionFactory.java` | known_hosts 校验、会话建立与认证 | 4 |
| `transfer/TransferProgress.java` | 已传/总字节数与百分比 | 4 |
| `transfer/RemoteFileTransfer.java` | mkdir -p、临时文件上传 + 原子 rename、进度 | 5 |
| `transfer/RemoteCommandExecutor.java` | exec channel、stdout/stderr 分流、退出码、取消 | 5 |
| `deploy/DeployTarget.java`、`deploy/DeployObserver.java` | 阶段接口与输出观察接口 | 6 |
| `deploy/DeployPipeline.java`、`deploy/EcsSshTarget.java` | 流水线编排与 transfer 层映射 | 6 |
| `ui/EcsDeployConfigurationType.java`、`ui/EcsDeployRunConfiguration.java` | Run Configuration 类型与实例 | 7 |
| `ui/EcsDeployRunConfigurationOptions.java`、`ui/EcsDeploySettingsEditor.java` | 配置持久化与编辑器校验 | 7 |
| `ui/DeployConsole.java`、`ui/DeployProcessHandler.java`、`ui/DeployNotifier.java` | Console、取消传播、结果通知 | 8 |
| `ui/EcsDeployRunProfileState.java` | `ExecutionResult` 与后台任务入口 | 8 |
| `settings/EcsConnectionsConfigurable.java` | Settings 连接表格与「测试连接」 | 9 |
| `src/test/java/.../support/TestSshServer.java` | 本机内存 SSH/SFTP 服务夹具 | 4 |

测试文件与主类同名加 `Test`，位于 `src/test/java/com/cloudtoolkit/ecsdeploy/` 对应包下；任务 3/7/9 的平台轻量用例继承 `LightPlatformTestCase`。

---

### 任务 1：工程骨架与构建链路

**文件：**

- 创建：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`gradlew`、`gradlew.bat`、`.gitignore`
- 创建：`src/main/resources/META-INF/plugin.xml`（最小版：id / name / vendor / `idea-version` / `depends`）、`src/main/resources/messages/EcsDeployBundle.properties`
- 创建：`README.md`（构建、`runIde`、打包、验收命令）
- 测试：无单元测试；本任务交付物是构建链路，验证方式为构建命令

- [ ] **步骤 1：生成 wrapper 与构建脚本**

`settings.gradle.kts` 声明 `rootProject.name = "cloud-toolkit-ecs-deploy"` 与 `pluginManagement` 仓库（`gradlePluginPortal()`、`mavenCentral()`）。`build.gradle.kts` 按规格第 10 节的关键配置约束编写：`plugins { id("java"); id("org.jetbrains.intellij.platform") version "2.18.1" }`、`java.toolchain.languageVersion = 25`、`tasks.withType<JavaCompile> { options.release = 21 }`、`intellijPlatform.localPath(...)`、`implementation` 两个 sshd 依赖并排除 `slf4j-simple` / `slf4j-nop` / `logback-classic`、`testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")` + `testImplementation("junit:junit:4.13.2")` + `testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")` + `intellijPlatform.testFramework(TestFrameworkType.Platform)`、`tasks.test { useJUnitPlatform() }`、`pluginConfiguration.ideaVersion.sinceBuild = "262"`。

`gradle.properties` 设 `org.gradle.java.home=D:/Program/java/zulu25` 与 `org.gradle.jvmargs=-Xmx2g`。用系统 Gradle 7.3.3 执行 `gradle wrapper --gradle-version 9.6.1 --distribution-type bin` 生成 wrapper 文件；若 7.3.3 在 JDK 21 下执行失败，临时把 `JAVA_HOME` 指向 `D:\Program\java\zulu17` 重跑同一命令，之后所有命令改用 `.\gradlew.bat`。`.gitignore` 排除 `.gradle/`、`build/`、`.idea/`、`*.iml`、`.intellijPlatform/`。

- [ ] **步骤 2：最小插件声明**

`plugin.xml` 写 `<id>com.cloudtoolkit.ecsdeploy</id>`、`<name>Cloud Toolkit — ECS Deploy</name>`、`<vendor>cloud-toolkit</vendor>`、`<idea-version since-build="262"/>`、两个 `<depends>`；`EcsDeployBundle.properties` 先放 `configuration.type.name=Cloud Toolkit: Deploy to ECS` 与 `notification.group=Cloud Toolkit 部署`。

- [ ] **步骤 3：验证构建闭环**

运行：`.\gradlew.bat buildPlugin --no-daemon`
预期：BUILD SUCCESSFUL，产出 `build/distributions/cloud-toolkit-ecs-deploy-*.zip`，zip 内 `lib/` 只有插件 jar 与两个 sshd jar（及 `slf4j-api`），无重复 slf4j 绑定。

- [ ] **步骤 4：验证插件兼容性任务可运行**

运行：`.\gradlew.bat verifyPlugin --no-daemon`
预期：任务执行完成，对 `2026.2` 目标无 error。

- [ ] **步骤 5：Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat .gitignore README.md src/main/resources/
git commit -m "build: scaffold IntelliJ Platform 262 plugin project"
```

**Interfaces（供后续任务使用）：** 根包名 `com.cloudtoolkit.ecsdeploy`；构建命令 `.\gradlew.bat <task> --no-daemon`；主源集 `src/main/java`，资源 `src/main/resources`，测试 `src/test/java`。

---

### 任务 2：领域模型、异常类族与工具函数

**文件：**

- 创建：`settings/EcsConnection.java`、`deploy/DeployStage.java`、`deploy/DeployContext.java`、`deploy/DeployException.java`、`util/PathUtils.java`、`util/OutputSanitizer.java`
- 测试：`util/PathUtilsTest.java`、`util/OutputSanitizerTest.java`、`deploy/DeployContextTest.java`
- 修改：`src/main/resources/messages/EcsDeployBundle.properties`（补校验与阶段文案）

- [ ] **步骤 1：编写失败的测试**

```java
// PathUtilsTest
@Test void normalizeRemoteDirectoryTrimsTrailingSlashAndFoldsSlashes() {
    assertEquals("/opt/app", PathUtils.normalizeRemoteDirectory("/opt//app///"));
    assertEquals("/opt/app", PathUtils.normalizeRemoteDirectory("\\opt\\app"));
}
@Test void remoteFilePathKeepsSpaces() {
    assertEquals("/opt/my app/app.jar", PathUtils.remoteFilePath("/opt/my app", "app.jar"));
}
@Test void rejectsRelativeRemoteDirectory() {
    assertFalse(PathUtils.isAbsoluteRemote("opt/app"));
    assertTrue(PathUtils.isAbsoluteRemote("/opt/app"));
}

// DeployContextTest
@Test void validateRejectsMissingArtifact(@TempDir Path dir) {
    assertThrows(ConfigurationException.class, () -> ctx(dir.resolve("missing.jar")).validate());
}
@Test void validateRejectsDirectoryArtifact(@TempDir Path dir) {
    assertThrows(ConfigurationException.class, () -> ctx(dir).validate());
}
@Test void validateRejectsEmptyArtifact(@TempDir Path dir) throws Exception {
    Path empty = Files.createFile(dir.resolve("app.jar"));
    assertThrows(ConfigurationException.class, () -> ctx(empty).validate());
}
@Test void validateFailsWhenConnectionIsNull(@TempDir Path dir) throws Exception {
    Path jar = Files.write(dir.resolve("app.jar"), new byte[]{1});
    DeployContext c = new DeployContext(null, "s", jar, "/opt/app", "", "start");
    assertThrows(ConfigurationException.class, c::validate);
}
@Test void toStringNeverContainsSecret(@TempDir Path dir) throws Exception {
    Path jar = Files.write(dir.resolve("app.jar"), new byte[]{1});
    DeployContext c = new DeployContext(connection(), "SUPER_SECRET", jar, "/opt/app", "", "start");
    assertFalse(c.toString().contains("SUPER_SECRET"));
}

// OutputSanitizerTest
@Test void masksKnownSecrets() {
    assertEquals("use *** now", OutputSanitizer.sanitize("use SECRET now", List.of("SECRET")));
}
@Test void decodesUtf8Chinese() {
    assertEquals("部署成功", OutputSanitizer.decodeUtf8("部署成功".getBytes(StandardCharsets.UTF_8)));
}
```

`ctx(Path)` 与 `connection()` 是测试类的私有辅助方法，按 `DeployContext` 构造签名组装合法输入。

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.*" --no-daemon`
预期：FAIL，`cannot find symbol`（类尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`EcsConnection`：public 字段 `String id, displayName, host, username, privateKeyPath`；`int port = 22`；`AuthType authType = AuthType.PASSWORD`；`boolean hasPassphrase`；嵌套 `enum AuthType { PASSWORD, PRIVATE_KEY }`。**不得**出现 password / passphrase / privateKeyContent 字段。

`DeployStage`：`enum VALIDATE, CONNECT, PREPARE, UPLOAD, STOP, START, FINISH`。

`DeployContext`：`public DeployContext(EcsConnection connection, String secret, Path artifactPath, String remoteDirectory, String stopCommand, String startCommand)`；getter 为 `connection()` / `secret()` / `artifactPath()` / `remoteDirectory()` / `stopCommand()` / `startCommand()`；`public void validate() throws ConfigurationException` 依次校验 connection 非 null、`artifactPath` 存在且是普通文件且 `size > 0`、`remoteDirectory` 由 `PathUtils.isAbsoluteRemote` 判真、`startCommand` 非空白；覆写 `toString()` 只输出非敏感字段。

`DeployException`：`extends Exception`，嵌套 public static 子类 `ConfigurationException`、`SshConnectException`、`SshAuthenticationException`、`SftpTransferException`、`RemoteCommandException`、`DeploymentCanceledException`；`RemoteCommandException(int exitCode, List<String> tail)` 暴露 `exitCode()` 与 `tail()`。

`PathUtils`：`static String normalizeRemoteDirectory(String)`、`static boolean isAbsoluteRemote(String)`、`static Path expandHome(String)`、`static String remoteFilePath(String dir, String fileName)`。

`OutputSanitizer`：`static String sanitize(String line, Collection<String> secrets)`（把每个非空 secret 替换为 `***`）、`static String decodeUtf8(byte[])`（`REPLACE` 而非抛异常）。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.*" --no-daemon`
预期：PASS，10 个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/settings/EcsConnection.java src/main/java/com/cloudtoolkit/ecsdeploy/deploy/ src/main/java/com/cloudtoolkit/ecsdeploy/util/ src/test/java/com/cloudtoolkit/ecsdeploy/
git commit -m "feat: add domain model, exception taxonomy and path/output utilities"
```

**Interfaces：** 本任务全部签名如上；后续任务统一使用 `DeployContext` 的 getter 名称与 `DeployException` 的嵌套子类名。**审查重点 #1**（`validateFailsWhenConnectionIsNull`）与 **#2**（三个产物校验用例）由本任务钉住。

---

### 任务 3：凭据存储与连接配置服务

**文件：**

- 创建：`settings/CredentialStore.java`、`settings/EcsConnectionState.java`、`settings/EcsConnectionsService.java`
- 测试：`settings/EcsConnectionStateTest.java`（纯 JUnit 5）、`settings/EcsConnectionsServiceTest.java`、`settings/CredentialStoreTest.java`（后两者继承 `LightPlatformTestCase`）
- 修改：`src/main/resources/META-INF/plugin.xml`（注册 `com.intellij.applicationService`）

- [ ] **步骤 1：编写失败的测试**

```java
// EcsConnectionStateTest（纯 JVM，反射断言）
@Test void stateHasNoSecretFields() {
    assertFalse(Arrays.stream(EcsConnectionState.class.getDeclaredFields())
            .anyMatch(f -> f.getName().toLowerCase().contains("password")));
    assertFalse(Arrays.stream(EcsConnection.class.getDeclaredFields())
            .anyMatch(f -> f.getName().toLowerCase().contains("password")
                    || f.getName().toLowerCase().contains("passphrase")
                    || f.getName().toLowerCase().contains("privatekeycontent")));
}

// EcsConnectionsServiceTest（LightPlatformTestCase）
public void testUpsertFindAndDeleteRemovesCredentials() {
    EcsConnection c = new EcsConnection(); c.id = "c1"; c.displayName = "prod";
    service.upsert(c, "SUPER_SECRET");
    assertEquals("prod", service.findById("c1").displayName);
    assertEquals("SUPER_SECRET", store.getSecret("c1", CredentialStore.SecretKind.PASSWORD));
    service.delete("c1");
    assertNull(service.findById("c1"));
    assertNull(store.getSecret("c1", CredentialStore.SecretKind.PASSWORD));
}
public void testDeletedConnectionReturnsNull() {
    assertNull(service.findById("does-not-exist"));
}

// CredentialStoreTest（LightPlatformTestCase）
public void testSetGetDeleteSecrets() {
    store.setSecret("c2", CredentialStore.SecretKind.PASSPHRASE, "phrase");
    assertEquals("phrase", store.getSecret("c2", CredentialStore.SecretKind.PASSPHRASE));
    store.deleteSecrets("c2");
    assertNull(store.getSecret("c2", CredentialStore.SecretKind.PASSPHRASE));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.settings.*" --no-daemon`
预期：FAIL，`cannot find symbol`（类尚未创建）；若报 `TestFrameworkType.Platform` 依赖缺失，先按 2.18.1 文档修正 `build.gradle.kts` 的 `testFramework(...)` 声明再重跑，仍须看到 FAIL 才算本步通过。

- [ ] **步骤 3：实现类型与签名**

`CredentialStore`：嵌套 `enum SecretKind { PASSWORD, PASSPHRASE }`；`String getSecret(String connectionId, SecretKind kind)`、`void setSecret(String connectionId, SecretKind kind, String value)`、`void deleteSecrets(String connectionId)`；用 `PasswordSafe` 与 `CredentialAttributes("com.cloudtoolkit.ecsdeploy/" + connectionId + "/" + kind.name())` 存取，未找到返回 `null`。

`EcsConnectionState`：纯可序列化 POJO，字段 `List<EcsConnection> connections = new ArrayList<>()`，带 `@Tag` / `@Property` 注解；**不**实现 `PersistentStateComponent`。

`EcsConnectionsService`：`@Service(Service.Level.APP)` 且 `@State(name = "EcsConnections", storages = @Storage("cloud-toolkit-ecs.xml"))`，implements `PersistentStateComponent<EcsConnectionState>`；持有 `EcsConnectionState state`，实现 `getState()` / `loadState(EcsConnectionState)`；`static EcsConnectionsService getInstance()`；`List<EcsConnection> list()`、`EcsConnection findById(String id)`、`void upsert(EcsConnection connection, String secret)`、`void delete(String id)`；`upsert` 按 `id` 覆盖并写入 `CredentialStore`，`delete` 同时清除两种 `SecretKind`。`plugin.xml` 注册 `com.intellij.applicationService` 指向该类。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.settings.*" --no-daemon`
预期：PASS；1 个纯 JVM 用例与 3 个平台用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/settings/ src/test/java/com/cloudtoolkit/ecsdeploy/settings/ src/main/resources/
git commit -m "feat: persist named SSH connections with PasswordSafe credentials"
```

**Interfaces：** `CredentialStore.SecretKind`、`EcsConnectionsService.getInstance()/list()/findById()/upsert()/delete()/getState()`。**审查重点 #1** 的悬空 ID 行为由 `testDeletedConnectionReturnsNull` 与任务 7 的编辑器校验共同钉住。

---

### 任务 4：SSH 会话工厂、主机密钥策略与测试夹具

**文件：**

- 创建：`transfer/HostKeyPolicy.java`、`transfer/SshSessionFactory.java`、`transfer/TransferProgress.java`
- 测试：`support/TestSshServer.java`、`transfer/HostKeyPolicyTest.java`、`transfer/SshSessionFactoryTest.java`
- 修改：`src/main/resources/messages/EcsDeployBundle.properties`（认证与主机密钥错误文案）

- [ ] **步骤 1：编写失败的测试**

```java
// HostKeyPolicyTest
@Test void acceptsKnownHostKey() {
    HostKeyPolicy.write(knownHosts, "127.0.0.1", port, keyPair.getPublic());
    assertEquals(HostKeyPolicy.Decision.ACCEPT,
            HostKeyPolicy.check(knownHosts, "127.0.0.1", port, keyPair.getPublic()));
}
@Test void flagsUnknownHostKey() {
    assertEquals(HostKeyPolicy.Decision.UNKNOWN,
            HostKeyPolicy.check(emptyHosts, "127.0.0.1", port, keyPair.getPublic()));
}
@Test void rejectsChangedHostKey() {
    HostKeyPolicy.write(knownHosts, "127.0.0.1", port, keyPair.getPublic());
    assertEquals(HostKeyPolicy.Decision.CHANGED,
            HostKeyPolicy.check(knownHosts, "127.0.0.1", port, otherKeyPair.getPublic()));
}
@Test void skipVerificationNeverBypassesChangedKey() {
    HostKeyPolicy.write(knownHosts, "127.0.0.1", port, keyPair.getPublic());
    assertEquals(HostKeyPolicy.Decision.CHANGED,
            new HostKeyPolicy(true).evaluate(knownHosts, "127.0.0.1", port, otherKeyPair.getPublic()));
    assertEquals(HostKeyPolicy.Decision.ACCEPT,
            new HostKeyPolicy(true).evaluate(emptyHosts, "127.0.0.1", port, keyPair.getPublic()));
}

// SshSessionFactoryTest（TestSshServer 在本机随机端口启动）
@Test void opensSessionWithPassword() throws Exception {
    try (ClientSession s = factory.open(conn(AuthType.PASSWORD, "pw"), "pw")) { assertTrue(s.isAuthenticated()); }
}
@Test void reportsMissingPrivateKeyFile() {
    EcsConnection c = conn(AuthType.PRIVATE_KEY, ""); c.privateKeyPath = "/no/such/key.pem";
    assertThrows(SshAuthenticationException.class, () -> factory.open(c, null));
}
@Test void reportsMalformedPrivateKey(@TempDir Path dir) throws Exception {
    Path bad = Files.write(dir.resolve("key.pem"), "not a key".getBytes(UTF_8));
    EcsConnection c = conn(AuthType.PRIVATE_KEY, ""); c.privateKeyPath = bad.toString();
    assertThrows(SshAuthenticationException.class, () -> factory.open(c, null));
}
@Test void reportsMissingPassphrase(@TempDir Path dir) throws Exception {
    Path key = TestSshServer.encryptedPrivateKey(dir);
    EcsConnection c = conn(AuthType.PRIVATE_KEY, ""); c.privateKeyPath = key.toString(); c.hasPassphrase = true;
    assertThrows(SshAuthenticationException.class, () -> factory.open(c, null));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.transfer.*" --no-daemon`
预期：FAIL，`cannot find symbol`（`HostKeyPolicy` / `SshSessionFactory` / `TransferProgress` / `TestSshServer` 尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`HostKeyPolicy`：嵌套 `enum Decision { ACCEPT, UNKNOWN, CHANGED }`；`static Decision check(Path knownHosts, String host, int port, PublicKey key)`、`static void write(Path knownHosts, String host, int port, PublicKey key)`；`public HostKeyPolicy(boolean skipVerification)`（`skipVerification` 默认 false，来自 Settings）、`Decision evaluate(Path knownHosts, String host, int port, PublicKey key)`（`CHANGED` 一律返回 `CHANGED`，不受 skip 影响；`UNKNOWN` 且 skip 为 true 时返回 `ACCEPT`）、`ServerKeyVerifier verifier()`。`UNKNOWN` 且未跳过时由 UI 弹一次性确认对话框展示主机与指纹，确认后调用 `write`。

`SshSessionFactory`：`public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15)`、`AUTH_TIMEOUT = Duration.ofSeconds(15)`；`public ClientSession open(EcsConnection connection, String secret) throws IOException, SshConnectException, SshAuthenticationException`；按 `connection.authType` 选择密码或私钥认证，私钥从 `PathUtils.expandHome(privateKeyPath)` 读取、口令从 `secret` 取；文件缺失/损坏/口令错误统一包成 `SshAuthenticationException`（附原因），连接失败包成 `SshConnectException`。

`TransferProgress`：`record TransferProgress(long transferred, long total)` + `int percent()`（total 为 0 时返回 0）。

`TestSshServer`：MINA `SshServer` 在随机端口启动，密码认证 + 公钥认证可选，SFTP 子系统以 `root()` 为虚拟根。**命令执行使用 JVM 内的 `CommandFactory`，不调用系统 shell**：按 `;` 拆分并解释 `echo <text>`（stdout 输出一行 UTF-8 文本）、`echo-stderr <text>`、`exit <code>`、`seq <from> <to>`、`long-line <n>`（输出 n 个 `x` 的单行）、`sleep <ms>`（阻塞直到 channel 关闭）；每条命令记录到 `lastCommand()` / `commandCount()`。方法：`static TestSshServer start()`、`int port()`、`Path root()`、`String readOnlyDirectory()`（预置的不可写远端目录）、`String lastCommand()`、`int commandCount()`、`void stop()`、`static Path encryptedPrivateKey(Path dir)`。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.transfer.*" --no-daemon`
预期：PASS；`HostKeyPolicyTest` 四个用例与 `SshSessionFactoryTest` 四个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/transfer/ src/test/java/com/cloudtoolkit/ecsdeploy/ src/main/resources/
git commit -m "feat: add SSH session factory with known_hosts verification"
```

**Interfaces：** `SshSessionFactory.open(EcsConnection, String)`、`HostKeyPolicy.check/write/evaluate`、`TransferProgress(long,long)`、`TestSshServer.start()/port()/root()/readOnlyDirectory()/lastCommand()/commandCount()/stop()/encryptedPrivateKey(Path)` 与内存命令词汇。**审查重点 #3**（`rejectsChangedHostKey` + `skipVerificationNeverBypassesChangedKey`）与 **#4**（三个私钥/口令用例）由本任务钉住。

---

### 任务 5：SFTP 传输与远程命令执行

**文件：**

- 创建：`transfer/RemoteFileTransfer.java`、`transfer/RemoteCommandExecutor.java`
- 测试：`transfer/RemoteFileTransferTest.java`、`transfer/RemoteCommandExecutorTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
// RemoteFileTransferTest（TestSshServer 的 SFTP 虚拟根）
@Test void createsDirectoryAndUploadsAtomically(@TempDir Path dir) throws Exception {
    Path jar = Files.write(dir.resolve("app.jar"), "payload".getBytes(UTF_8));
    List<TransferProgress> seen = new ArrayList<>();
    transfer.upload(session, jar, "/opt/my app/deploy", seen::add);
    assertEquals("payload", Files.readString(server.root().resolve("opt/my app/deploy/app.jar")));
    assertTrue(seen.stream().anyMatch(p -> p.percent() == 100));
    assertEquals(0, Files.list(server.root().resolve("opt/my app/deploy"))
            .filter(p -> p.getFileName().toString().endsWith(".tmp")).count());
}
@Test void failsWhenRemoteDirectoryIsNotWritable(@TempDir Path dir) throws Exception {
    Path jar = Files.write(dir.resolve("app.jar"), "payload".getBytes(UTF_8));
    assertThrows(SftpTransferException.class,
            () -> transfer.upload(session, jar, server.readOnlyDirectory(), p -> {}));
}

// RemoteCommandExecutorTest（命令由任务 4 的内存 CommandFactory 解释）
@Test void splitsStdoutStderrAndReturnsExitCode() {
    List<String> out = new ArrayList<>(), err = new ArrayList<>();
    int code = executor.execute(session, "echo 部署成功; echo-stderr bad; exit 7",
            (isErr, line) -> (isErr ? err : out).add(line), () -> false);
    assertEquals(7, code);
    assertTrue(out.contains("部署成功"));
    assertTrue(err.contains("bad"));
}
@Test void carriesTailOnNonZeroExit() {
    RemoteCommandException e = assertThrows(RemoteCommandException.class,
            () -> executor.executeOrThrow(session, "seq 1 300; exit 3", (a,b) -> {}, () -> false));
    assertEquals(3, e.exitCode());
    assertEquals(200, e.tail().size());
    assertEquals("300", e.tail().get(199));
}
@Test void cancelMiddlewareClosesChannel() {
    assertThrows(DeploymentCanceledException.class,
            () -> executor.executeOrThrow(session, "sleep 30000", (a,b) -> {}, () -> true));
}
@Test void passesCommandVerbatimAndHandlesLongLine() {
    assertEquals(0, executor.execute(session, "echo 'a && b | c'", (a,b) -> {}, () -> false));
    assertEquals("echo 'a && b | c'", server.lastCommand());
    assertEquals(0, executor.execute(session, "long-line 100000", (a,b) -> {}, () -> false));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.transfer.Remote*Test" --no-daemon`
预期：FAIL，`cannot find symbol`（两个类尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`RemoteFileTransfer`：`public void upload(ClientSession session, Path local, String remoteDir, Consumer<TransferProgress> progress) throws IOException, SftpTransferException`；用 `SftpClientFactory` 建客户端、逐级 `mkdir -p`、写到 `<remoteDir>/<fileName>.<timestamp>.tmp` 后 rename 覆盖目标；每写一块回调 `progress`；远端无写权限/中断抛 `SftpTransferException`。

`RemoteCommandExecutor`：`public int execute(ClientSession session, String command, BiConsumer<Boolean,String> output, BooleanSupplier canceled) throws IOException, RemoteCommandException, DeploymentCanceledException`（`Boolean` 为 `isStderr`）；`public int executeOrThrow(ClientSession, String, BiConsumer<Boolean,String>, BooleanSupplier)` 额外在退出码非 0 时抛 `RemoteCommandException(exitCode, tail)`。两者在 `canceled` 为真时关闭 channel 并抛 `DeploymentCanceledException`；stdout/stderr 用 `OutputSanitizer.decodeUtf8` 按行解码，内部保留最后 200 行（`ArrayDeque`，满则移除头），单命令超时 5 分钟；命令按用户填写原样传给 exec channel，不改写、不转义。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.transfer.Remote*Test" --no-daemon`
预期：PASS；6 个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/transfer/Remote*.java src/test/java/com/cloudtoolkit/ecsdeploy/transfer/Remote*Test.java
git commit -m "feat: implement SFTP upload and streaming remote command execution"
```

**Interfaces：** `RemoteFileTransfer.upload(...)`, `RemoteCommandExecutor.execute(...)` / `executeOrThrow(...)`, `RemoteCommandException.exitCode()/tail()`。**审查重点 #5** 的 `cancelMiddlewareClosesChannel` 由本任务钉住；命令原样传递、中文输出与超长行由本任务覆盖。

---

### 任务 6：部署流水线、观察接口与 EcsSshTarget

**文件：**

- 创建：`deploy/DeployTarget.java`、`deploy/DeployObserver.java`、`deploy/DeployPipeline.java`、`deploy/EcsSshTarget.java`
- 测试：`deploy/DeployPipelineTest.java`、`deploy/EcsSshTargetTest.java`
- 修改：`src/main/resources/messages/EcsDeployBundle.properties`（7 个阶段名、成功/失败/取消文案）

- [ ] **步骤 1：编写失败的测试**

```java
// DeployPipelineTest（FakeDeployTarget 逐阶段记录，RecordingObserver 记录回调）
@Test void runsStagesInOrder() {
    pipeline.run(validContext());
    assertEquals(List.of(DeployStage.VALIDATE, DeployStage.CONNECT, DeployStage.PREPARE,
            DeployStage.UPLOAD, DeployStage.STOP, DeployStage.START, DeployStage.FINISH),
            observer.stages());
}
@Test void stopNonZeroDoesNotAbort() {
    target.stopExitCode = 1;
    pipeline.run(validContext());
    assertTrue(target.calls.contains("start"));
}
@Test void startNonZeroFailsWithExitCodeAndTail() {
    target.startExitCode = 3;
    RemoteCommandException e = assertThrows(RemoteCommandException.class, () -> pipeline.run(validContext()));
    assertEquals(3, e.exitCode());
    assertEquals(200, e.tail().size());
}
@Test void uploadFailureSkipsStart() {
    target.failOn = "upload";
    assertThrows(SftpTransferException.class, () -> pipeline.run(validContext()));
    assertFalse(target.calls.contains("start"));
    assertTrue(target.closed);
}
@Test void validationFailureSkipsConnect() {
    assertThrows(ConfigurationException.class, () -> pipeline.run(contextWithMissingArtifact()));
    assertFalse(target.calls.contains("prepare"));
}
@Test void cancelCallsCloseAndStopsBeforeNextStage() {
    // FakeDeployTarget.upload 被调用后把 canceled 置 true，pipeline 的 canceled 指向同一个 AtomicBoolean
    target.cancelAfter = "upload";
    assertThrows(DeploymentCanceledException.class, () -> pipeline.run(validContext()));
    assertTrue(target.closed);
    assertFalse(target.calls.contains("start"));
    assertEquals(DeployStage.FINISH, observer.stages().get(observer.stages().size() - 1));
}

// EcsSshTargetTest（TestSshServer + 真实 transfer 类）
@Test void uploadsFileAndRunsStartCommand() throws Exception {
    EcsSshTarget t = new EcsSshTarget(factory, transfer, executor, new HostKeyPolicy(true));
    DeployContext ctx = validContext();
    t.prepare(ctx);
    t.upload(ctx, p -> {}, () -> false);
    t.start(ctx, (e, l) -> {}, () -> false);
    t.close();
    assertTrue(Files.exists(server.root().resolve("opt/app/app.jar")));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.deploy.*Test" --no-daemon`
预期：FAIL，`cannot find symbol`（`DeployTarget` / `DeployObserver` / `DeployPipeline` / `EcsSshTarget` 尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`DeployTarget`：`void prepare(DeployContext ctx)`、`void upload(DeployContext ctx, Consumer<TransferProgress> progress, BooleanSupplier canceled)`、`int stop(DeployContext ctx, BiConsumer<Boolean,String> output, BooleanSupplier canceled)`、`int start(DeployContext ctx, BiConsumer<Boolean,String> output, BooleanSupplier canceled)`、`void close()`；各方法声明抛出 `DeployException` 子类。

`DeployObserver`：`void onStage(DeployStage stage)`、`void onOutput(boolean stderr, String line)`、`void onProgress(TransferProgress progress)`、`void onFinished(boolean success)`。

`DeployPipeline`：`public DeployPipeline(DeployTarget target, DeployObserver observer, BooleanSupplier canceled)`；`public void run(DeployContext ctx) throws DeployException` 依次 `ctx.validate()` → `observer.onStage(...)` → 调用 target 的 `prepare/upload/stop/start`，把构造器的 `canceled` 原样传给三个带 `canceled` 参数的方法；每个阶段前检查 `canceled`；`start` 非零退出抛 `RemoteCommandException`，`stop` 非零只记警告；`finally` 中调用 `target.close()` 并 `observer.onStage(FINISH)`；取消抛 `DeploymentCanceledException`。

`EcsSshTarget`：`public EcsSshTarget(SshSessionFactory factory, RemoteFileTransfer transfer, RemoteCommandExecutor executor, HostKeyPolicy policy)` implements `DeployTarget`；`prepare` 建会话（`factory.open`），`upload` 调 `transfer.upload`，`stop` 在 `stopCommand` 空白时返回 0 且不执行命令、否则调 `executor.execute(...)` 返回退出码（非零不抛），`start` 调 `executor.executeOrThrow(...)`，`close` 关闭会话。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.deploy.*Test" --no-daemon`
预期：PASS；7 个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/deploy/ src/test/java/com/cloudtoolkit/ecsdeploy/deploy/ src/main/resources/
git commit -m "feat: add deploy pipeline with cancellation and ECS SSH target"
```

**Interfaces：** `DeployTarget` 五方法（`upload/stop/start` 带 `BooleanSupplier canceled`）、`DeployObserver` 四个回调、`DeployPipeline(target, observer, canceled).run(ctx) throws DeployException`、`EcsSshTarget(factory, transfer, executor, policy)`。**审查重点 #2** 的 `validationFailureSkipsConnect` 与 **#5** 的 `cancelCallsCloseAndStopsBeforeNextStage` 由本任务钉住。

---

### 任务 7：Run Configuration 与设置编辑器

**文件：**

- 创建：`ui/EcsDeployConfigurationType.java`、`ui/EcsDeployRunConfiguration.java`、`ui/EcsDeployRunConfigurationOptions.java`、`ui/EcsDeploySettingsEditor.java`
- 测试：`ui/EcsDeploySettingsEditorValidationTest.java`、`ui/EcsDeployRunConfigurationOptionsTest.java`（`LightPlatformTestCase`）
- 修改：`src/main/resources/META-INF/plugin.xml`（注册 `com.intellij.configurationType`）

- [ ] **步骤 1：编写失败的测试**

```java
// EcsDeploySettingsEditorValidationTest（静态校验方法，不实例化 Swing 组件）
public void testReportsMissingConnection() {
    EcsDeployRunConfigurationOptions o = validOptions(); o.setConnectionId("deleted-id");
    assertTrue(EcsDeploySettingsEditor.validate(o, service).contains("连接不存在，请重新选择"));
}
public void testReportsRelativeRemoteDirectory() {
    EcsDeployRunConfigurationOptions o = validOptions(); o.setRemoteDirectory("opt/app");
    assertTrue(EcsDeploySettingsEditor.validate(o, service).stream().anyMatch(s -> s.contains("绝对路径")));
}
public void testReportsEmptyStartCommand() {
    EcsDeployRunConfigurationOptions o = validOptions(); o.setStartCommand("");
    assertTrue(EcsDeploySettingsEditor.validate(o, service).stream().anyMatch(s -> s.contains("start")));
}
public void testStopCommandMayBeEmpty() {
    EcsDeployRunConfigurationOptions o = validOptions(); o.setStopCommand("");
    assertTrue(EcsDeploySettingsEditor.validate(o, service).isEmpty());
}

// EcsDeployRunConfigurationOptionsTest
public void testOptionsRoundTrip() {
    EcsDeployRunConfigurationOptions o = validOptions();
    Element xml = XmlSerializer.serialize(o);
    EcsDeployRunConfigurationOptions copy = XmlSerializer.deserialize(xml, EcsDeployRunConfigurationOptions.class);
    assertEquals("c1", copy.getConnectionId());
    assertEquals("/opt/app", copy.getRemoteDirectory());
    assertFalse(xml.serializeToString().contains("SUPER_SECRET"));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.ui.*Test" --no-daemon`
预期：FAIL，`cannot find symbol`（四个 UI 类尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`EcsDeployConfigurationType extends ConfigurationTypeBase`：ID `EcsDeployRunConfiguration`，显示名取 bundle 的 `configuration.type.name`，工厂返回 `new EcsDeployRunConfiguration(project, factory, name)`。

`EcsDeployRunConfigurationOptions extends RunConfigurationOptions`：用 `StoredProperty<String>` 持久化 `connectionId`、`artifactPath`、`remoteDirectory`（默认 `/opt/app`）、`stopCommand`、`startCommand`；暴露 5 组 `getXxx()/setXxx()`（均为 `String`，`null` 归一为 `""`）；不得有凭据字段。

`EcsDeployRunConfiguration extends RunConfigurationBase<EcsDeployRunConfigurationOptions>`：`getConfigurationEditor()` 返回 `new EcsDeploySettingsEditor()`；`public DeployContext toContext()` 从 Options + `EcsConnectionsService.findById` + `CredentialStore.getSecret` 组装，找不到连接时抛 `ConfigurationException("连接不存在，请重新选择")`；`getState(Executor, Environment)` 本任务返回 `null`，任务 8 创建 `EcsDeployRunProfileState` 后改为返回其实例，本任务测试不触发 `getState`。

`EcsDeploySettingsEditor extends SettingsEditor<EcsDeployRunConfiguration>`：连接下拉（`EcsConnectionsService.list()`，空列表显示跳转 Settings 链接）、本地 jar/war 文件选择器、远端目录输入、stop/start 命令输入；`public static List<String> validate(EcsDeployRunConfigurationOptions o, EcsConnectionsService svc)` 返回中文错误列表，规则见测试；`applyEditorTo` / `resetEditorFrom` 读写 Options。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.ui.*Test" --no-daemon`
预期：PASS；5 个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/ui/EcsDeploy*.java src/test/java/com/cloudtoolkit/ecsdeploy/ui/ src/main/resources/
git commit -m "feat: add Deploy to ECS run configuration and settings editor"
```

**Interfaces：** `EcsDeployRunConfigurationOptions` 的 5 组 getter/setter、`EcsDeployRunConfiguration.toContext()`、`EcsDeploySettingsEditor.validate(options, service)`。**审查重点 #1** 的编辑器提示由 `testReportsMissingConnection` 钉住。

---

### 任务 8：部署 Console、取消传播与执行入口

**文件：**

- 创建：`ui/DeployConsole.java`、`ui/DeployProcessHandler.java`、`ui/DeployNotifier.java`、`ui/EcsDeployRunProfileState.java`
- 测试：`ui/DeployConsoleTest.java`、`ui/DeployProcessHandlerTest.java`（纯 JUnit 5，只测静态方法与取消语义，不启动 IDE）
- 修改：`ui/EcsDeployRunConfiguration.java`（`getState` 返回 `EcsDeployRunProfileState`）、`src/main/resources/META-INF/plugin.xml`（注册 `com.intellij.notificationGroup`）

- [ ] **步骤 1：编写失败的测试**

```java
// DeployProcessHandlerTest
@Test void destroyCancelsExactlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    DeployProcessHandler h = new DeployProcessHandler(calls::incrementAndGet);
    h.destroyProcess(); h.destroyProcess();
    assertEquals(1, calls.get());
}

// DeployConsoleTest
@Test void cancelledIsNotRenderedAsError() {
    assertEquals("已取消", DeployConsole.resultText(DeployConsole.Result.CANCELLED));
    assertEquals("部署失败", DeployConsole.resultText(DeployConsole.Result.FAILURE));
}
@Test void stageTitlesAreLocalizedForAllSevenStages() {
    for (DeployStage s : DeployStage.values()) {
        assertFalse(DeployConsole.stageTitle(s).isBlank());
        assertFalse(DeployConsole.stageTitle(s).equals(s.name()));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.ui.Deploy*Test" --no-daemon`
预期：FAIL，`cannot find symbol`（`DeployConsole` / `DeployProcessHandler` 尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`DeployConsole implements DeployObserver`：嵌套 `enum Result { SUCCESS, FAILURE, CANCELLED }`；`public DeployConsole(Project project, Collection<String> secrets)`；`public JComponent getComponent()`；`static String resultText(Result r)` 返回「部署成功」/「部署失败」/「已取消」；`static String stageTitle(DeployStage stage)` 返回 7 个阶段各自的中文标题；实例方法 `onStage` 输出 `stageTitle`、`onOutput` **必须先经 `OutputSanitizer.sanitize(line, secrets)` 再写入 `ConsoleView`**（错误红色、警告黄色）、`onProgress` 更新进度、`onFinished` 输出汇总。

`DeployProcessHandler extends ProcessHandler`：`public DeployProcessHandler(Runnable cancel)`；`destroyProcessImpl()` 用 `AtomicBoolean` 保证 `cancel` 只执行一次并 `notifyProcessTerminated(...)`；`startNotify()` / `detachProcessImpl()` 空实现。

`DeployNotifier`：`static void success(Project p, String host, String fileName)`、`static void failure(Project p, String message)`；使用 `plugin.xml` 注册的 notification group。

`EcsDeployRunProfileState implements RunProfileState`：`public EcsDeployRunProfileState(EcsDeployRunConfiguration configuration)`；`execute(Executor executor, ProgramRunner runner)` 调 `configuration.toContext()`，构造 `DeployConsole` + `DeployProcessHandler` + `EcsSshTarget` + `DeployPipeline`，把同一个 `ProgressIndicator::isCanceled` 同时作为 `DeployProcessHandler` 的 cancel 回调与 `DeployPipeline` 的 `BooleanSupplier`；在 `Task.Backgroundable` 中运行流水线，捕获 `DeployException` 子类映射到 Console 与通知；返回 `DefaultExecutionResult(console.getComponent(), handler)`；`ConfigurationException` 在编排前直接反馈，不启动后台任务。所有 UI 更新走 `invokeLater`。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.ui.Deploy*Test" --no-daemon`
预期：PASS；3 个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/ui/ src/test/java/com/cloudtoolkit/ecsdeploy/ui/Deploy*Test.java src/main/resources/
git commit -m "feat: wire deploy console, cancellation and execution entry point"
```

**Interfaces：** `DeployConsole(Project, Collection<String>) implements DeployObserver`、`DeployConsole.resultText/stageTitle`、`DeployProcessHandler(Runnable)`、`EcsDeployRunProfileState(EcsDeployRunConfiguration)`。**审查重点 #5** 的 `destroyCancelsExactlyOnce` 由本任务钉住。

---

### 任务 9：Settings 连接配置页与测试连接

**文件：**

- 创建：`settings/EcsConnectionsConfigurable.java`
- 测试：`settings/EcsConnectionsConfigurableTest.java`（`LightPlatformTestCase`）
- 修改：`src/main/resources/META-INF/plugin.xml`（注册 `com.intellij.applicationConfigurable`）、`src/main/resources/messages/EcsDeployBundle.properties`（主机密钥确认与跳过校验风险提示文案）

- [ ] **步骤 1：编写失败的测试**

```java
public void testConnectionSucceedsWithCorrectPassword() {
    assertEquals("ok", EcsConnectionsConfigurable.testConnection(conn("pw"), "pw"));
}
public void testConnectionReportsAuthenticationFailure() {
    assertTrue(EcsConnectionsConfigurable.testConnection(conn("pw"), "wrong").contains("认证"));
}
public void testConnectionNeverPerformsRemoteWrites() {
    EcsConnectionsConfigurable.testConnection(conn("pw"), "pw");
    assertEquals(0, server.commandCount());
}
public void testAddEditDeletePersistsOnlyNonSensitiveFields() {
    configurable.add(conn("pw"), "pw");
    EcsConnectionState state = service.getState();
    Element xml = XmlSerializer.serialize(state);
    assertFalse(xml.serializeToString().contains("pw"));
    configurable.delete("c1");
    assertTrue(configurable.list().isEmpty());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurableTest" --no-daemon`
预期：FAIL，`cannot find symbol`（`EcsConnectionsConfigurable` 尚未创建）。

- [ ] **步骤 3：实现类型与签名**

`EcsConnectionsConfigurable implements Configurable`：`public static String testConnection(EcsConnection connection, String secret)` 用 `SshSessionFactory.open` 建立会话、认证成功后立即 `close()`，返回 `"ok"`；失败按 `SshConnectException` / `SshAuthenticationException` 映射中文原因；**不执行任何远程写命令**。`add(EcsConnection, String)`、`edit(EcsConnection, String)`、`delete(String)`、`list()` 委托 `EcsConnectionsService`。UI 为连接表格 + 新增/编辑/删除 + 「测试连接」按钮（后台线程调用 `testConnection`，结果走通知）+「跳过主机密钥校验」复选框（默认 false，勾选时显示 bundle 中的风险提示）；「测试连接」使用 `new HostKeyPolicy(skipVerification)`，未知主机弹一次性确认对话框展示主机与指纹，确认后 `HostKeyPolicy.write`。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurableTest" --no-daemon`
预期：PASS；4 个用例通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/cloudtoolkit/ecsdeploy/settings/EcsConnectionsConfigurable.java src/test/java/com/cloudtoolkit/ecsdeploy/settings/EcsConnectionsConfigurableTest.java src/main/resources/
git commit -m "feat: add SSH connection settings page with connection test"
```

**Interfaces：** `EcsConnectionsConfigurable.testConnection(EcsConnection, String)` 返回 `"ok"` 或中文错误原因；`add/edit/delete/list()`。

---

### 任务 10：注册补全、全量验证与真机验收

**文件：**

- 修改：`src/main/resources/META-INF/plugin.xml`（核对全部扩展点）、`README.md`（补验收清单与 Windows 命令）、`build.gradle.kts`（如任务 1 有遗留配置偏差）
- 测试：无新增单元测试；本任务交付物是可安装产物与验收记录

- [ ] **步骤 1：核对插件声明完整性**

`plugin.xml` 必须含 `<idea-version since-build="262"/>`、两个 `<depends>`，以及 `applicationService`（`EcsConnectionsService`）、`applicationConfigurable`（`EcsConnectionsConfigurable`）、`configurationType`（`EcsDeployConfigurationType`）、`notificationGroup`（部署结果）四项注册；逐项对照规格第 10 节。

- [ ] **步骤 2：全量测试与打包**

运行：`.\gradlew.bat clean test buildPlugin verifyPlugin --no-daemon`
预期：全部 BUILD SUCCESSFUL；测试报告 `build/reports/tests/test/index.html` 全绿；`verifyPlugin` 对 `2026.2` 无 error；产出 `build/distributions/*.zip` 且 `lib/` 无重复 slf4j 绑定。

- [ ] **步骤 3：runIde 冒烟**

运行：`.\gradlew.bat runIde --no-daemon`
预期：IDEA 262 沙箱启动，Settings 中出现连接配置页，Run Configuration 列表出现 `Cloud Toolkit: Deploy to ECS`，插件加载无 error。

- [ ] **步骤 4：真实 ECS 人工验收**

按规格第 9 节逐条执行：新增命名连接并「测试连接」成功；新建 Run Configuration 并把 Maven/Gradle `package` 加入 Before launch；执行一次部署，确认远端出现新产物、进程被重启、Console 有分阶段日志与上传进度、结束有结果通知；填错密码与断网时给出分类提示且 IDE 可交互；部署中点停止可取消且会话关闭。凭据只在本机 Settings 输入，不写入任何文件。把 6 条结果记入 `README.md` 的验收清单（只记通过/失败，不记凭据）。

- [ ] **步骤 5：Commit**

```bash
git add README.md src/main/resources/META-INF/plugin.xml build.gradle.kts
git commit -m "chore: finalize plugin registration and record 262 acceptance results"
```

---

## 自检结果

**规格覆盖度：** 规格 3 节约束 → 全局约束与任务 1；4 节架构与文件结构 → 文件结构表与任务 1-9；5.1 连接与凭据 → 任务 3/9；5.2 Run Configuration → 任务 7；5.3 传输与执行 → 任务 5；5.4 流水线 → 任务 6；5.5 日志与通知 → 任务 8；6 节错误分类 → 任务 2/4/5/6；7 节安全 → 任务 2（`toString`）、3（不落盘）、4（known_hosts）、8（脱敏）；8 节测试 → 各任务测试步骤；9 节验收 → 任务 10；10 节依赖 → 任务 1；11 节风险 → 任务 1/4/5/10。无遗漏。

**步骤扫描：** 每个测试步骤给出测试名与断言；每个代码步骤给出确切签名与规格取值，函数体交由实现者；验证步骤给出命令与预期。任务 1 与任务 10 无单元测试，其交付物分别是构建链路与可安装产物，验证方式为构建命令与人工验收。

**类型一致性：** `DeployContext` getter、`DeployTarget` 五方法（三个带 `BooleanSupplier canceled`）、`DeployObserver` 四回调、`DeployPipeline.run(...) throws DeployException`、`EcsConnectionsService.getState()`、`CredentialStore.SecretKind`、`TransferProgress(long,long)`、`RemoteCommandException.exitCode()/tail()`、`EcsDeployRunConfigurationOptions` 五组 getter/setter，在定义任务与引用任务中名称一致；`TestSshServer` 的内存命令词汇与任务 5 测试命令一致。

**审查重点：** #1 → 任务 2/3/7；#2 → 任务 2/6；#3、#4 → 任务 4；#5 → 任务 5/6/8。五条均有对应测试。

**比例：** 计划篇幅约为规格的 2 倍，代码块集中在测试断言与签名，未抄写函数体。
