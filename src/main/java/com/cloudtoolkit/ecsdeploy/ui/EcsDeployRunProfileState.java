package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.deploy.DeployContext;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.ConfigurationException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployPipeline;
import com.cloudtoolkit.ecsdeploy.deploy.EcsSshTarget;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteFileTransfer;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
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
        DeployContext context;
        try {
            context = configuration.toContext();
        } catch (ConfigurationException e) {
            invokeLater(() -> DeployNotifier.failure(project, e.getMessage()));
            throw new ExecutionException(e.getMessage());
        }

        DeployConsole console = new DeployConsole(project, secretsOf(context));
        AtomicBoolean stopRequested = new AtomicBoolean();
        DeployProcessHandler handler = new DeployProcessHandler(() -> stopRequested.set(true));

        new Task.Backgroundable(project, "部署到 ECS") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                SshSessionFactory factory = new SshSessionFactory();
                try {
                    EcsSshTarget target = new EcsSshTarget(factory, new RemoteFileTransfer(),
                            new RemoteCommandExecutor(), new HostKeyPolicy());
                    DeployPipeline pipeline = new DeployPipeline(target, console,
                            () -> stopRequested.get() || indicator.isCanceled());
                    pipeline.run(context);
                    if (isCancelled(indicator, stopRequested)) {
                        console.markCancelled();
                    } else {
                        invokeLater(() -> DeployNotifier.success(project, context.connection().host,
                                context.artifactPath().getFileName().toString()));
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

    private static boolean isCancelled(ProgressIndicator indicator, AtomicBoolean stopRequested) {
        return stopRequested.get() || indicator.isCanceled();
    }

    private static Collection<String> secretsOf(DeployContext context) {
        return context.secret() == null ? List.of() : List.of(context.secret());
    }

    private static void invokeLater(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }
}
