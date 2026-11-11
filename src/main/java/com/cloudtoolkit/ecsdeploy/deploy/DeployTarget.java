package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.transfer.TransferProgress;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface DeployTarget {

    void prepare(DeployContext ctx) throws DeployException;

    /** Runs the command configured as "Before deploy". */
    int beforeDeploy(DeployContext ctx, BiConsumer<Boolean, String> output, BooleanSupplier canceled)
            throws DeployException;

    void upload(DeployContext ctx, Consumer<TransferProgress> progress, BooleanSupplier canceled)
            throws DeployException;

    int stop(DeployContext ctx, BiConsumer<Boolean, String> output, BooleanSupplier canceled)
            throws DeployException;

    int start(DeployContext ctx, BiConsumer<Boolean, String> output, BooleanSupplier canceled)
            throws DeployException;

    /** Runs the command configured as "After deploy". */
    int afterDeploy(DeployContext ctx, BiConsumer<Boolean, String> output, BooleanSupplier canceled)
            throws DeployException;

    void close();
}
