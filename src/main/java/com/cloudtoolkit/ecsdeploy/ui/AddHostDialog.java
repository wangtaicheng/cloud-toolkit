package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurable;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "Add Host" dialog, modelled after easy-deploy's {@code HostSettingsDialog}: a tabbed form
 * (Basic connection settings + Deploy defaults) with a Test Connection button. All values are
 * persisted into {@code ~/.cloudidea/cloudtoolkit.db} ({@code profiles} + {@code hosts} + {@code tags}).
 */
public final class AddHostDialog extends DialogWrapper {

    private final Project project;
    private final EcsConnection editing;

    private final JBTextArea hostListArea = new JBTextArea(6, 30);
    private final JScrollPane hostListScroll = new JScrollPane(hostListArea);
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passwdField = new JBPasswordField();
    private final TextFieldWithBrowseButton privateKeyField = new TextFieldWithBrowseButton();
    private final JBPasswordField passphraseField = new JBPasswordField();
    private final ComboBox<String> tagCombo = new ComboBox<>();
    private final JBTextField descField = new JBTextField();

    private final JBRadioButton passwdRadio = new JBRadioButton("Password");
    private final JBRadioButton privateKeyRadio = new JBRadioButton("Private Key");

    private final JBTextField targetDirField = new JBTextField("/root");
    private final ComboBox<String> commandCombo = new ComboBox<>();
    private final JBRadioButton mavenRadio = new JBRadioButton("Maven");
    private final JBRadioButton uploadRadio = new JBRadioButton("Upload");
    private final JBRadioButton gradleRadio = new JBRadioButton("Gradle");

    private final JButton testButton = new JButton("Test Connection");

    public AddHostDialog(@Nullable Project project) {
        this(project, null);
    }

    public AddHostDialog(@Nullable Project project, @Nullable EcsConnection editing) {
        super(project, true);
        this.project = project;
        this.editing = editing;
        setTitle(editing == null ? "Add Host" : "Edit Host");
        init();
        initUi();
    }

