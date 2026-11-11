package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;

/**
 * Non-modal window showing streaming output of a remote command (命令 Run / Upload after-command).
 */
public final class ConsoleOutputDialog extends DialogWrapper {

    private final ConsoleView console;

    public ConsoleOutputDialog(Project project, String title) {
        super(project, false);
        setTitle(title);
        setModal(false);
        this.console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        init();
    }

    public JComponent component() {
        return console.getComponent();
    }

    public void println(String text, ConsoleViewContentType type) {
        console.print(text + "\n", type);
    }

    public ConsoleView console() {
        return console;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return console.getComponent();
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }
}
