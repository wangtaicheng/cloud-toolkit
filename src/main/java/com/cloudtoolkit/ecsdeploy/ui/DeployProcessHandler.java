package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.execution.process.ProcessHandler;

import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeployProcessHandler extends ProcessHandler {

    private final Runnable cancel;
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();

    public DeployProcessHandler(Runnable cancel) {
        this.cancel = Objects.requireNonNull(cancel);
        super.startNotify();
    }

    @Override
    protected void destroyProcessImpl() {
        if (destroyed.compareAndSet(false, true)) {
            cancel.run();
        }
        notifyTerminated();
    }

    @Override
    public void startNotify() {
    }

    @Override
    protected void detachProcessImpl() {
    }

    @Override
    public boolean detachIsDefault() {
        return false;
    }

    @Override
    public OutputStream getProcessInput() {
        return OutputStream.nullOutputStream();
    }

    public void finish() {
        notifyTerminated();
    }

    private void notifyTerminated() {
        if (terminated.compareAndSet(false, true)) {
            notifyProcessTerminated(0);
        }
    }
}
