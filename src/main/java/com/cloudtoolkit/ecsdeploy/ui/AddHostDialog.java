package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurable;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.settings.SshProfileCredentials;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.SshClientConfig;
import com.cloudtoolkit.ecsdeploy.transfer.SshConfigResolver;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.CardLayout;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "添加主机" dialog, mirroring the Alibaba Cloud Toolkit screenshots:
 * <ul>
 *   <li>Tab 添加主机: multi-line host list (one host per line, default port 22), SSH profile
 *       section that can either create a new profile or reuse an existing one, and a
 *       测试连接状况 button.</li>
 *   <li>Tab Advanced: Tag (create new / use existing), Description and SSH Proxy (pick a
 *       configured host as jump machine, stored into {@code hosts.proxy_id}).</li>
 * </ul>
 * All values are persisted into {@code ~/.cloudidea/cloudtoolkit.db}.
 */
public final class AddHostDialog extends DialogWrapper {

    /** One parsed host line. */
    private record HostEntry(String address, int port) { }

    private static final String CARD_PASSWORD = "password";
    private static final String CARD_KEY = "key";

    private final Project project;
    private final EcsConnection editing;
    /** The OpenSSH configuration of the machine, used to resolve host aliases. */
    private final SshClientConfig sshConfig = SshClientConfig.load(SshClientConfig.defaultFile());

    // ---- Tab: 添加主机 ----
    private final JBTextArea hostListArea = new JBTextArea(5, 32);
    private final JBTextField portField = new JBTextField("22");
    private final JBRadioButton createProfileRadio = new JBRadioButton("创建新配置", true);
    private final JBRadioButton useProfileRadio = new JBRadioButton("使用已有配置");
    private final ComboBox<String> profileCombo = new ComboBox<>();
    private final JBTextField profileNameField = new JBTextField("Profile 1");
    private final ComboBox<String> authTypeCombo = new ComboBox<>(new String[]{"Password", "Private Key"});
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passwdField = new JBPasswordField();
    private final JBCheckBox showPassCheck = new JBCheckBox("显示");
    private final TextFieldWithBrowseButton privateKeyField = new TextFieldWithBrowseButton();
    private final JBPasswordField passphraseField = new JBPasswordField();
    private final JButton testButton = new JButton("测试连接状况");
    private final JBLabel sshConfigHint = new JBLabel(" ");
    private JPanel profileFieldsPanel;
    private JPanel authCards;

    // ---- Tab: Advanced ----
    private final JBRadioButton createTagRadio = new JBRadioButton("Create new tag", true);
    private final JBRadioButton useTagRadio = new JBRadioButton("Use exist tag");
    private final ComboBox<String> tagCombo = new ComboBox<>();
    private final JBTextField tagField = new JBTextField();
    private final JBTextField descField = new JBTextField();
    private JPanel tagFieldsPanel;
    private final ComboBox<String> proxyTagCombo = new ComboBox<>();
    private final SearchTextField proxySearchField = new SearchTextField();
    private final JButton proxySearchButton = new JButton("Search");
    private final JButton proxyClearButton = new JButton("清除");
    private final JBTable proxyTable = new JBTable();
    private final JBLabel proxyLabel = new JBLabel("当前跳板机: 无");
    private int proxyHostId = 0;

    public AddHostDialog(@Nullable Project project) {
        this(project, null);
    }

    public AddHostDialog(@Nullable Project project, @Nullable EcsConnection editing) {
        super(project, true);
        this.project = project;
        this.editing = editing;
        setTitle(editing == null ? "添加主机" : "编辑主机");
        init();
        getOKAction().putValue(Action.NAME, "添加");
        getCancelAction().putValue(Action.NAME, "取消");
        initUi();
    }

