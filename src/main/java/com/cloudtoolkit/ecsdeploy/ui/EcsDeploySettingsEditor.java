package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.util.PathUtils;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor of the "ECS Deploy" run configuration, modelled after the Alibaba Cloud Toolkit
 * deployment configuration:
 *
 * <ul>
 *   <li><b>Deployment</b>: build type (Maven / Upload File / Gradle), the target hosts, the
 *       remote target directory and the command executed after the deployment.</li>
 *   <li><b>Advanced</b>: command executed before the deployment, the terminal options and the
 *       (multi line) command executed after the deployment.</li>
 * </ul>
 */
public final class EcsDeploySettingsEditor extends SettingsEditor<EcsDeployRunConfiguration> {

    private static final String TARGET_DIR_PLACEHOLDER =
            "远程服务器的部署目录, e.g. /home/admin/tomcat/webapps";
    private static final String BEFORE_DEPLOY_PLACEHOLDER =
            "Execute commands before deploy. Run commands like: /usr/bin/stop.sh";
    private static final String COMMAND_PLACEHOLDER =
            "# eg: 部署应用之后，如果需要查看实时日志，则执行如下命令即可\n"
                    + "tail -f /root/logs/xxx1.log -n300";

    private final JBRadioButton mavenRadio = new JBRadioButton("Maven Build");
    private final JBRadioButton uploadRadio = new JBRadioButton("Upload File");
    private final JBRadioButton gradleRadio = new JBRadioButton("Gradle Build");
    private final TextFieldWithBrowseButton artifactField = new TextFieldWithBrowseButton();
    private final JBLabel buildHint = new JBLabel();

    private final HostSelectionPanel deploymentHosts;
    private final HostSelectionPanel advancedHosts;

    private final JBTextField targetDirField = new JBTextField();
    private final JBTextField afterDeployField = new JBTextField();
    private final JBTextField beforeDeployField = new JBTextField();
    private final JBTextArea commandArea = new JBTextArea(6, 60);
    private final JBCheckBox openTerminalCheck = new JBCheckBox("Open Terminal");
    private final JBCheckBox autoOpenCheck = new JBCheckBox("Automatic open after deploy");

    private boolean syncing;
    private Project project;

    public EcsDeploySettingsEditor() {
        deploymentHosts = new HostSelectionPanel(this::onDeploymentHostsChanged);
        advancedHosts = new HostSelectionPanel(this::onAdvancedHostsChanged);
    }

    public static List<String> validate(EcsDeployRunConfigurationOptions options,
                                        EcsConnectionsService service) {
        List<String> errors = new ArrayList<>();
        List<String> hostIds = options.getTargetHostIdList();
        if (hostIds.isEmpty()) {
            String connectionId = options.getConnectionId();
            if (connectionId.isBlank() || service.findById(connectionId) == null) {
                errors.add(EcsDeployMessages.message("validation.connection.missing"));
            }
        } else {
            for (String hostId : hostIds) {
                if (service.findById(hostId) == null) {
                    errors.add("目标主机不存在: " + hostId);
                }
            }
        }
        if (options.getArtifactPath().isBlank()
                && EcsDeployRunConfigurationOptions.BUILD_UPLOAD.equals(options.getBuildType())) {
            errors.add(EcsDeployMessages.message("validation.artifact"));
        }
        if (!PathUtils.isAbsoluteRemote(options.getRemoteDirectory())) {
            errors.add(EcsDeployMessages.message("validation.remote.path"));
        }
        // The deploy commands are optional - an empty command simply is not executed.
        return errors;
    }

