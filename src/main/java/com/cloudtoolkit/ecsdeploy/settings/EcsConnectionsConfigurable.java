package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshAuthenticationException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshConnectException;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.security.PublicKey;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;

public final class EcsConnectionsConfigurable implements Configurable {

    private static final String DISPLAY_NAME = "Cloud Toolkit - ECS Deploy";
    private static final String NOTIFICATION_GROUP = "cloud-toolkit.ecs.deploy";

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"ID", "名称", "主机", "端口", "用户名", "认证方式"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextField idField = new JTextField();
    private final JTextField nameField = new JTextField();
    private final JTextField hostField = new JTextField();
    private final JTextField portField = new JTextField("22");
    private final JTextField tagField = new JTextField();
    private final JTextField userField = new JTextField();
    private final JComboBox<EcsConnection.AuthType> authTypeField =
            new JComboBox<>(new DefaultComboBoxModel<>(EcsConnection.AuthType.values()));
    private final JTextField privateKeyField = new JTextField();
    private final JPasswordField secretField = new JPasswordField();
    private final JCheckBox skipHostKey = new JCheckBox("跳过主机密钥校验（不推荐）");
    private final JLabel riskLabel = new JLabel(message("risk.skip.host.key"));
    private boolean modified;

    public static String testConnection(EcsConnection connection, String secret) {
        return testConnection(connection, secret, new HostKeyPolicy(true));
    }

    public static String testConnection(EcsConnection connection, String secret, HostKeyPolicy policy) {
        try (SshSessionFactory factory = new SshSessionFactory(policy);
             ClientSession session = factory.open(connection, secret)) {
            return "ok";
        } catch (SshAuthenticationException e) {
            return "SSH 认证失败：" + e.getMessage();
        } catch (SshConnectException e) {
            return "无法连接 ECS：" + e.getMessage();
        } catch (Exception e) {
            return "无法连接 ECS：" + e.getMessage();
        }
    }

    public void add(EcsConnection connection, String secret) {
        service().upsert(connection, secret);
        modified = true;
        refreshTable();
    }

    public void edit(EcsConnection connection, String secret) {
        service().upsert(connection, secret);
        modified = true;
        refreshTable();
    }

    public void delete(String id) {
        service().delete(id);
        modified = true;
        refreshTable();
    }

    public List<EcsConnection> list() {
        return service().list();
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 2));
        form.add(new JLabel("连接 ID"));
        form.add(idField);
        form.add(new JLabel("显示名称"));
        form.add(nameField);
        form.add(new JLabel("主机"));
        form.add(hostField);
        form.add(new JLabel("端口"));
        form.add(portField);
        form.add(new JLabel("标签 (Tag)"));
        form.add(tagField);
        form.add(new JLabel("用户名"));
        form.add(userField);
        form.add(new JLabel("认证方式"));
        form.add(authTypeField);
        form.add(new JLabel("私钥路径"));
        form.add(privateKeyField);
        form.add(new JLabel("密码 / 口令"));
        form.add(secretField);

        JButton save = new JButton("保存连接");
        JButton deleteButton = new JButton("删除");
        JButton testButton = new JButton("测试连接");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(save);
        buttons.add(deleteButton);
        buttons.add(testButton);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(skipHostKey, BorderLayout.CENTER);
        bottom.add(riskLabel, BorderLayout.SOUTH);
        riskLabel.setVisible(false);

        skipHostKey.addActionListener(event -> riskLabel.setVisible(skipHostKey.isSelected()));
        save.addActionListener(event -> saveFromForm());
        deleteButton.addActionListener(event -> {
            String id = selectedId();
            if (id != null) {
                delete(id);
            }
        });
        testButton.addActionListener(event -> testSelected());
        table.getSelectionModel().addListSelectionListener(event -> fillFormFromSelection());

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        refreshTable();
        return root;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void apply() {
        modified = false;
    }

    @Override
    public void reset() {
        modified = false;
        refreshTable();
    }

    private void saveFromForm() {
        EcsConnection connection = new EcsConnection();
        connection.id = idField.getText().trim();
        connection.displayName = nameField.getText().trim();
        connection.host = hostField.getText().trim();
        connection.port = parsePort(portField.getText());
        connection.tag = tagField.getText().trim();
        connection.username = userField.getText().trim();
        connection.authType = (EcsConnection.AuthType) authTypeField.getSelectedItem();
        connection.privateKeyPath = privateKeyField.getText().trim();
        connection.hasPassphrase = connection.authType == EcsConnection.AuthType.PRIVATE_KEY;
        String secret = new String(secretField.getPassword());
        if (service().findById(connection.id) == null) {
            add(connection, secret);
        } else {
            edit(connection, secret);
        }
    }

    private void testSelected() {
        String id = selectedId();
        if (id == null) {
            return;
        }
        EcsConnection connection = service().findById(id);
        if (connection == null) {
            return;
        }
        String secret = service().getSecret(id);
        HostKeyPolicy policy = new HostKeyPolicy(skipHostKey.isSelected());
        policy.clearUnknownServerKey();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result = testConnection(connection, secret, policy);
            PublicKey unknownKey = policy.unknownServerKey();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (unknownKey != null) {
                    confirmHostKey(connection, unknownKey);
                }
                publish(result);
            });
        });
    }

    private static void confirmHostKey(EcsConnection connection, PublicKey key) {
        String text = MessageFormat.format(message("dialog.host.key.confirm"),
                connection.host, HostKeyPolicy.fingerprint(key));
        int answer = Messages.showYesNoDialog(ProjectManager.getInstance().getDefaultProject(),
                text, "确认主机密钥", null);
        if (answer == Messages.YES) {
            HostKeyPolicy.write(HostKeyPolicy.defaultKnownHosts(), connection.host, connection.port, key);
        }
    }

    private static void publish(String result) {
        boolean success = "ok".equals(result);
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Cloud Toolkit 部署",
                        success ? "连接成功" : result,
                        success ? NotificationType.INFORMATION : NotificationType.ERROR)
                .notify(ProjectManager.getInstance().getDefaultProject());
    }

    private String selectedId() {
        int row = table.getSelectedRow();
        return row < 0 ? null : String.valueOf(tableModel.getValueAt(row, 0));
    }

    private void fillFormFromSelection() {
        String id = selectedId();
        if (id == null) {
            return;
        }
        EcsConnection connection = service().findById(id);
        if (connection == null) {
            return;
        }
        idField.setText(connection.id);
        nameField.setText(connection.displayName);
        hostField.setText(connection.host);
        portField.setText(String.valueOf(connection.port));
        tagField.setText(connection.tag == null ? "" : connection.tag);
        userField.setText(connection.username);
        authTypeField.setSelectedItem(connection.authType);
        privateKeyField.setText(connection.privateKeyPath == null ? "" : connection.privateKeyPath);
        secretField.setText("");
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        for (EcsConnection connection : service().list()) {
            tableModel.addRow(new Object[]{connection.id, connection.displayName, connection.host,
                    connection.port, connection.username, connection.authType});
        }
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    private static EcsConnectionsService service() {
        return EcsConnectionsService.getInstance();
    }

    private static String message(String key) {
        return ResourceBundle.getBundle("messages.EcsDeployBundle").getString(key);
    }
}
