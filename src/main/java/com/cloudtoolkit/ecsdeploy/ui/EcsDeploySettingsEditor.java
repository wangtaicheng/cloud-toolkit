package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.util.PathUtils;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.ActionLink;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;

public final class EcsDeploySettingsEditor extends SettingsEditor<EcsDeployRunConfiguration> {

    private final JComboBox<String> connections = new JComboBox<>();
    private final TextFieldWithBrowseButton artifactPath = new TextFieldWithBrowseButton();
    private final JTextField remoteDirectory = new JTextField();
    private final JTextField stopCommand = new JTextField();
    private final JTextField startCommand = new JTextField();
    private ActionLink settingsLink;

    public static List<String> validate(EcsDeployRunConfigurationOptions options,
                                        EcsConnectionsService service) {
        List<String> errors = new ArrayList<>();
        String connectionId = options.getConnectionId();
        if (connectionId.isBlank() || service.findById(connectionId) == null) {
            errors.add(EcsDeployMessages.message("validation.connection.missing"));
        }
        if (options.getArtifactPath().isBlank()) {
            errors.add(EcsDeployMessages.message("validation.artifact"));
        }
        if (!PathUtils.isAbsoluteRemote(options.getRemoteDirectory())) {
            errors.add(EcsDeployMessages.message("validation.remote.path"));
        }
        if (options.getStartCommand().isBlank()) {
            errors.add(EcsDeployMessages.message("validation.start.command"));
        }
        return errors;
    }

    @Override
    protected @NotNull JComponent createEditor() {
        artifactPath.addBrowseFolderListener(ProjectManager.getInstance().getDefaultProject(),
                FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("选择构建产物"));

        JPanel panel = new JPanel(new GridBagLayout());
        java.awt.event.ActionListener openSettings = event ->
                ShowSettingsUtil.getInstance().showSettingsDialog(
                        ProjectManager.getInstance().getDefaultProject(),
                        "Cloud Toolkit - ECS Deploy");
        settingsLink = new ActionLink("没有连接？打开 Settings 添加", openSettings);

        addRow(panel, 0, "ECS 连接", connections);
        addRow(panel, 1, "", settingsLink);
        addRow(panel, 2, "构建产物", artifactPath);
        addRow(panel, 3, "远端目录", remoteDirectory);
        addRow(panel, 4, "stop 命令", stopCommand);
        addRow(panel, 5, "start 命令", startCommand);
        return panel;
    }

    private static void addRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new java.awt.Insets(2, 4, 2, 8);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new java.awt.Insets(2, 0, 2, 4);
        panel.add(field, fieldConstraints);
    }

    @Override
    protected void resetEditorFrom(@NotNull EcsDeployRunConfiguration configuration) {
        EcsConnectionsService service = EcsConnectionsService.getInstance();
        connections.removeAllItems();
        for (EcsConnection connection : service.list()) {
            connections.addItem(connection.id);
        }
        settingsLink.setVisible(connections.getItemCount() == 0);

        EcsDeployRunConfigurationOptions options = configuration.getOptions();
        connections.setSelectedItem(options.getConnectionId());
        artifactPath.setText(options.getArtifactPath());
        remoteDirectory.setText(options.getRemoteDirectory());
        stopCommand.setText(options.getStopCommand());
        startCommand.setText(options.getStartCommand());
    }

    @Override
    protected void applyEditorTo(@NotNull EcsDeployRunConfiguration configuration) {
        EcsDeployRunConfigurationOptions options = configuration.getOptions();
        Object selected = connections.getSelectedItem();
        options.setConnectionId(selected == null ? "" : selected.toString());
        options.setArtifactPath(artifactPath.getText());
        options.setRemoteDirectory(remoteDirectory.getText());
        options.setStopCommand(stopCommand.getText());
        options.setStartCommand(startCommand.getText());
    }
}
