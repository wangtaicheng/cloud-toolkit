package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.List;

/**
 * 命令 action dialog (mirrors Alibaba Cloud Toolkit's Select Command window): pick an existing
 * command (or add/edit/delete one) and run it on the target host. With {@code target == null} it
 * acts as a pure chooser (used by UploadDialog's Select Command button).
 */
public final class SelectCommandDialog extends DialogWrapper {

    private final Project project;
    private final EcsConnection target;
    private final ComboBox<String> commandCombo = new ComboBox<>();
    private final JBList<String> commandList = new JBList<>();
    private final DefaultListModel<String> listModel = new DefaultListModel<>();

    public SelectCommandDialog(Project project, @Nullable EcsConnection target) {
        super(project, true);
        this.project = project;
        this.target = target;
        setTitle(target == null ? "选择命令" : "命令 - " + target.host);
        reloadCommands();
        commandList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                commandCombo.setSelectedItem(commandList.getSelectedValue());
            }
        });
        commandList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    commandCombo.setSelectedItem(commandList.getSelectedValue());
                    if (target != null) {
                        runCommand();
                    } else {
                        doOKAction();
                    }
                }
            }
        });
        init();
    }

    private void reloadCommands() {
        List<CloudToolkitDb.CommandRow> rows = CloudToolkitDb.getInstance().listCommands();
        DefaultComboBoxModel<String> comboModel = new DefaultComboBoxModel<>();
        comboModel.addElement("(无)");
        listModel.clear();
        for (CloudToolkitDb.CommandRow row : rows) {
            comboModel.addElement(row.content);
            listModel.addElement(row.content);
        }
        commandCombo.setModel(comboModel);
        commandList.setModel(listModel);
    }

    /** The selected command text (empty when "(无)"). */
    public String selectedCommand() {
        Object item = commandCombo.getSelectedItem();
        String text = item == null ? "" : item.toString().trim();
        return "(无)".equals(text) ? "" : text;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        commandCombo.setEditable(true);
        commandCombo.setPreferredSize(new Dimension(420, 30));
        commandList.setPreferredSize(new Dimension(420, 180));

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(commandCombo, BorderLayout.NORTH);
        center.add(commandList, BorderLayout.CENTER);

        JPanel left = new JPanel();
        JButton editBtn = new JButton("编辑");
        JButton deleteBtn = new JButton("删除");
        editBtn.addActionListener(e -> editCommand());
        deleteBtn.addActionListener(e -> deleteCommand());
        left.add(editBtn);
        left.add(deleteBtn);
        JPanel right = new JPanel();
        JButton addBtn = new JButton("添加命令");
        addBtn.addActionListener(e -> addCommand());
        right.add(addBtn);

        JPanel editRow = new JPanel(new BorderLayout());
        editRow.add(left, BorderLayout.WEST);
        editRow.add(right, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(center, BorderLayout.CENTER);
        root.add(editRow, BorderLayout.SOUTH);
        return root;
    }

    @Override
    protected Action[] createActions() {
        if (target == null) {
            getOKAction().putValue(Action.NAME, "Run");
            getCancelAction().putValue(Action.NAME, "Close");
            return new Action[]{getOKAction(), getCancelAction()};
        }
        Action run = new AbstractAction("Run") {
            @Override
            public void actionPerformed(ActionEvent e) {
                runCommand();
            }
        };
        Action close = new AbstractAction("Close") {
            @Override
            public void actionPerformed(ActionEvent e) {
                doCancelAction();
            }
        };
        return new Action[]{run, close};
    }

    private CloudToolkitDb.CommandRow findSelectedRow() {
        String selected = selectedCommand();
        if (selected.isEmpty()) {
            return null;
        }
        for (CloudToolkitDb.CommandRow row : CloudToolkitDb.getInstance().listCommands()) {
            if (row.content.equals(selected)) {
                return row;
            }
        }
        return null;
    }

    private void addCommand() {
        CommandEditorDialog editor = new CommandEditorDialog(project, null);
        if (editor.showAndGet()) {
            CloudToolkitDb.getInstance().saveCommand(editor.commandText());
            reloadCommands();
        }
    }

    private void editCommand() {
        CloudToolkitDb.CommandRow row = findSelectedRow();
        if (row == null) {
            return;
        }
        CommandEditorDialog editor = new CommandEditorDialog(project, row.content);
        if (editor.showAndGet()) {
            CloudToolkitDb.getInstance().updateCommand(row.id, editor.commandText());
            reloadCommands();
        }
    }

    private void deleteCommand() {
        CloudToolkitDb.CommandRow row = findSelectedRow();
        if (row == null) {
            return;
        }
        int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                "确定删除命令: " + row.content + " ?", "删除命令",
                com.intellij.openapi.ui.Messages.getQuestionIcon());
        if (answer == com.intellij.openapi.ui.Messages.YES) {
            CloudToolkitDb.getInstance().deleteCommand(row.id);
            reloadCommands();
        }
    }

    private void runCommand() {
        String command = selectedCommand();
        if (command.isEmpty()) {
            com.intellij.openapi.ui.Messages.showWarningDialog("请选择要执行的命令", "Run");
            return;
        }
        if (target == null) {
            doOKAction();
            return;
        }
        String secret = EcsConnectionsService.getInstance().getSecret(target.id);
        ConsoleOutputDialog console = new ConsoleOutputDialog(project, "命令输出 - " + target.host);
        console.show();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (SshSessionFactory factory = new SshSessionFactory(new HostKeyPolicy(true))) {
                var session = factory.open(target, secret);
                try {
                    int code = new RemoteCommandExecutor().execute(
                            session, command,
                            (stderr, line) -> console.println(line, stderr
                                    ? ConsoleViewContentType.ERROR_OUTPUT
                                    : ConsoleViewContentType.NORMAL_OUTPUT),
                            () -> false);
                    console.println("exit code: " + code, ConsoleViewContentType.SYSTEM_OUTPUT);
                } finally {
                    session.close();
                }
            } catch (IOException | DeployException e) {
                console.println("执行失败: " + e.getMessage(), ConsoleViewContentType.ERROR_OUTPUT);
            }
        });
    }

    /** Small editor used by 添加命令/编辑. */
    private static final class CommandEditorDialog extends DialogWrapper {
        private final JBTextArea area = new JBTextArea(8, 48);

        CommandEditorDialog(Project project, @Nullable String initial) {
            super(project, true);
            setTitle(initial == null ? "添加命令" : "编辑命令");
            if (initial != null) {
                area.setText(initial);
            }
            init();
        }

        String commandText() {
            return area.getText().trim();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(area, BorderLayout.CENTER);
            return panel;
        }
    }
}
