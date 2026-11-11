package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsConfigurable;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.cloudtoolkit.ecsdeploy.ui.terminal.SshTerminalOpener;
import com.cloudtoolkit.ecsdeploy.ui.terminal.SshTtyConnector;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Per-row action buttons shown in the host table:
 * 部署 / 测试 / 上传 / 终端 / 命令 / 更多 (下拉: 属性 / 远端文件 / 诊断 / 移除).
 */
public final class HostActionPanel extends JPanel {

    private static final String ARTHAS_JAR = "/tmp/arthas-boot.jar";
    private static final String ARTHAS_URL = "https://arthas.aliyun.com/arthas-boot.jar";

    private final JButton deployBtn;
    private final JButton testBtn;
    private final JButton uploadBtn;
    private final JButton terminalBtn;
    private final JButton commandBtn;
    private final JButton moreBtn;

    private final String hostId;
    private final Project project;
    private final Runnable onChange;

    public HostActionPanel(String hostId, Project project, Runnable onChange) {
        this.hostId = hostId;
        this.project = project;
        this.onChange = onChange;
        setLayout(new GridLayout(1, 6, 2, 0));

        deployBtn = new JButton("部署");
        testBtn = new JButton("测试");
        uploadBtn = new JButton("上传");
        terminalBtn = new JButton("终端");
        commandBtn = new JButton("命令");
        moreBtn = new JButton("更多");

        deployBtn.addActionListener(e -> {
            EcsConnection c = connection();
            if (c != null) {
                new DeployToHostDialog(project, c).show();
            }
        });
        testBtn.addActionListener(e -> testConnection());
        uploadBtn.addActionListener(e -> {
            EcsConnection c = connection();
            if (c != null) {
                new UploadDialog(project, c).show();
            }
        });
        terminalBtn.addActionListener(e -> openTerminal(null));
        commandBtn.addActionListener(e -> {
            EcsConnection c = connection();
            if (c != null) {
                new SelectCommandDialog(project, c).show();
            }
        });
        moreBtn.addActionListener(this::showMoreMenu);
        moreBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMoreMenu(e);
            }
        });

        add(deployBtn);
        add(testBtn);
        add(uploadBtn);
        add(terminalBtn);
        add(commandBtn);
        add(moreBtn);
    }

    @Override
    public void setBackground(Color bg) {
        super.setBackground(bg);
        for (JButton btn : new JButton[]{deployBtn, testBtn, uploadBtn, terminalBtn, commandBtn, moreBtn}) {
            if (btn != null) {
                btn.setBackground(bg);
            }
        }
    }

    public String getState() {
        return hostId;
    }

    // ------------------------------------------------------------------------------------------
    // 更多 dropdown: 属性 / 远端文件 / 诊断 / 移除
    // ------------------------------------------------------------------------------------------

    private void showMoreMenu(ActionEvent e) {
        JPopupMenu menu = moreMenu();
        menu.show(moreBtn, 0, moreBtn.getHeight());
    }

    private void showMoreMenu(MouseEvent e) {
        JPopupMenu menu = moreMenu();
        menu.show(moreBtn, e.getX(), e.getY());
    }

    private JPopupMenu moreMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("属性", ev -> showProperties()));
        menu.add(item("远端文件", ev -> showRemoteFiles()));
        menu.add(item("诊断", ev -> runDiagnostics()));
        menu.add(item("移除", ev -> delete()));
        return menu;
    }

    private static JMenuItem item(String text, java.util.function.Consumer<ActionEvent> action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(action::accept);
        return item;
    }

    private void showProperties() {
        EcsConnection c = connection();
        if (c != null) {
            new AddHostDialog(project, c).show();
            onChange.run();
        }
    }

    private void showRemoteFiles() {
        EcsConnection c = connection();
        if (c == null) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "连接 " + c.host) {
            private SftpBrowserDialog dialog;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    dialog = new SftpBrowserDialog(project, c);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
            }

            @Override
            public void onFinished() {
                if (dialog != null) {
                    dialog.show();
                } else {
                    Messages.showErrorDialog("无法打开远端文件浏览: " + error, "远端文件");
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // 诊断: ensure arthas-boot.jar on the remote host, then open a terminal running it
    // ------------------------------------------------------------------------------------------

    private void runDiagnostics() {
        EcsConnection c = connection();
        if (c == null) {
            return;
        }
        String secret = EcsConnectionsService.getInstance().getSecret(hostId);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "诊断 - " + c.host) {
            private String error;
            private boolean ready;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try (SshSessionFactory factory = new SshSessionFactory(new HostKeyPolicy(true))) {
                    var session = factory.open(c, secret);
                    try {
                        RemoteCommandExecutor executor = new RemoteCommandExecutor();
                        int exists = executor.execute(session, "test -f " + ARTHAS_JAR,
                                (stderr, line) -> { }, () -> false);
                        if (exists != 0) {
                            String cmd = "mkdir -p /tmp && (curl -sfLo " + quote(ARTHAS_JAR) + " "
                                    + quote(ARTHAS_URL)
                                    + " || wget -qO " + quote(ARTHAS_JAR) + " " + quote(ARTHAS_URL) + ")";
                            int download = executor.execute(session, cmd,
                                    (stderr, line) -> { }, () -> false);
                            if (download != 0) {
                                error = "下载 Arthas 失败 (exit " + download + ")，请检查远端网络";
                                return;
                            }
                        }
                        ready = true;
                    } finally {
                        session.close();
                    }
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
            }

            @Override
            public void onFinished() {
                if (ready) {
                    openTerminal("java -jar " + ARTHAS_JAR);
                } else {
                    Messages.showErrorDialog("诊断失败: " + error, "诊断");
                }
            }
        });
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------------------------------
    // 终端
    // ------------------------------------------------------------------------------------------

    private void openTerminal(String initialCommand) {
        EcsConnection c = connection();
        if (c == null) {
            return;
        }
        String secret = EcsConnectionsService.getInstance().getSecret(hostId);
        String address = c.host + ":" + c.port;
        String tabTitle = c.host;
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "打开终端 " + address) {
            private SshTtyConnector connector;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    connector = new SshTtyConnector(c, secret, address, initialCommand);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
            }

            @Override
            public void onFinished() {
                if (connector != null) {
                    SshTerminalOpener.open(project, connector, tabTitle);
                } else {
                    Messages.showErrorDialog("无法连接 " + address + ": " + error, "终端");
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private EcsConnection connection() {
        return EcsConnectionsService.getInstance().findById(hostId);
    }

    private void testConnection() {
        EcsConnection connection = connection();
        if (connection == null) {
            return;
        }
        String secret = EcsConnectionsService.getInstance().getSecret(hostId);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result = EcsConnectionsConfigurable.testConnection(connection, secret, new HostKeyPolicy(true));
            boolean ok = "ok".equals(result);
            String msg = ok ? "连接成功: " + connection.host : result;
            ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(msg, "Test Connection"));
        });
    }

    private void delete() {
        int answer = Messages.showYesNoDialog(
                "确定删除主机 " + hostId + " ?", "Delete Host",
                Messages.getQuestionIcon());
        if (answer == Messages.YES) {
            EcsConnectionsService.getInstance().delete(hostId);
            onChange.run();
        }
    }
}