    @Override
    protected @NotNull JComponent createEditor() {
        artifactField.addBrowseFolderListener(ProjectManager.getInstance().getDefaultProject(),
                FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("选择要上传的文件"));

        ButtonGroup group = new ButtonGroup();
        group.add(mavenRadio);
        group.add(uploadRadio);
        group.add(gradleRadio);
        uploadRadio.setSelected(true);
        mavenRadio.addActionListener(e -> syncBuildType());
        uploadRadio.addActionListener(e -> syncBuildType());
        gradleRadio.addActionListener(e -> syncBuildType());

        targetDirField.getEmptyText().setText(TARGET_DIR_PLACEHOLDER);
        beforeDeployField.getEmptyText().setText(BEFORE_DEPLOY_PLACEHOLDER);
        afterDeployField.getEmptyText().setText("部署完成后执行的命令");
        commandArea.getEmptyText().setText(COMMAND_PLACEHOLDER);

        afterDeployField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                mirrorToCommandArea();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                mirrorToCommandArea();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                mirrorToCommandArea();
            }
        });
        commandArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                mirrorToAfterDeployField();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                mirrorToAfterDeployField();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                mirrorToAfterDeployField();
            }
        });

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Deployment", createDeploymentTab());
        tabs.addTab("Advanced", createAdvancedTab());
        syncBuildType();
        return tabs;
    }

    private JComponent createDeploymentTab() {
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        fileRow.add(mavenRadio);
        fileRow.add(uploadRadio);
        fileRow.add(gradleRadio);

        JPanel artifactRow = new JPanel(new BorderLayout(8, 0));
        artifactRow.add(artifactField, BorderLayout.CENTER);
        artifactRow.add(buildHint, BorderLayout.EAST);

        JPanel afterRow = new JPanel(new BorderLayout(8, 0));
        afterRow.add(afterDeployField, BorderLayout.CENTER);
        afterRow.add(selectCommandButton(afterDeployField), BorderLayout.EAST);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("File", fileRow)
                .addLabeledComponent("", artifactRow)
                .addLabeledComponent("Target Host", deploymentHosts)
                .addLabeledComponent("Target Directory", targetDirField)
                .addLabeledComponent("After deploy", afterRow)
                .addComponent(learnSampleLink())
                .getPanel();
        return wrap(form);
    }

    private JComponent createAdvancedTab() {
        JPanel beforeRow = new JPanel(new BorderLayout(8, 0));
        beforeRow.add(beforeDeployField, BorderLayout.CENTER);
        beforeRow.add(selectCommandButton(beforeDeployField), BorderLayout.EAST);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Before deploy", beforeRow)
                .addComponent(learnSampleLink())
                .addComponent(openTerminalCheck)
                .addLabeledComponent("Target Host", advancedHosts)
                .addLabeledComponent("Command", commandArea)
                .addComponent(autoOpenCheck)
                .getPanel();
        return wrap(form);
    }

    private static JComponent wrap(JComponent form) {
        JPanel root = new JPanel(new BorderLayout());
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    private javax.swing.JButton selectCommandButton(JBTextField target) {
        javax.swing.JButton button = new javax.swing.JButton("Select Command");
        button.addActionListener(e -> {
            SelectCommandDialog dialog = new SelectCommandDialog(project, null);
            if (dialog.showAndGet()) {
                target.setText(dialog.selectedCommand());
            }
        });
        return button;
    }

    private ActionLink learnSampleLink() {
        ActionLink link = new ActionLink("Learn Sample");
        link.addActionListener(e -> com.intellij.openapi.ui.Messages.showInfoMessage(
                "示例:\n"
                        + "Before deploy: /usr/bin/stop.sh\n"
                        + "After deploy:  tail -f /root/logs/app.log -n300",
                "Learn Sample"));
        return link;
    }

    private void syncBuildType() {
        boolean upload = uploadRadio.isSelected();
        artifactField.setEnabled(upload);
        if (mavenRadio.isSelected()) {
            buildHint.setText("部署前自动执行 mvn clean package");
        } else if (gradleRadio.isSelected()) {
            buildHint.setText("部署前自动执行 gradle build");
        } else {
            buildHint.setText("");
        }
    }

    /** Enables the Maven / Gradle options only when the project actually uses them. */
    private void syncBuildSupport() {
        boolean maven = hasBuildFile("pom.xml");
        boolean gradle = hasBuildFile("build.gradle") || hasBuildFile("build.gradle.kts");
        mavenRadio.setEnabled(maven);
        gradleRadio.setEnabled(gradle);
        if (mavenRadio.isSelected() && !maven) {
            uploadRadio.setSelected(true);
        }
        if (gradleRadio.isSelected() && !gradle) {
            uploadRadio.setSelected(true);
        }
    }

    private boolean hasBuildFile(String name) {
        if (project == null || project.getBasePath() == null) {
            return false;
        }
        return java.nio.file.Files.exists(java.nio.file.Path.of(project.getBasePath(), name));
    }

    private void mirrorToCommandArea() {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            commandArea.setText(afterDeployField.getText());
        } finally {
            syncing = false;
        }
    }

    private void mirrorToAfterDeployField() {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            afterDeployField.setText(commandArea.getText());
        } finally {
            syncing = false;
        }
    }

    private void onDeploymentHostsChanged(List<EcsConnection> hosts) {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            advancedHosts.setHosts(hosts);
        } finally {
            syncing = false;
        }
    }

    private void onAdvancedHostsChanged(List<EcsConnection> hosts) {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            deploymentHosts.setHosts(hosts);
        } finally {
            syncing = false;
        }
    }

    /** Rejects a configuration that cannot be deployed (e.g. hosts without credentials). */
    private void validateEditorState() throws ConfigurationException {
        for (EcsConnection host : deploymentHosts.getHosts()) {
            if (host.username == null || host.username.isBlank()) {
                throw new ConfigurationException("目标主机 " + host.host
                        + " 未配置 SSH 凭据，请先在 Dashboard 的「属性」中配置");
            }
        }
        String dir = targetDirField.getText().trim();
        if (!dir.isEmpty() && !dir.startsWith("/")) {
            throw new ConfigurationException("远端目录必须是绝对路径: " + dir);
        }
    }

    @Override
    protected void resetEditorFrom(@NotNull EcsDeployRunConfiguration configuration) {
        this.project = configuration.getProject();
        deploymentHosts.setProject(project);
        advancedHosts.setProject(project);
        EcsDeployRunConfigurationOptions options = configuration.getOptions();

        switch (options.getBuildType()) {
            case EcsDeployRunConfigurationOptions.BUILD_MAVEN -> mavenRadio.setSelected(true);
            case EcsDeployRunConfigurationOptions.BUILD_GRADLE -> gradleRadio.setSelected(true);
            default -> uploadRadio.setSelected(true);
        }
        artifactField.setText(options.getArtifactPath());
        targetDirField.setText(options.getRemoteDirectory());
        afterDeployField.setText(options.getAfterDeployCommand());
        beforeDeployField.setText(options.getBeforeDeployCommand());
        commandArea.setText(options.getAfterDeployCommand());
        openTerminalCheck.setSelected(options.isOpenTerminal());
        autoOpenCheck.setSelected(options.isAutoOpenAfterDeploy());

        List<EcsConnection> hosts = new ArrayList<>();
        for (String hostId : options.getTargetHostIdList()) {
            EcsConnection connection = EcsConnectionsService.getInstance().findById(hostId);
            if (connection != null) {
                hosts.add(connection);
            }
        }
        deploymentHosts.setHosts(hosts);
        advancedHosts.setHosts(hosts);
        syncBuildSupport();
        syncBuildType();
    }

    @Override
    protected void applyEditorTo(@NotNull EcsDeployRunConfiguration configuration)
            throws ConfigurationException {
        validateEditorState();
        EcsDeployRunConfigurationOptions options = configuration.getOptions();
        options.setBuildType(mavenRadio.isSelected()
                ? EcsDeployRunConfigurationOptions.BUILD_MAVEN
                : gradleRadio.isSelected()
                ? EcsDeployRunConfigurationOptions.BUILD_GRADLE
                : EcsDeployRunConfigurationOptions.BUILD_UPLOAD);
        options.setArtifactPath(artifactField.getText().trim());
        options.setRemoteDirectory(targetDirField.getText().trim());
        options.setAfterDeployCommand(afterDeployField.getText().trim());
        options.setBeforeDeployCommand(beforeDeployField.getText().trim());
        options.setOpenTerminal(openTerminalCheck.isSelected());
        options.setAutoOpenAfterDeploy(autoOpenCheck.isSelected());

        List<String> hostIds = new ArrayList<>();
        for (EcsConnection host : deploymentHosts.getHosts()) {
            if (host.id != null) {
                hostIds.add(host.id);
            }
        }
        options.setTargetHostIdList(hostIds);
        if (!hostIds.isEmpty()) {
            options.setConnectionId(hostIds.get(0));
        }
    }
}
