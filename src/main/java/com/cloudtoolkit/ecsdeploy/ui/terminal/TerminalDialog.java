package com.cloudtoolkit.ecsdeploy.ui.terminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase;
import com.intellij.terminal.JBTerminalWidget;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * Floating non-modal window hosting an interactive remote SSH terminal
 * (JediTerm widget wired to {@link SshTtyConnector}). Closing it closes the SSH shell.
 */
public final class TerminalDialog extends DialogWrapper {

    private final JBTerminalWidget widget;

    public TerminalDialog(Project project, SshTtyConnector connector) {
        super(project, false);
        setTitle("终端 - " + connector.getName());
        setModal(false);

        Disposable parent = getDisposable();
        JBTerminalSystemSettingsProviderBase settings = new JBTerminalSystemSettingsProviderBase();
        widget = new JBTerminalWidget(project, settings, parent);
        Disposer.register(parent, widget);
        Disposer.register(parent, connector);

        widget.setPreferredSize(new Dimension(800, 480));
        widget.start(connector);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return widget;
    }

    @Override
    protected void doOKAction() {
        // just close the window; the SSH session is torn down via Disposer
        super.doOKAction();
    }
}
