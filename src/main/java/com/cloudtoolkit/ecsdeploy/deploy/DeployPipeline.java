package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.RemoteCommandException;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class DeployPipeline {

    private final DeployTarget target;
    private final DeployObserver observer;
    private final BooleanSupplier canceled;

    public DeployPipeline(DeployTarget target, DeployObserver observer, BooleanSupplier canceled) {
        this.target = Objects.requireNonNull(target);
        this.observer = Objects.requireNonNull(observer);
        this.canceled = Objects.requireNonNull(canceled);
    }

    public void run(DeployContext ctx) throws DeployException {
        boolean success = false;
        try {
            observer.onStage(DeployStage.VALIDATE);
            ctx.validate();
            checkCancelled();

            observer.onStage(DeployStage.CONNECT);
            checkCancelled();

            observer.onStage(DeployStage.PREPARE);
            target.prepare(ctx);
            checkCancelled();

            observer.onStage(DeployStage.UPLOAD);
            target.upload(ctx, observer::onProgress, canceled);
            checkCancelled();

            observer.onStage(DeployStage.STOP);
            int stopExitCode = target.stop(ctx, observer::onOutput, canceled);
            if (stopExitCode != 0) {
                observer.onOutput(true, "stop 命令退出码 " + stopExitCode + "，继续部署");
            }
            checkCancelled();

            observer.onStage(DeployStage.START);
            int startExitCode = target.start(ctx, observer::onOutput, canceled);
            if (startExitCode != 0) {
                throw new RemoteCommandException(startExitCode, List.of());
            }
            success = true;
        } finally {
            target.close();
            observer.onStage(DeployStage.FINISH);
            observer.onFinished(success);
        }
    }

    private void checkCancelled() throws DeploymentCanceledException {
        if (canceled.getAsBoolean()) {
            throw new DeploymentCanceledException();
        }
    }
}
