package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.deploy.DeployContext;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.ConfigurationException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployPipeline;
import com.cloudtoolkit.ecsdeploy.deploy.EcsSshTarget;
import com.cloudtoolkit.ecsdeploy.deploy.LocalBuilder;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.settings.HostKeyPrompts;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicies;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteFileTransfer;
import com.cloudtoolkit.ecsdeploy.transfer.SshConfigResolver;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.cloudtoolkit.ecsdeploy.ui.terminal.SshTerminalOpener;
import com.cloudtoolkit.ecsdeploy.ui.terminal.SshTtyConnector;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EcsDeployRunProfileState implements RunProfileState {

    private final EcsDeployRunConfiguration configuration;

    public EcsDeployRunProfileState(EcsDeployRunConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public @Nullable ExecutionResult execute(@NotNull Executor executor,
                                             @NotNull ProgramRunner<?> runner) throws ExecutionException {
        Project project = configuration.getProject();
        EcsDeployRunConfigurationOptions options = configuration.getOptions();

        List<EcsConnection> hosts = resolveHosts(options);
        if (hosts.isEmpty()) {
            String message = EcsDeployMessages.message("validation.connection.missing");
            invokeLater(() -> DeployNotifier.failure(project, message));
            throw new ExecutionException(message);
        }

        DeployConsole console = new DeployConsole(project, secretsOf(hosts));
        AtomicBoolean stopRequested = new AtomicBoolean();
        DeployProcessHandler handler = new DeployProcessHandler(() -> stopRequested.set(true));

        new Task.Backgroundable(project, "部署到 ECS") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                SshSessionFactory factory = new SshSessionFactory(HostKeyPolicies.create(), HostKeyPrompts.TRUST);
                try {
                    validateHosts(hosts);
                    Path artifact = resolveArtifact(project, options, console);
                    for (EcsConnection host : hosts) {
                        if (isCancelled(indicator, stopRequested)) {
                            break;
                        }
                        console.onOutput(false, "===== 部署到 " + host.host + " =====");
                        deploy(factory, console, options, host, artifact, indicator, stopRequested);
                    }
                    if (isCancelled(indicator, stopRequested)) {
                        console.markCancelled();
                    } else {
                        openTerminals(project, options, hosts);
                        invokeLater(() -> DeployNotifier.success(project, hosts.get(0).host,
                                artifact.getFileName().toString()));
                    }
                } catch (DeploymentCanceledException e) {
                    console.markCancelled();
                } catch (DeployException e) {
                    if (isCancelled(indicator, stopRequested)) {
                        console.markCancelled();
                    } else {
                        invokeLater(() -> DeployNotifier.failure(project, e.getMessage()));
                    }
                } finally {
                    factory.close();
                    handler.finish();
                    console.finish();
                }
            }
        }.queue();

        return new DefaultExecutionResult(console.getExecutionConsole(), handler);
    }

    /** Deploys the artifact to a single host. */
    private static void deploy(SshSessionFactory factory, DeployConsole console,
                               EcsDeployRunConfigurationOptions options, EcsConnection host,
                               Path artifact, ProgressIndicator indicator,
                               AtomicBoolean stopRequested) throws DeployException {
        String secret = EcsConnectionsService.getInstance().getSecret(host.id);
        DeployContext context = new DeployContext(host, secret, artifact,
                options.getRemoteDirectory(), options.getStopCommand(), options.getStartCommand(),
                options.getBeforeDeployCommand(), options.getAfterDeployCommand());
        EcsSshTarget target = new EcsSshTarget(factory, new RemoteFileTransfer(),
                new RemoteCommandExecutor());
        DeployPipeline pipeline = new DeployPipeline(target, console,
                () -> stopRequested.get() || indicator.isCanceled());
        pipeline.run(context);
    }

    /** Fails fast when a target host has no usable SSH credentials. */
    private static void validateHosts(List<EcsConnection> hosts) throws ConfigurationException {
        for (EcsConnection host : hosts) {
            boolean hasUser = host.username != null && !host.username.isBlank();
            if (!hasUser && !SshConfigResolver.providesUser(host.host)) {
                throw new ConfigurationException("目标主机 " + host.host
                        + " 未配置 SSH 凭据（缺少 SSH 配置），请在 Dashboard 的「属性」中重新选择或创建 SSH 配置");
            }
        }
    }

    /** Resolves the artifact to upload: either the configured file or a fresh local build. */
    private static Path resolveArtifact(Project project, EcsDeployRunConfigurationOptions options,
                                         DeployConsole console) throws DeployException {
        String buildType = options.getBuildType();
        if (EcsDeployRunConfigurationOptions.BUILD_MAVEN.equals(buildType)
                || EcsDeployRunConfigurationOptions.BUILD_GRADLE.equals(buildType)) {
            return LocalBuilder.build(project, buildType, console);
        }
        String artifact = options.getArtifactPath().trim();
        if (artifact.isEmpty()) {
            throw new ConfigurationException(EcsDeployMessages.message("validation.artifact"));
        }
        return Path.of(artifact);
    }

    /** Opens a terminal tab for every target host when the configuration asks for it. */
    private static void openTerminals(Project project, EcsDeployRunConfigurationOptions options,
                                      List<EcsConnection> hosts) {
        boolean openTerminal = options.isOpenTerminal();
        boolean autoOpen = options.isAutoOpenAfterDeploy();
        if (!openTerminal && !autoOpen) {
            return;
        }
        for (EcsConnection host : hosts) {
            try {
                String secret = EcsConnectionsService.getInstance().getSecret(host.id);
                String startup = autoOpen ? options.getAfterDeployCommand() : null;
                SshTtyConnector connector = new SshTtyConnector(host, secret,
                        host.host + ":" + host.port, startup);
                invokeLater(() -> SshTerminalOpener.open(project, connector, host.host));
            } catch (Exception e) {
                invokeLater(() -> DeployNotifier.failure(project,
                        "无法打开终端 " + host.host + ": " + e.getMessage()));
            }
        }
    }

    /** The hosts selected in the configuration (falls back to the legacy single connection). */
    private static List<EcsConnection> resolveHosts(EcsDeployRunConfigurationOptions options) {
        EcsConnectionsService service = EcsConnectionsService.getInstance();
        List<EcsConnection> hosts = new ArrayList<>();
        for (String hostId : options.getTargetHostIdList()) {
            EcsConnection connection = service.findById(hostId);
            if (connection != null) {
                hosts.add(connection);
            }
        }
        if (hosts.isEmpty()) {
            String connectionId = options.getConnectionId();
            if (connectionId != null && !connectionId.isBlank()) {
                EcsConnection connection = service.findById(connectionId);
                if (connection != null) {
                    hosts.add(connection);
                }
            }
        }
        return hosts;
    }

    private static boolean isCancelled(ProgressIndicator indicator, AtomicBoolean stopRequested) {
        return stopRequested.get() || indicator.isCanceled();
    }

    private static Collection<String> secretsOf(List<EcsConnection> hosts) {
        List<String> secrets = new ArrayList<>();
        for (EcsConnection host : hosts) {
            String secret = EcsConnectionsService.getInstance().getSecret(host.id);
            if (secret != null && !secret.isBlank()) {
                secrets.add(secret);
            }
        }
        return secrets;
    }

    private static void invokeLater(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }
}