    private void initUi() {
        hostListArea.setLineWrap(true);
        hostListArea.setWrapStyleWord(true);

        ButtonGroup profileGroup = new ButtonGroup();
        profileGroup.add(createProfileRadio);
        profileGroup.add(useProfileRadio);
        for (CloudToolkitDb.ProfileRow p : EcsConnectionsService.getInstance().listProfiles()) {
            profileCombo.addItem(p.name);
        }
        profileCombo.setEnabled(false);

        char echoChar = passwdField.getEchoChar();
        showPassCheck.addActionListener(e -> passwdField.setEchoChar(
                showPassCheck.isSelected() ? (char) 0 : echoChar));

        privateKeyField.addBrowseFolderListener(project,
                new FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("选择私钥文件"));

        authTypeCombo.addActionListener(e -> {
            CardLayout cl = (CardLayout) authCards.getLayout();
            cl.show(authCards, isKeyAuth() ? CARD_KEY : CARD_PASSWORD);
        });

        profileCombo.addActionListener(e -> echoExistingProfile());

        ButtonGroup tagGroup = new ButtonGroup();
        tagGroup.add(createTagRadio);
        tagGroup.add(useTagRadio);
        for (String tag : EcsConnectionsService.getInstance().listTags()) {
            tagCombo.addItem(tag);
        }
        tagCombo.setEnabled(false);

        // SSH Proxy filter + table
        proxyTagCombo.addItem("");
        for (String tag : EcsConnectionsService.getInstance().listTags()) {
            proxyTagCombo.addItem(tag);
        }
        proxyTagCombo.addActionListener(e -> refreshProxyTable());
        proxySearchButton.addActionListener(e -> refreshProxyTable());
        proxySearchField.getTextEditor().addActionListener(e -> refreshProxyTable());
        proxyClearButton.addActionListener(this::clearProxy);
        proxyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        proxyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onProxySelectionChanged();
            }
        });
        refreshProxyTable();

        createProfileRadio.addActionListener(e -> syncProfileMode());
        useProfileRadio.addActionListener(e -> syncProfileMode());
        createTagRadio.addActionListener(e -> syncTagMode());
        useTagRadio.addActionListener(e -> syncTagMode());

        testButton.addActionListener(e -> testConnection());

        hostListArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSshConfigHint();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSshConfigHint();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSshConfigHint();
            }
        });

        if (editing != null) {
            prefillForEdit();
        }
        syncProfileMode();
        syncTagMode();
        updateSshConfigHint();
    }

    /** Shows how the OpenSSH configuration ({@code ~/.ssh/config}) resolves the first host. */
    private void updateSshConfigHint() {
        List<HostEntry> hosts = parseHostLines();
        if (hosts.isEmpty()) {
            sshConfigHint.setText(" ");
            return;
        }
        String address = hosts.get(0).address();
        String description = SshConfigResolver.describe(sshConfig, address);
        sshConfigHint.setText(description == null
                ? " "
                : "已匹配 SSH 配置 " + address + " → " + description);
        sshConfigHint.setForeground(description == null
                ? JBColor.GRAY : new JBColor(new java.awt.Color(0x3574F0), new java.awt.Color(0x3574F0)));
    }

    private boolean isKeyAuth() {
        return "Private Key".equals(authTypeCombo.getSelectedItem());
    }

    /** When "使用已有配置" is selected, echo the chosen profile's details into the fields. */
    private void echoExistingProfile() {
        CloudToolkitDb.ProfileRow profile = findSelectedProfile();
        if (profile == null) {
            return;
        }
        userField.setText(profile.user == null ? "" : profile.user);
        if (profile.privateKey != null && !profile.privateKey.isBlank()) {
            authTypeCombo.setSelectedItem("Private Key");
            privateKeyField.setText(profile.privateKey);
            passwdField.setText("");
            passphraseField.setText(profile.passphrase == null ? "" : profile.passphrase);
        } else {
            authTypeCombo.setSelectedItem("Password");
            passwdField.setText(profile.passwd == null ? "" : profile.passwd);
            privateKeyField.setText("");
            passphraseField.setText("");
        }
        CardLayout cl = (CardLayout) authCards.getLayout();
        cl.show(authCards, isKeyAuth() ? CARD_KEY : CARD_PASSWORD);
        syncProfileMode();
    }

    /** The profile currently picked in "使用已有配置" mode (null in "创建新配置" mode). */
    private CloudToolkitDb.ProfileRow findSelectedProfile() {
        if (createProfileRadio.isSelected()) {
            return null;
        }
        String name = Objects.toString(profileCombo.getSelectedItem(), "");
        if (name.isBlank()) {
            return null;
        }
        return CloudToolkitDb.getInstance().findProfileByName(name);
    }

    /**
     * A profile is usable when it has a user plus a credential. For private-key profiles the
     * passphrase is optional (unencrypted keys are common), for password profiles it is required.
     */
    private static boolean isProfileComplete(CloudToolkitDb.ProfileRow profile) {
        return SshProfileCredentials.isComplete(profile);
    }

    /** Section header rendered as a bold label (TitledSeparator is unavailable in this SDK). */
    private static JBLabel sectionLabel(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.LEFT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private void syncProfileMode() {
        boolean create = createProfileRadio.isSelected();
        profileCombo.setEnabled(!create);
        setEnabledDeep(profileFieldsPanel, create);
        if (!create) {
            // Reusing an existing profile: the fields stay read-only, unless that profile is
            // missing a credential - then the user is allowed to fill the gap in.
            CloudToolkitDb.ProfileRow profile = findSelectedProfile();
            boolean incomplete = profile == null || !isProfileComplete(profile);
            userField.setEnabled(incomplete);
            authTypeCombo.setEnabled(incomplete);
            passwdField.setEnabled(incomplete);
            showPassCheck.setEnabled(incomplete);
            privateKeyField.setEnabled(incomplete);
            passphraseField.setEnabled(incomplete);
            profileNameField.setEnabled(false);
        }
    }

    private void syncTagMode() {
        boolean create = createTagRadio.isSelected();
        tagCombo.setEnabled(!create);
        setEnabledDeep(tagFieldsPanel, create);
    }

    private static void setEnabledDeep(java.awt.Component c, boolean on) {
        c.setEnabled(on);
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                setEnabledDeep(child, on);
            }
        }
    }

    private void prefillForEdit() {
        // The host list keeps the plain address only; the port goes into the 端口号 field.
        hostListArea.setText(editing.host == null ? "" : editing.host);
        portField.setText(String.valueOf(editing.port));
        profileNameField.setText(editing.displayName == null || editing.displayName.isBlank()
                ? editing.host : editing.displayName);
        userField.setText(editing.username);
        String secret = EcsConnectionsService.getInstance().getSecret(editing.id);
        if (editing.privateKeyPath != null && !editing.privateKeyPath.isBlank()) {
            authTypeCombo.setSelectedItem("Private Key");
            privateKeyField.setText(editing.privateKeyPath);
            passphraseField.setText(secret == null ? "" : secret);
        } else {
            authTypeCombo.setSelectedItem("Password");
            passwdField.setText(secret == null ? "" : secret);
        }
        if (editing.tag != null && !editing.tag.isBlank()) {
            useTagRadio.setSelected(true);
            tagCombo.setSelectedItem(editing.tag);
            createTagRadio.setSelected(false);
        } else {
            tagField.setText("");
        }
        descField.setText(DeployMeta.parse(editing.remark).base);
        try {
            CloudToolkitDb.HostRow host = CloudToolkitDb.getInstance()
                    .findHostById(Integer.parseInt(editing.id));
            if (host != null && host.proxyId > 0) {
                EcsConnection proxy = EcsConnectionsService.getInstance()
                        .findById(String.valueOf(host.proxyId));
                proxyHostId = host.proxyId;
                proxyLabel.setText("当前跳板机: " + (proxy == null ? host.proxyId : proxy.host));
            }
        } catch (NumberFormatException ignored) {
            // keep default
        }
    }

    // ------------------------------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("添加主机", createBasicTab());
        tabs.addTab("Advanced", createAdvancedTab());
        return tabs;
    }

    private JComponent createBasicTab() {
        JPanel hostListPanel = new JPanel(new BorderLayout());
        JScrollPane scroll = new JScrollPane(hostListArea);
        scroll.setPreferredSize(new Dimension(420, 130));
        hostListPanel.add(scroll, BorderLayout.CENTER);
        JBLabel hint = new JBLabel("提示: 一行只输入一个主机, 默认端口号是 22");
        hint.setForeground(new JBColor(java.awt.Color.GRAY, java.awt.Color.GRAY));
        hostListPanel.add(hint, BorderLayout.SOUTH);

        JPanel profileModeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        profileModeRow.add(createProfileRadio);
        profileModeRow.add(useProfileRadio);
        profileModeRow.add(profileCombo);

        JPanel passRow = new JPanel(new BorderLayout(8, 0));
        passRow.add(passwdField, BorderLayout.CENTER);
        passRow.add(showPassCheck, BorderLayout.EAST);

        JPanel passwordCard = FormBuilder.createFormBuilder()
                .addLabeledComponent("密码", passRow)
                .getPanel();
        JPanel keyCard = FormBuilder.createFormBuilder()
                .addLabeledComponent("私钥文件", privateKeyField)
                .addLabeledComponent("Passphrase", passphraseField)
                .getPanel();
        authCards = new JPanel(new CardLayout());
        authCards.add(passwordCard, CARD_PASSWORD);
        authCards.add(keyCard, CARD_KEY);

        profileFieldsPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("配置名称", profileNameField)
                .addLabeledComponent("验证方式", authTypeCombo)
                .addLabeledComponent("用户名", userField)
                .addComponent(authCards)
                .getPanel();

        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        testRow.add(testButton);

        JBLabel proxyHint = new JBLabel("如有需要，可以在 Advanced 中设置跳板机");
        proxyHint.setForeground(new JBColor(new java.awt.Color(0x3574F0), new java.awt.Color(0x3574F0)));

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("待添加主机列表", hostListPanel)
                .addLabeledComponent("端口号", portField)
                .addComponent(sshConfigHint)
                .addComponent(sectionLabel("SSH 配置"))
                .addComponent(profileModeRow)
                .addComponent(profileFieldsPanel)
                .addComponent(testRow)
                .addComponent(proxyHint)
                .getPanel();

        JPanel root = new JPanel(new BorderLayout());
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(null);
        root.add(formScroll, BorderLayout.CENTER);
        return root;
    }

    private JComponent createAdvancedTab() {
        JPanel tagModeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tagModeRow.add(createTagRadio);
        tagModeRow.add(useTagRadio);
        tagModeRow.add(tagCombo);

        tagFieldsPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Tag", tagField)
                .addLabeledComponent("Description", descField)
                .getPanel();

        JPanel proxyFilterRow = new JPanel(new BorderLayout(8, 0));
        proxyFilterRow.add(proxyTagCombo, BorderLayout.WEST);
        JPanel filterMid = new JPanel(new BorderLayout(8, 0));
        filterMid.add(proxySearchField.getTextEditor(), BorderLayout.CENTER);
        filterMid.add(proxySearchButton, BorderLayout.EAST);
        proxyFilterRow.add(filterMid, BorderLayout.CENTER);

        JScrollPane proxyScroll = new JScrollPane(proxyTable);
        proxyScroll.setPreferredSize(new Dimension(420, 180));

        JPanel proxyRow = new JPanel(new BorderLayout(8, 0));
        proxyRow.add(proxyLabel, BorderLayout.WEST);
        proxyRow.add(proxyClearButton, BorderLayout.EAST);

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(sectionLabel("Tag"))
                .addComponent(tagModeRow)
                .addComponent(tagFieldsPanel)
                .addComponent(sectionLabel("SSH Proxy"))
                .addComponent(new JBLabel("请选择一台机器作为跳板机"))
                .addComponent(proxyFilterRow)
                .addComponent(proxyScroll)
                .addComponent(proxyRow)
                .getPanel();

        JPanel root = new JPanel(new BorderLayout());
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(null);
        root.add(formScroll, BorderLayout.CENTER);
        return root;
    }

    // ------------------------------------------------------------------------------------------
    // SSH Proxy table
    // ------------------------------------------------------------------------------------------

    private void refreshProxyTable() {
        String tag = Objects.toString(proxyTagCombo.getSelectedItem(), "");
        String keyword = proxySearchField.getText().trim().toLowerCase();

        List<EcsConnection> hosts = new ArrayList<>();
        for (EcsConnection c : EcsConnectionsService.getInstance().list()) {
            if (!tag.isEmpty() && !tag.equals(c.tag)) {
                continue;
            }
            if (!keyword.isEmpty() && !c.host.toLowerCase().contains(keyword)) {
                continue;
            }
            hosts.add(c);
        }

        Object[][] data = new Object[hosts.size()][2];
        for (int i = 0; i < hosts.size(); i++) {
            data[i][0] = hosts.get(i).host + ":" + hosts.get(i).port;
            data[i][1] = hosts.get(i).tag == null ? "" : hosts.get(i).tag;
        }
        DefaultTableModel model = new DefaultTableModel(data, new String[]{"IP", "Tag"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        proxyTable.setModel(model);
        proxyTable.getEmptyText().setText("没有可选主机");
        proxyTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        proxyTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        if (proxyHostId > 0) {
            highlightProxyRow(hosts);
        }
    }

    /** Keeps the currently-selected jump host highlighted after a table refresh. */
    private void highlightProxyRow(List<EcsConnection> hosts) {
        for (int i = 0; i < hosts.size(); i++) {
            EcsConnection c = hosts.get(i);
            if (c.id != null && String.valueOf(proxyHostId).equals(c.id)) {
                proxyTable.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void onProxySelectionChanged() {
        int row = proxyTable.getSelectedRow();
        if (row < 0 || row >= proxyTable.getRowCount()) {
            return;
        }
        Object value = proxyTable.getValueAt(row, 0);
        if (value == null) {
            return;
        }
        String addressPort = value.toString();
        int idx = addressPort.lastIndexOf(':');
        String address = idx > 0 ? addressPort.substring(0, idx) : addressPort;
        for (EcsConnection c : EcsConnectionsService.getInstance().list()) {
            if (c.host.equals(address)) {
                proxyHostId = Integer.parseInt(c.id);
                proxyLabel.setText("当前跳板机: " + c.host);
                return;
            }
        }
    }

    private void clearProxy(ActionEvent e) {
        proxyHostId = 0;
        proxyLabel.setText("当前跳板机: 无");
        proxyTable.clearSelection();
    }

    // ------------------------------------------------------------------------------------------
    // Parsing / validation / save / test
    // ------------------------------------------------------------------------------------------

    /** Parses the host list; per-line {@code host:port} overrides the 端口号 field. */
    private List<HostEntry> parseHostLines() {
        List<HostEntry> result = new ArrayList<>();
        int defaultPort = 22;
        try {
            defaultPort = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ignored) {
            // keep 22
        }
        for (String raw : hostListArea.getText().split("\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String host;
            int port = defaultPort;
            int idx = line.lastIndexOf(':');
            if (idx > 0) {
                host = line.substring(0, idx).trim();
                try {
                    port = Integer.parseInt(line.substring(idx + 1).trim());
                } catch (NumberFormatException ignored) {
                    port = defaultPort;
                }
            } else {
                host = line;
            }
            if (!host.isEmpty()) {
                result.add(new HostEntry(host, port));
            }
        }
        return result;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        List<HostEntry> hosts = parseHostLines();
        if (hosts.isEmpty()) {
            return new ValidationInfo("主机列表不能为空 (每行一个主机)", hostListArea);
        }
        if (createProfileRadio.isSelected()) {
            if (profileNameField.getText().trim().isEmpty()) {
                return new ValidationInfo("配置名称不能为空", profileNameField);
            }
            if (userField.getText().trim().isEmpty()) {
                return new ValidationInfo("用户名不能为空", userField);
            }
            if (isKeyAuth()) {
                if (privateKeyField.getText().trim().isEmpty()) {
                    return new ValidationInfo("私钥文件不能为空", privateKeyField);
                }
            } else if (new String(passwdField.getPassword()).isEmpty()) {
                return new ValidationInfo("密码不能为空", passwdField);
            }
        } else {
            if (profileCombo.getSelectedItem() == null
                    || profileCombo.getSelectedItem().toString().isBlank()) {
                return new ValidationInfo("请选择已有的 SSH 配置", profileCombo);
            }
            CloudToolkitDb.ProfileRow profile = findSelectedProfile();
            if (profile == null) {
                return new ValidationInfo("请选择已有的 SSH 配置", profileCombo);
            }
            if (!isProfileComplete(profile)) {
                // The profile lacks a credential - it can be completed right here.
                SshProfileCredentials.Credentials credentials = resolveCredentials();
                if (credentials.user().isEmpty()) {
                    return new ValidationInfo("请先填写用户名", userField);
                }
                if (credentials.authType() == EcsConnection.AuthType.PRIVATE_KEY) {
                    if (credentials.privateKeyPath().isEmpty()) {
                        return new ValidationInfo("请先选择私钥文件", privateKeyField);
                    }
                } else if (credentials.secret().isEmpty()) {
                    return new ValidationInfo("请先填写密码", passwdField);
                }
            }
        }
        if (createTagRadio.isSelected() && tagField.getText().trim().isEmpty()) {
            return new ValidationInfo("Tag 不能为空", tagField);
        }
        return null;
    }

    @Override
    protected void doOKAction() {
        List<HostEntry> hosts = parseHostLines();
        if (hosts.isEmpty()) {
            return;
        }

        int profileId = resolveProfileId();
        if (profileId <= 0) {
            return;
        }

        String tag = createTagRadio.isSelected()
                ? tagField.getText().trim()
                : Objects.toString(tagCombo.getSelectedItem(), "").trim();
        String remarkText = descField.getText().trim();
        String remark = buildRemark(remarkText);

        EcsConnectionsService service = EcsConnectionsService.getInstance();
        if (editing != null) {
            HostEntry first = hosts.get(0);
            int hostId = 0;
            try {
                hostId = Integer.parseInt(editing.id);
            } catch (NumberFormatException ignored) {
                // insert a fresh row
            }
            service.upsertHostWithProfile(hostId, first.address(), first.port(), tag, remark, profileId, proxyHostId);
        } else {
            for (HostEntry h : hosts) {
                service.upsertHostWithProfile(0, h.address(), h.port(), tag, remark, profileId, proxyHostId);
            }
        }

        super.doOKAction();
    }

    /** Creates a new profile from the form or resolves the selected existing one. */
    private int resolveProfileId() {
        if (useProfileRadio.isSelected()) {
            CloudToolkitDb.ProfileRow profile = findSelectedProfile();
            if (profile == null) {
                return -1;
            }
            if (!isProfileComplete(profile)) {
                // The profile was missing a credential and the user filled it in - persist it.
                SshProfileCredentials.Credentials credentials = resolveCredentials();
                CloudToolkitDb.ProfileRow updated = new CloudToolkitDb.ProfileRow();
                updated.name = profile.name;
                updated.user = credentials.user();
                if (credentials.authType() == EcsConnection.AuthType.PRIVATE_KEY) {
                    updated.privateKey = credentials.privateKeyPath();
                    updated.passphrase = credentials.secret().isEmpty() ? null : credentials.secret();
                } else {
                    updated.passwd = credentials.secret();
                }
                return CloudToolkitDb.getInstance().upsertProfile(updated);
            }
            return profile.id;
        }
        CloudToolkitDb.ProfileRow profile = new CloudToolkitDb.ProfileRow();
        profile.name = profileNameField.getText().trim();
        profile.user = userField.getText().trim();
        if (isKeyAuth()) {
            profile.privateKey = privateKeyField.getText().trim();
            profile.passphrase = new String(passphraseField.getPassword());
        } else {
            profile.passwd = new String(passwdField.getPassword());
        }
        if (!confirmOverwriteExistingProfile(profile.name)) {
            return -1;
        }
        return CloudToolkitDb.getInstance().upsertProfile(profile);
    }

    /**
     * A profile is stored by its unique name, so creating a configuration with an existing name
     * overwrites its credentials - which would break every other host using it. Ask first.
     */
    private boolean confirmOverwriteExistingProfile(String name) {
        CloudToolkitDb.ProfileRow existing = CloudToolkitDb.getInstance().findProfileByName(name);
        if (existing == null || isProfileOfEditedHost(existing.id)) {
            return true;
        }
        int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                "已存在同名 SSH 配置 '" + name + "'，继续将覆盖它的凭据，"
                        + "其它使用该配置的主机可能无法连接。是否继续？",
                "覆盖 SSH 配置", com.intellij.openapi.ui.Messages.getQuestionIcon());
        return answer == com.intellij.openapi.ui.Messages.YES;
    }

    /** Whether the profile is the one currently bound to the host being edited. */
    private boolean isProfileOfEditedHost(int profileId) {
        if (editing == null || editing.id == null) {
            return false;
        }
        try {
            CloudToolkitDb.HostRow host = CloudToolkitDb.getInstance()
                    .findHostById(Integer.parseInt(editing.id));
            return host != null && host.profileId == profileId;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** Keeps any stored deploy meta ({@link DeployMeta}) when overwriting the description. */
    private String buildRemark(String remarkText) {
        if (editing == null) {
            return remarkText.isEmpty() ? null : remarkText;
        }
        DeployMeta meta = DeployMeta.parse(editing.remark);
        boolean hasMeta = meta.commandId > 0 || (meta.deployDir != null && !meta.deployDir.isEmpty());
        if (hasMeta) {
            return DeployMeta.serialize(remarkText, meta.deployDir, meta.buildType, meta.commandId);
        }
        return remarkText.isEmpty() ? null : remarkText;
    }

    /** Effective credentials: whatever is typed in the form wins, the profile is the fallback. */
    private SshProfileCredentials.Credentials resolveCredentials() {
        CloudToolkitDb.ProfileRow profile = findSelectedProfile();
        EcsConnection.AuthType authType = isKeyAuth()
                ? EcsConnection.AuthType.PRIVATE_KEY
                : EcsConnection.AuthType.PASSWORD;
        String secret = authType == EcsConnection.AuthType.PRIVATE_KEY
                ? new String(passphraseField.getPassword())
                : new String(passwdField.getPassword());
        return SshProfileCredentials.resolve(profile, userField.getText(), authType,
                privateKeyField.getText(), secret);
    }

    private void testConnection() {
        List<HostEntry> hosts = parseHostLines();
        if (hosts.isEmpty()) {
            com.intellij.openapi.ui.Messages.showErrorDialog("请先填写待添加主机列表", "测试连接状况");
            return;
        }
        HostEntry first = hosts.get(0);
        EcsConnection connection = new EcsConnection();
        connection.host = first.address();
        connection.port = first.port();
        connection.proxyId = proxyHostId;

        if (useProfileRadio.isSelected() && findSelectedProfile() == null) {
            com.intellij.openapi.ui.Messages.showErrorDialog("请选择已有的 SSH 配置", "测试连接状况");
            return;
        }

        SshProfileCredentials.Credentials credentials = resolveCredentials();
        if (credentials.user().isEmpty()) {
            com.intellij.openapi.ui.Messages.showErrorDialog("请先填写用户名", "测试连接状况");
            return;
        }
        if (credentials.authType() == EcsConnection.AuthType.PRIVATE_KEY) {
            // The passphrase is only required for encrypted keys, an empty one is valid.
            if (credentials.privateKeyPath().isEmpty()) {
                com.intellij.openapi.ui.Messages.showErrorDialog("请先选择私钥文件", "测试连接状况");
                return;
            }
        } else if (credentials.secret().isEmpty()) {
            com.intellij.openapi.ui.Messages.showErrorDialog("请先填写密码", "测试连接状况");
            return;
        }

        connection.username = credentials.user();
        connection.authType = credentials.authType();
        connection.privateKeyPath = credentials.privateKeyPath();
        connection.hasPassphrase = !credentials.secret().isEmpty();

        String result = EcsConnectionsConfigurable.testConnection(
                connection, credentials.secret(), new HostKeyPolicy(true));
        boolean ok = "ok".equals(result);
        String description = SshConfigResolver.describe(sshConfig, connection.host);
        String target = description == null
                ? connection.host : connection.host + " → " + description;
        com.intellij.openapi.ui.Messages.showInfoMessage(
                ok ? "连接成功: " + target : result,
                "测试连接状况");
    }
}
