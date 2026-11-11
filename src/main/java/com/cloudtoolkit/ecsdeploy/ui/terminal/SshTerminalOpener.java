package com.cloudtoolkit.ecsdeploy.ui.terminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;

/**
 * Opens an interactive SSH terminal as a <em>tab inside the Terminal tool window</em> (tab title =
 * host IP) instead of a separate dialog window. When the Terminal tool window is not available
 * (plugin disabled) it falls back to {@link TerminalDialog}.
 */
public final class SshTerminalOpener {

    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";

    private SshTerminalOpener() {
    }

    public static void open(Project project, SshTtyConnector connector, String tabTitle) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TERMINAL_TOOL_WINDOW_ID);
        if (toolWindow == null) {
            new TerminalDialog(project, connector).show();
            return;
        }

        // The owner is disposed together with the tab, which tears down the SSH session.
        Disposable owner = new Disposable() {
            @Override
            public void dispose() {
                connector.close();
            }
        };

        JBTerminalWidget widget = new JBTerminalWidget(project,
                new JBTerminalSystemSettingsProviderBase(), owner);
        widget.start(connector);

        Content content = ContentFactory.getInstance().createContent(widget, tabTitle, false);
        content.setDisposer(owner);
        content.setCloseable(true);

        ContentManager manager = toolWindow.getContentManager();
        manager.addContent(content);
        manager.setSelectedContent(content);
        toolWindow.activate(null);
    }
}
