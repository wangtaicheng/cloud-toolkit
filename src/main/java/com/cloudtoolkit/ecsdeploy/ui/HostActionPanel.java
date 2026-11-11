package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurable;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.Objects;

/**
 * Per-row action buttons shown in the host table (mirrors easy-deploy's {@code HostActionPanel}):
 * Deploy / Test / Edit / Delete.
 */
public final class HostActionPanel extends JPanel {

    private final JButton deployBtn;
    private final JButton testBtn;
    private final JButton editBtn;
    private final JButton deleteBtn;

    private final String hostId;
    private final Project project;
    private final Runnable onChange;

    public HostActionPanel(String hostId, Project project, Runnable onChange) {
        this.hostId = hostId;
        this.project = project;
        this.onChange = onChange;
        setLayout(new GridLayout(1, 4, 4, 0));

        deployBtn = new JButton("部署");
        testBtn = new JButton("测试");
        editBtn = new JButton("编辑");
        deleteBtn = new JButton("删除");

        deployBtn.addActionListener(e -> {
            EcsConnection c = EcsConnectionsService.getInstance().findById(hostId);
            if (c != null) {
                new DeployToHostDialog(project, c).show();
            }
        });
        testBtn.addActionListener(e -> testConnection());
        editBtn.addActionListener(e -> {
            EcsConnection c = EcsConnectionsService.getInstance().findById(hostId);
            if (c != null) {
                new AddHostDialog(project, c).show();
                onChange.run();
            }
        });
        deleteBtn.addActionListener(e -> delete());

        add(deployBtn);
        add(testBtn);
        add(editBtn);
        add(deleteBtn);
    }

    @Override
    public void setBackground(Color bg) {
        super.setBackground(bg);
        if (deployBtn != null) {
            deployBtn.setBackground(bg);
            testBtn.setBackground(bg);
            editBtn.setBackground(bg);
            deleteBtn.setBackground(bg);
        }
    }

    public String getState() {
        return hostId;
    }

    private void testConnection() {
        EcsConnection connection = EcsConnectionsService.getInstance().findById(hostId);
        if (connection == null) {
            return;
        }
        String secret = EcsConnectionsService.getInstance().getSecret(hostId);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result = EcsConnectionsConfigurable.testConnection(connection, secret, new HostKeyPolicy(true));
            boolean ok = "ok".equals(result);
            String msg = ok ? "连接成功: " + connection.host : result;
            ApplicationManager.getApplication().invokeLater(() ->
                    com.intellij.openapi.ui.Messages.showInfoMessage(msg, "Test Connection"));
        });
    }

    private void delete() {
        int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                "确定删除主机 " + hostId + " ?", "Delete Host",
                com.intellij.openapi.ui.Messages.getQuestionIcon());
        if (answer == com.intellij.openapi.ui.Messages.YES) {
            EcsConnectionsService.getInstance().delete(hostId);
            onChange.run();
        }
    }
}
