package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
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

/**
 * Manages stored SSH credential profiles. Selecting a row echoes every property back into the form
 * (including the secret) so the user can edit it (回显).
 */
public final class ProfileConfigurable implements Configurable {

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"ID", "名称", "用户名", "认证方式"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextField nameField = new JTextField();
    private final JTextField userField = new JTextField();
    private final JComboBox<EcsConnection.AuthType> authTypeField =
            new JComboBox<>(new DefaultComboBoxModel<>(EcsConnection.AuthType.values()));
    private final JTextField privateKeyField = new JTextField();
    private final JPasswordField secretField = new JPasswordField();
    private int selectedId;
    private boolean modified;

    @Override
    public String getDisplayName() {
        return "Profile 配置";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 2));
        form.add(new JLabel("名称"));
        form.add(nameField);
        form.add(new JLabel("用户名"));
        form.add(userField);
        form.add(new JLabel("认证方式"));
        form.add(authTypeField);
        form.add(new JLabel("私钥路径 (密钥认证)"));
        form.add(privateKeyField);
        form.add(new JLabel("密码 / 口令"));
        form.add(secretField);

        JButton save = new JButton("保存");
        JButton delete = new JButton("删除");
        JButton clear = new JButton("清空");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(save);
        buttons.add(delete);
        buttons.add(clear);

        selectedId = 0;
        save.addActionListener(event -> save());
        delete.addActionListener(event -> deleteSelected());
        clear.addActionListener(event -> clearForm());
        table.getSelectionModel().addListSelectionListener(event -> fillFromSelection());

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
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
        clearForm();
        refreshTable();
    }

    private void save() {
        String name = nameField.getText().trim();
        String user = userField.getText().trim();
        if (name.isEmpty()) {
            Messages.showWarningDialog("名称不能为空", "Cloud Toolkit");
            return;
        }
        boolean keyAuth = authTypeField.getSelectedItem() == EcsConnection.AuthType.PRIVATE_KEY;
        String secret = new String(secretField.getPassword());

        CloudToolkitDb.ProfileRow profile = new CloudToolkitDb.ProfileRow();
        profile.name = name;
        profile.user = user;
        if (keyAuth) {
            profile.privateKey = privateKeyField.getText().trim();
            profile.passphrase = secret;
            profile.passwd = null;
        } else {
            profile.passwd = secret;
            profile.privateKey = null;
            profile.passphrase = null;
        }

        try {
            if (selectedId == 0) {
                if (EcsConnectionsService.getInstance().findProfileByName(name) != null) {
                    Messages.showWarningDialog("Profile 名称已存在：" + name, "Cloud Toolkit");
                    return;
                }
                EcsConnectionsService.getInstance().createProfile(profile);
            } else {
                EcsConnectionsService.getInstance().updateProfile(selectedId, profile);
            }
            modified = true;
            clearForm();
            refreshTable();
        } catch (RuntimeException e) {
            Messages.showErrorDialog(e.getMessage(), "Cloud Toolkit");
        }
    }

    private void deleteSelected() {
        if (selectedId == 0) {
            Messages.showWarningDialog("请先选择要删除的 Profile", "Cloud Toolkit");
            return;
        }
        try {
            EcsConnectionsService.getInstance().deleteProfile(selectedId);
            modified = true;
            clearForm();
            refreshTable();
        } catch (RuntimeException e) {
            Messages.showErrorDialog(e.getMessage(), "Cloud Toolkit");
        }
    }

    private void clearForm() {
        selectedId = 0;
        nameField.setText("");
        userField.setText("");
        authTypeField.setSelectedItem(EcsConnection.AuthType.PASSWORD);
        privateKeyField.setText("");
        secretField.setText("");
    }

    /** Echo the selected profile's properties back into the form (回显). */
    private void fillFromSelection() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        selectedId = Integer.parseInt(String.valueOf(tableModel.getValueAt(row, 0)));
        CloudToolkitDb.ProfileRow profile = EcsConnectionsService.getInstance().findProfileById(selectedId);
        if (profile == null) {
            return;
        }
        nameField.setText(profile.name == null ? "" : profile.name);
        userField.setText(profile.user == null ? "" : profile.user);
        boolean keyAuth = profile.privateKey != null && !profile.privateKey.isBlank();
        authTypeField.setSelectedItem(keyAuth ? EcsConnection.AuthType.PRIVATE_KEY : EcsConnection.AuthType.PASSWORD);
        privateKeyField.setText(profile.privateKey == null ? "" : profile.privateKey);
        if (keyAuth) {
            secretField.setText(profile.passphrase == null ? "" : profile.passphrase);
        } else {
            secretField.setText(profile.passwd == null ? "" : profile.passwd);
        }
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        for (CloudToolkitDb.ProfileRow profile : EcsConnectionsService.getInstance().listProfiles()) {
            boolean keyAuth = profile.privateKey != null && !profile.privateKey.isBlank();
            tableModel.addRow(new Object[]{profile.id, profile.name,
                    profile.user, keyAuth ? "私钥" : "密码"});
        }
    }
}
