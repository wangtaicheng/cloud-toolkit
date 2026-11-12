package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.settings.HostKeyPrompts;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicies;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteFileTransfer;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 上传 action dialog (mirrors Alibaba Cloud Toolkit's Upload window): upload a local file via
 * SFTP or download from a URL directly on the remote host, optionally run a command afterwards.
 */
public final class UploadDialog extends DialogWrapper {

    private final Project project;
    private final EcsConnection target;

    private final JBRadioButton localFileRadio = new JBRadioButton("本地文件", true);
    private final JBRadioButton urlRadio = new JBRadioButton("URL");
    private final TextFieldWithBrowseButton localFileField = new TextFieldWithBrowseButton();
    private final JBTextField urlField = new JBTextField();
    private final JBTextField locationField = new JBTextField("/root");
    private final JBTextField afterCommandField = new JBTextField();

    public UploadDialog(Project project, EcsConnection target) {
        super(project, true);
        this.project = project;
        this.target = target;
        setTitle("上传 - " + target.host);
        localFileField.addBrowseFolderListener(project,
                new FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("选择要上传的文件"));
        init();
        getOKAction().putValue(Action.NAME, "上传");
        getCancelAction().putValue(Action.NAME, "取消");
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(localFileRadio);
        group.add(urlRadio);
        localFileRadio.addActionListener(e -> syncMode());
        urlRadio.addActionListener(e -> syncMode());

        JPanel root = new JPanel();
        root.setLayout(new javax.swing.BoxLayout(root, javax.swing.BoxLayout.Y_AXIS));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel fileRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        fileRow.add(localFileRadio);
        fileRow.add(urlRadio);
        root.add(fileRow);

        JPanel localRow = labeledRow("本地文件", localFileField);
        JPanel urlRow = labeledRow("URL", urlField);
        root.add(localRow);
        root.add(urlRow);

        JBTable hostTable = new JBTable(new DefaultTableModel(
                new Object[][]{{target.host + ":" + target.port, Objects.toString(target.tag, "")}},
                new String[]{"IP", "Tag"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        hostTable.setShowGrid(false);
        hostTable.setPreferredSize(new java.awt.Dimension(420, 46));
        root.add(labeledRow("目标主机", hostTable));
        root.add(labeledRow("Location", locationField));

        root.add(new com.intellij.ui.components.JBLabel("▼ 上传后操作"));
        JPanel afterRow = new JPanel(new java.awt.BorderLayout(8, 0));
        afterRow.add(afterCommandField, java.awt.BorderLayout.CENTER);
        javax.swing.JButton selectCommandBtn = new javax.swing.JButton("选择命令");
        selectCommandBtn.addActionListener(e -> {
            SelectCommandDialog chooser = new SelectCommandDialog(project, null);
            if (chooser.showAndGet()) {
                afterCommandField.setText(chooser.selectedCommand());
            }
        });
        afterRow.add(selectCommandBtn, java.awt.BorderLayout.EAST);
        root.add(afterRow);
        root.add(new com.intellij.ui.components.JBLabel("Tip: 请使用绝对路径"));

        this.localRow = localRow;
        this.urlRow = urlRow;
        syncMode();
        return root;
    }

    private JPanel localRow;
    private JPanel urlRow;

    private static JPanel labeledRow(String label, javax.swing.JComponent comp) {
        JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
        com.intellij.ui.components.JBLabel l = new com.intellij.ui.components.JBLabel(label);
        l.setPreferredSize(new java.awt.Dimension(72, l.getPreferredSize().height));
        row.add(l, java.awt.BorderLayout.WEST);
        row.add(comp, java.awt.BorderLayout.CENTER);
        return row;
    }

    private void syncMode() {
        boolean local = localFileRadio.isSelected();
        if (localRow != null) {
            localRow.setVisible(local);
        }
        if (urlRow != null) {
            urlRow.setVisible(!local);
        }
        localFileField.setEnabled(local);
        urlField.setEnabled(!local);
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (locationField.getText().trim().isEmpty()) {
            return new ValidationInfo("Location 不能为空", locationField);
        }
        if (localFileRadio.isSelected() && localFileField.getText().trim().isEmpty()) {
            return new ValidationInfo("请选择要上传的本地文件", localFileField);
        }
        if (urlRadio.isSelected() && urlField.getText().trim().isEmpty()) {
            return new ValidationInfo("请输入下载 URL", urlField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        String location = locationField.getText().trim();
        String afterCommand = afterCommandField.getText().trim();
        ConsoleOutputDialog console = new ConsoleOutputDialog(project, "上传输出 - " + target.host);
        console.show();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String secret = EcsConnectionsService.getInstance().getSecret(target.id);
            try (SshSessionFactory factory = new SshSessionFactory(HostKeyPolicies.create(), HostKeyPrompts.TRUST)) {
                var session = factory.open(target, secret);
                try {
                    if (localFileRadio.isSelected()) {
                        uploadLocal(console, session, location, afterCommand);
                    } else {
                        downloadUrl(console, session, location, afterCommand);
                    }
                } finally {
                    session.close();
                }
                console.println("完成", ConsoleViewContentType.SYSTEM_OUTPUT);
            } catch (Exception e) {
                console.println("上传失败: " + e.getMessage(), ConsoleViewContentType.ERROR_OUTPUT);
            }
        });
        super.doOKAction();
    }

    private void uploadLocal(ConsoleOutputDialog console, org.apache.sshd.client.session.ClientSession session,
                             String location, String afterCommand) throws Exception {
        Path local = Paths.get(localFileField.getText().trim());
        console.println("上传 " + local + " -> " + target.host + ":" + location,
                ConsoleViewContentType.SYSTEM_OUTPUT);
        new RemoteFileTransfer().upload(session, local, location,
                progress -> console.println("上传进度：" + progress.percent() + "%",
                        ConsoleViewContentType.NORMAL_OUTPUT));
        console.println("上传完成", ConsoleViewContentType.SYSTEM_OUTPUT);
        runAfterCommand(console, session, afterCommand);
    }

    private void downloadUrl(ConsoleOutputDialog console, org.apache.sshd.client.session.ClientSession session,
                             String location, String afterCommand) throws Exception {
        String url = urlField.getText().trim();
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.isBlank() || name.contains("?")) {
            name = "url-download-" + System.nanoTime();
        }
        String quotedLocation = shellQuote(location);
        String quotedFile = shellQuote(PathUtilsRemote.remoteFilePath(location, name));
        String cmd = "mkdir -p " + quotedLocation + " && (curl -sfLo " + quotedFile + " "
                + shellQuote(url) + " || wget -qO " + quotedFile + " " + shellQuote(url) + ")";
        console.println("远端下载 " + url + " -> " + target.host + ":" + location + "/" + name,
                ConsoleViewContentType.SYSTEM_OUTPUT);
        new RemoteCommandExecutor().executeOrThrow(session, cmd,
                (stderr, line) -> console.println(line, stderr
                        ? ConsoleViewContentType.ERROR_OUTPUT
                        : ConsoleViewContentType.NORMAL_OUTPUT),
                () -> false);
        runAfterCommand(console, session, afterCommand);
    }

    private void runAfterCommand(ConsoleOutputDialog console,
                                 org.apache.sshd.client.session.ClientSession session,
                                 String afterCommand) throws Exception {
        if (afterCommand.isEmpty()) {
            return;
        }
        console.println("执行上传后命令: " + afterCommand, ConsoleViewContentType.SYSTEM_OUTPUT);
        new RemoteCommandExecutor().execute(session, afterCommand,
                (stderr, line) -> console.println(line, stderr
                        ? ConsoleViewContentType.ERROR_OUTPUT
                        : ConsoleViewContentType.NORMAL_OUTPUT),
                () -> false);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Minimal remote path helper (kept local to avoid touching util classes). */
    private static final class PathUtilsRemote {
        static String remoteFilePath(String dir, String name) {
            if (dir == null || dir.isBlank() || "/".equals(dir)) {
                return "/" + name;
            }
            String base = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
            return base + "/" + name;
        }
    }
}
