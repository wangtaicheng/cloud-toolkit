package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.cloudtoolkit.ecsdeploy.deploy.DeployContext;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * "Deploy to Host" dialog, mirroring the Cloud Toolkit documentation: choose a build method
 * (Maven / Upload File / Gradle), a target host via its Tag and server, the remote target
 * directory, and the run command. The command is persisted into the {@code commands} table, while
 * the target directory and build type are stored on the host record (column {@code remark}).
 */
public final class DeployToHostDialog extends DialogWrapper {

    private final Project project;

    private final JBRadioButton mavenRadio = new JBRadioButton("Maven Build");
    private final JBRadioButton uploadRadio = new JBRadioButton("Upload File");
    private final JBRadioButton gradleRadio = new JBRadioButton("Gradle Build");
    private final TextFieldWithBrowseButton artifactField = new TextFieldWithBrowseButton();
    private final ComboBox<String> tagCombo = new ComboBox<>();
    private final ComboBox<String> hostCombo = new ComboBox<>();
    private final ComboBox<String> commandCombo = new ComboBox<>();
    private final com.intellij.ui.components.JBTextField targetDirField =
            new com.intellij.ui.components.JBTextField("/root");

    private List<EcsConnection> currentHosts = List.of();
    private final EcsConnection preselected;

    public DeployToHostDialog(@Nullable Project project) {
        this(project, null);
    }

    public DeployToHostDialog(@Nullable Project project, @Nullable EcsConnection preselected) {
        super(project, true);
        this.project = project;
        this.preselected = preselected;
        setTitle("Deploy to Host");
        init();
        initUi();
    }

    private void initUi() {
        ButtonGroup group = new ButtonGroup();
        group.add(mavenRadio);
        group.add(uploadRadio);
        group.add(gradleRadio);
        mavenRadio.setSelected(true);

        artifactField.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("选择构建产物 / 上传文件"));

        EcsConnectionsService service = EcsConnectionsService.getInstance();
        for (String tag : service.listTags()) {
            tagCombo.addItem(tag);
        }
        refreshHosts();
        tagCombo.addActionListener(event -> refreshHosts());

        for (CloudToolkitDb.CommandRow cmd : CloudToolkitDb.getInstance().listCommands()) {
            commandCombo.addItem(cmd.content);
        }
        commandCombo.setEditable(true);

        if (preselected != null && preselected.tag != null) {
            tagCombo.setSelectedItem(preselected.tag);
            refreshHosts();
            for (int i = 0; i < currentHosts.size(); i++) {
                if (currentHosts.get(i).id.equals(preselected.id)) {
                    hostCombo.setSelectedIndex(i);
                    break;
                }
            }
            DeployMeta meta = DeployMeta.parse(preselected.remark);
            if (meta.deployDir != null && !meta.deployDir.isEmpty()) {
                targetDirField.setText(meta.deployDir);
            }
            if (meta.commandId > 0) {
                CloudToolkitDb.getInstance().listCommands().stream()
                        .filter(c -> c.id == meta.commandId)
                        .findFirst()
                        .ifPresent(c -> commandCombo.setSelectedItem(c.content));
            }
        }
    }

    private void refreshHosts() {
        hostCombo.removeAllItems();
        currentHosts = List.of();
        Object selected = tagCombo.getSelectedItem();
        if (selected == null) {
            return;
        }
        currentHosts = EcsConnectionsService.getInstance().findByTag(selected.toString());
        for (EcsConnection c : currentHosts) {
            hostCombo.addItem(c.host + ":" + c.port + (c.remark == null || c.remark.isBlank()
                    ? "" : " (" + c.remark + ")"));
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel fileRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        fileRow.add(mavenRadio);
        fileRow.add(uploadRadio);
        fileRow.add(gradleRadio);

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("File", fileRow)
                .addLabeledComponent("Artifact / Build output", artifactField)
                .addLabeledComponent("Tag", tagCombo)
                .addLabeledComponent("Target Host", hostCombo)
                .addLabeledComponent("Target Directory", targetDirField)
                .addLabeledComponent("Command", commandCombo)
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (hostCombo.getItemCount() == 0 || hostCombo.getSelectedIndex() < 0) {
            return new ValidationInfo("请先选择目标主机", hostCombo);
        }
        String artifact = artifactField.getText().trim();
        if (artifact.isEmpty() || !new File(artifact).isFile()) {
            return new ValidationInfo("构建产物不存在", artifactField);
        }
        String dir = targetDirField.getText().trim();
        if (dir.isEmpty() || !dir.startsWith("/")) {
            return new ValidationInfo("远端目录必须是绝对路径", targetDirField);
        }
        String cmd = commandText();
        if (cmd.isEmpty()) {
            return new ValidationInfo("启动命令不能为空", commandCombo);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        int index = hostCombo.getSelectedIndex();
        if (index < 0 || index >= currentHosts.size()) {
            return;
        }
        EcsConnection connection = currentHosts.get(index);
        String secret = EcsConnectionsService.getInstance().getSecret(connection.id);
        Path artifact = Path.of(artifactField.getText().trim());
        String targetDir = targetDirField.getText().trim();
        String command = commandText();

        int commandId = CloudToolkitDb.getInstance().saveCommand(command);

        CloudToolkitDb.HostRow host = CloudToolkitDb.getInstance().findHostById(Integer.parseInt(connection.id));
        if (host != null) {
            host.remark = DeployMeta.serialize(host.remark, targetDir, buildType(), commandId);
            CloudToolkitDb.getInstance().upsertHost(host);
        }

        DeployContext context = new DeployContext(connection, secret, artifact, targetDir, "", command);

        super.doOKAction();
        DeployExecutor.run(project, context);
    }

    private String buildType() {
        if (uploadRadio.isSelected()) {
            return "upload";
        }
        if (gradleRadio.isSelected()) {
            return "gradle";
        }
        return "maven";
    }

    private String commandText() {
        Object item = commandCombo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }
}
