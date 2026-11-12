package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteFileTransfer;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.cloudtoolkit.ecsdeploy.transfer.TransferProgress;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class EcsSshTarget implements DeployTarget {

    private final SshSessionFactory factory;
    private final RemoteFileTransfer transfer;
    private final RemoteCommandExecutor executor;
    private ClientSession session;

    public EcsSshTarget(SshSessionFactory factory, RemoteFileTransfer transfer,
                        RemoteCommandExecutor executor) {
        this.factory = Objects.requireNonNull(factory);
        this.transfer = Objects.requireNonNull(transfer);
        this.executor = Objects.requireNonNull(executor);
    }

    /** The host key policy of the session - the same one the factory verifies the keys with. */
    public HostKeyPolicy policy() {
        return factory.hostKeyPolicy();
    }

    @Override
    public void prepare(DeployContext ctx) throws DeployException {
        try {
            session = factory.open(ctx.connection(), ctx.secret());
        } catch (IOException e) {
            throw new DeployException.SshConnectException("无法连接 ECS", e);
        }
    }

    @Override
    public int beforeDeploy(DeployContext ctx, BiConsumer<Boolean, String> output,
                            BooleanSupplier canceled) throws DeployException {
        return runOptional(ctx.beforeDeployCommand(), output, canceled, "部署前命令执行失败");
    }

    @Override
    public void upload(DeployContext ctx, Consumer<TransferProgress> progress,
                       BooleanSupplier canceled) throws DeployException {
        try {
            transfer.upload(session, ctx.artifactPath(), ctx.remoteDirectory(), progress);
        } catch (IOException e) {
            throw new DeployException.SftpTransferException("SFTP 上传失败", e);
        }
    }

    @Override
    public int stop(DeployContext ctx, BiConsumer<Boolean, String> output,
                    BooleanSupplier canceled) throws DeployException {
        if (ctx.stopCommand() == null || ctx.stopCommand().isBlank()) {
            return 0;
        }
        try {
            return executor.execute(session, ctx.stopCommand(), output, canceled);
        } catch (IOException e) {
            throw new DeployException("stop 命令执行失败", e);
        }
    }

    @Override
    public int start(DeployContext ctx, BiConsumer<Boolean, String> output,
                     BooleanSupplier canceled) throws DeployException {
        if (ctx.startCommand() == null || ctx.startCommand().isBlank()) {
            return 0;
        }
        try {
            return executor.executeOrThrow(session, ctx.startCommand(), output, canceled);
        } catch (IOException e) {
            throw new DeployException("start 命令执行失败", e);
        }
    }

    @Override
    public int afterDeploy(DeployContext ctx, BiConsumer<Boolean, String> output,
                           BooleanSupplier canceled) throws DeployException {
        return runOptional(ctx.afterDeployCommand(), output, canceled, "部署后命令执行失败");
    }

    /** Runs a command when it is configured, otherwise does nothing. */
    private int runOptional(String command, BiConsumer<Boolean, String> output,
                            BooleanSupplier canceled, String errorMessage) throws DeployException {
        if (command == null || command.isBlank()) {
            return 0;
        }
        try {
            return executor.execute(session, command, output, canceled);
        } catch (IOException e) {
            throw new DeployException(errorMessage, e);
        }
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (IOException ignored) {
                // best effort session cleanup
            }
            session = null;
        }
    }
}