    private void initUi() {
        ButtonGroup auth = new ButtonGroup();
        auth.add(passwdRadio);
        auth.add(privateKeyRadio);
        passwdRadio.setSelected(true);
        updateAuthEnabled();
        passwdRadio.addActionListener(e -> updateAuthEnabled());
        privateKeyRadio.addActionListener(e -> updateAuthEnabled());

        privateKeyField.addBrowseFolderListener(project,
                new FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select Private Key"));

        for (String tag : EcsConnectionsService.getInstance().listTags()) {
            tagCombo.addItem(tag);
        }
        tagCombo.setEditable(true);

        for (CloudToolkitDb.CommandRow cmd : CloudToolkitDb.getInstance().listCommands()) {
            commandCombo.addItem(cmd.content);
        }
        commandCombo.setEditable(true);

        testButton.addActionListener(e -> testConnection());

        if (editing != null) {
            hostListArea.setText(editing.host + ":" + editing.port);
            userField.setText(editing.username);
            descField.setText(editing.remark == null ? "" : DeployMeta.parse(editing.remark).base);
            tagCombo.setSelectedItem(editing.tag == null ? "" : editing.tag);
            String secret = EcsConnectionsService.getInstance().getSecret(editing.id);
            if (editing.authType == EcsConnection.AuthType.PRIVATE_KEY) {
                privateKeyRadio.setSelected(true);
                privateKeyField.setText(secret == null ? "" : secret);
            } else {
                passwdRadio.setSelected(true);
                passwdField.setText(secret == null ? "" : secret);
            }
            updateAuthEnabled();
            DeployMeta meta = DeployMeta.parse(editing.remark);
            if (meta.deployDir != null && !meta.deployDir.isEmpty()) {
                targetDirField.setText(meta.deployDir);
            }
            if (meta.commandId > 0) {
                CloudToolkitDb.getInstance().listCommands().stream()
                        .filter(c -> c.id == meta.commandId)
                        .findFirst()
                        .ifPresent(c -> commandCombo.setSelectedItem(c.content));
            }
            selectBuildType(meta.buildType);
        }
    }

    private void updateAuthEnabled() {
        boolean pwd = passwdRadio.isSelected();
        passwdField.setEnabled(pwd);
        privateKeyField.setEnabled(!pwd);
        passphraseField.setEnabled(!pwd);
    }

    private void selectBuildType(String type) {
        if ("upload".equals(type)) {
            uploadRadio.setSelected(true);
        } else if ("gradle".equals(type)) {
            gradleRadio.setSelected(true);
        } else {
            mavenRadio.setSelected(true);
        }
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

    @Override
    protected @Nullable JComponent createCenterPanel() {
        hostListArea.setLineWrap(true);
        hostListScroll.setPreferredSize(new Dimension(360, 120));

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Host List", hostListScroll)
                .addLabeledComponent("Username", userField)
                .addLabeledComponent("Auth Type", authContainer())
                .addLabeledComponent("Password", passwdField)
                .addLabeledComponent("Private Key", privateKeyField)
                .addLabeledComponent("Passphrase", passphraseField)
                .addLabeledComponent("Tag", tagCombo)
                .addLabeledComponent("Description", descField)
                .getPanel();

        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        testRow.add(testButton);

        JPanel basic = new JPanel(new BorderLayout());
        basic.add(form, BorderLayout.CENTER);
        basic.add(testRow, BorderLayout.SOUTH);

        JPanel deploy = FormBuilder.createFormBuilder()
                .addLabeledComponent("Target Directory", targetDirField)
                .addLabeledComponent("Command", commandCombo)
                .addLabeledComponent("Build Type", buildTypeContainer())
                .getPanel();

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Basic", basic);
        tabs.addTab("Deploy", deploy);
        return tabs;
    }

    private JPanel authContainer() {
        JPanel p = new JPanel(new GridLayout(1, 2));
        p.add(passwdRadio);
        p.add(privateKeyRadio);
        return p;
    }

    private JPanel buildTypeContainer() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(mavenRadio);
        p.add(uploadRadio);
        p.add(gradleRadio);
        return p;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        List<String[]> hosts = parseHostLines();
        if (hosts.isEmpty()) {
            return new ValidationInfo("主机列表不能为空 (每行 host 或 host:port)", hostListArea);
        }
        if (userField.getText().trim().isEmpty()) {
            return new ValidationInfo("Username is required", userField);
        }
        if (passwdRadio.isSelected() && new String(passwdField.getPassword()).isEmpty()) {
            return new ValidationInfo("Password is required", passwdField);
        }
        if (privateKeyRadio.isSelected() && privateKeyField.getText().trim().isEmpty()) {
            return new ValidationInfo("Private key is required", privateKeyField);
        }
        return null;
    }

    /** Parses the host list: each non-empty, non-comment line is {@code host} or {@code host:port}. */
    private List<String[]> parseHostLines() {
        List<String[]> result = new ArrayList<>();
        for (String raw : hostListArea.getText().split("\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String host;
            int port = 22;
            int idx = line.lastIndexOf(':');
            if (idx > 0) {
                host = line.substring(0, idx).trim();
                try {
                    port = Integer.parseInt(line.substring(idx + 1).trim());
                } catch (NumberFormatException ignored) {
                    port = 22;
                }
            } else {
                host = line;
            }
            if (!host.isEmpty()) {
                result.add(new String[]{host, String.valueOf(port)});
            }
        }
        return result;
    }

    @Override
    protected void doOKAction() {
        EcsConnection.AuthType authType = passwdRadio.isSelected()
                ? EcsConnection.AuthType.PASSWORD
                : EcsConnection.AuthType.PRIVATE_KEY;
        String tag = Objects.toString(tagCombo.getSelectedItem(), "").trim();
        String secret = passwdRadio.isSelected()
                ? new String(passwdField.getPassword())
                : passphraseField.getText().trim();
        String privateKeyPath = privateKeyRadio.isSelected() ? privateKeyField.getText().trim() : null;

        String command = Objects.toString(commandCombo.getEditor().getItem(), "").trim();
        int commandId = command.isEmpty() ? -1 : CloudToolkitDb.getInstance().saveCommand(command);

        String base = descField.getText().trim();
        String remark;
        if (commandId > 0) {
            remark = DeployMeta.serialize(base, targetDirField.getText().trim(), buildType(), commandId);
        } else {
            remark = base.isEmpty() ? null : base;
        }

        EcsConnectionsService service = EcsConnectionsService.getInstance();
        List<String[]> hosts = parseHostLines();

        if (editing != null && !hosts.isEmpty()) {
            String[] h = hosts.get(0);
            editing.host = h[0];
            try {
                editing.port = Integer.parseInt(h[1]);
            } catch (NumberFormatException e) {
                editing.port = 22;
            }
            editing.username = userField.getText().trim();
            editing.tag = tag;
            editing.authType = authType;
            editing.privateKeyPath = privateKeyPath;
            editing.remark = remark;
            service.upsert(editing, secret);
        } else {
            for (String[] h : hosts) {
                EcsConnection c = new EcsConnection();
                c.host = h[0];
                try {
                    c.port = Integer.parseInt(h[1]);
                } catch (NumberFormatException e) {
                    c.port = 22;
                }
                c.username = userField.getText().trim();
                c.tag = tag;
                c.authType = authType;
                c.privateKeyPath = privateKeyPath;
                c.remark = remark;
                service.upsert(c, secret);
            }
        }

        super.doOKAction();
    }

    private void testConnection() {
        List<String[]> hosts = parseHostLines();
        if (hosts.isEmpty()) {
            com.intellij.openapi.ui.Messages.showErrorDialog("请先在主机列表中填写至少一个主机", "Test Connection");
            return;
        }
        String[] first = hosts.get(0);
        EcsConnection connection = new EcsConnection();
        connection.host = first[0];
        try {
            connection.port = Integer.parseInt(first[1]);
        } catch (NumberFormatException e) {
            connection.port = 22;
        }
        connection.username = userField.getText().trim();
        connection.authType = passwdRadio.isSelected()
                ? EcsConnection.AuthType.PASSWORD
                : EcsConnection.AuthType.PRIVATE_KEY;
        String secret = passwdRadio.isSelected()
                ? new String(passwdField.getPassword())
                : passphraseField.getText().trim();
        if (connection.host.isEmpty() || connection.username.isEmpty() || secret.isEmpty()) {
            com.intellij.openapi.ui.Messages.showErrorDialog("请先填写主机、用户名与凭据", "Test Connection");
            return;
        }
        String result = EcsConnectionsConfigurable.testConnection(connection, secret, new HostKeyPolicy(true));
        boolean ok = "ok".equals(result);
        com.intellij.openapi.ui.Messages.showInfoMessage(ok ? "连接成功: " + connection.host : result, "Test Connection");
    }
}
