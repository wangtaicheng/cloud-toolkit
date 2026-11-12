package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshAuthenticationException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshConnectException;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicies;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyTrust;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.openapi.options.Configurable;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Root "Cloud Toolkit" settings page. Hosts are no longer managed here; use the Tags and Profile
 * submenus instead. This page only holds the global "skip host key verification" toggle and the
 * shared {@link #testConnection} helper used by the deploy UI.
 */
public final class EcsConnectionsConfigurable implements Configurable {

    private static final String DISPLAY_NAME = "Cloud Toolkit";
    private static final String NOTIFICATION_GROUP = "cloud-toolkit.ecs.deploy";

    private final JCheckBox skipHostKey = new JCheckBox("跳过主机密钥校验（不推荐）");
    private final JLabel riskLabel = new JLabel(message("risk.skip.host.key"));
    private boolean modified;

    public static String testConnection(EcsConnection connection, String secret) {
        return testConnection(connection, secret, HostKeyPolicies.create(), HostKeyPrompts.TRUST);
    }

    public static String testConnection(EcsConnection connection, String secret, HostKeyPolicy policy) {
        return testConnection(connection, secret, policy, null);
    }

    /**
     * Opens a session to check the connection.
     *
     * @param hostKeyTrust asks the user whether an unknown host key may be trusted, or {@code null}
     *                     to refuse unknown keys
     * @return {@code "ok"} or the reason the connection failed
     */
    public static String testConnection(EcsConnection connection, String secret, HostKeyPolicy policy,
                                        HostKeyTrust hostKeyTrust) {
        try (SshSessionFactory factory = new SshSessionFactory(policy, hostKeyTrust);
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
    }

    public void edit(EcsConnection connection, String secret) {
        service().upsert(connection, secret);
        modified = true;
    }

    public void delete(String id) {
        service().delete(id);
        modified = true;
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
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 8));
        panel.add(new JLabel("Cloud Toolkit 设置。请使用下方子菜单管理 Tags 与 Profile。"));
        skipHostKey.setSelected(skipHostKeyVerification());
        riskLabel.setVisible(skipHostKey.isSelected());
        skipHostKey.addActionListener(event -> riskLabel.setVisible(skipHostKey.isSelected()));
        panel.add(skipHostKey);
        panel.add(riskLabel);
        return panel;
    }

    @Override
    public boolean isModified() {
        return modified || skipHostKey.isSelected() != skipHostKeyVerification();
    }

    @Override
    public void apply() {
        CloudToolkitDeploySettings settings = settings();
        if (settings != null) {
            settings.setSkipHostKeyVerification(skipHostKey.isSelected());
        }
        modified = false;
    }

    @Override
    public void reset() {
        modified = false;
        skipHostKey.setSelected(skipHostKeyVerification());
        riskLabel.setVisible(skipHostKey.isSelected());
    }

    private static EcsConnectionsService service() {
        return EcsConnectionsService.getInstance();
    }

    /** The settings of the plugin, or null when the IDE is not available. */
    private static CloudToolkitDeploySettings settings() {
        try {
            return CloudToolkitDeploySettings.getInstance();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether the host keys of the servers are trusted without asking. */
    private static boolean skipHostKeyVerification() {
        CloudToolkitDeploySettings settings = settings();
        return settings != null && settings.isSkipHostKeyVerification();
    }

    private static String message(String key) {
        return ResourceBundle.getBundle("messages.EcsDeployBundle").getString(key);
    }
}
