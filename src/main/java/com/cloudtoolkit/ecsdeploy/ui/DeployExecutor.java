package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.deploy.DeployContext;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployPipeline;
import com.cloudtoolkit.ecsdeploy.deploy.EcsSshTarget;
import com.cloudtoolkit.ecsdeploy.settings.HostKeyPrompts;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicies;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteFileTransfer;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a {@link DeployContext} against an ECS host, streaming output into a Run tool window.
 */
public final class DeployExecutor {

    private DeployExecutor() {
    }

    public static void run(Project project, DeployContext context) {
        DeployConsole console = new DeployConsole(project, secretsOf(context));
        AtomicBoolean stopRequested = new AtomicBoolean();
        DeployProcessHandler handler = new DeployProcessHandler(() -> stopRequested.set(true));

        new Task.Backgroundable(project, "部署到 ECS") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                SshSessionFactory factory = new SshSessionFactory(HostKeyPolicies.create(), HostKeyPrompts.TRUST);
                try {
                    EcsSshTarget target = new EcsSshTarget(factory, new RemoteFileTransfer(),
                            new RemoteCommandExecutor());
                    DeployPipeline pipeline = new DeployPipeline(target, console,
                            () -> stopRequested.get() || indicator.isCanceled());
                    pipeline.run(context);
                    if (isCancelled(indicator, stopRequested)) {
                        console.markCancelled();
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> DeployNotifier.success(
                                project, context.connection().host,
                                context.artifactPath().getFileName().toString()));
                    }
                } catch (DeploymentCanceledException e) {
                    console.markCancelled();
                } catch (DeployException e) {
                    if (isCancelled(indicator, stopRequested)) {
                        console.markCancelled();
                    } else {
                        ApplicationManager.getApplication().invokeLater(
                                () -> DeployNotifier.failure(project, e.getMessage()));
                    }
                } finally {
                    factory.close();
                    handler.finish();
                    console.finish();
                }
            }
        }.queue();
    }

    private static boolean isCancelled(ProgressIndicator indicator, AtomicBoolean stopRequested) {
        return stopRequested.get() || indicator.isCanceled();
    }

    private static Collection<String> secretsOf(DeployContext context) {
        return context.secret() == null ? List.of() : List.of(context.secret());
    }
}
