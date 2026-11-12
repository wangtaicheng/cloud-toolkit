package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 远端文件 action dialog: browse the remote file system over SFTP and open a remote file
 * (downloaded to a temp file) in the IDE editor.
 */
public final class SftpBrowserDialog extends DialogWrapper {

    private static final String ARROW_UP = "..";

    private final Project project;
    private final SshSessionFactory factory;
    private final ClientSession session;
    private final SftpFileSystem fs;

    private final JBTextField pathField = new JBTextField();
    private final JBList<String> fileList = new JBList<>();
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JBLabel statusLabel = new JBLabel("");
    private final List<String> dirNames = new ArrayList<>();

    /**
     * @param factory 已建立的会话工厂（调用方负责在后台线程创建并负责最终关闭）
     * @param session 已建立且已认证的 SSH 会话
     * @param fs      已创建的 SFTP 文件系统
     */
    public SftpBrowserDialog(Project project, EcsConnection target,
                             SshSessionFactory factory, ClientSession session, SftpFileSystem fs) {
        super(project, true);
        this.project = project;
        this.factory = factory;
        this.session = session;
        this.fs = fs;
        setTitle("远端文件 - " + target.host);
        pathField.setText("/root");

        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });

        refresh();
        init();
    }

    private void refresh() {
        String raw = pathField.getText().trim();
        String path;
        if (raw.isEmpty() || !raw.startsWith("/")) {
            path = "/";
            pathField.setText(path);
        } else {
            path = raw;
        }
        statusLabel.setText("读取目录: " + path + " ...");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "读取 " + path) {
            private final List<String> dirs = new ArrayList<>();
            private final List<String> files = new ArrayList<>();
            private String err;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fs.getPath(path))) {
                    for (Path p : stream) {
                        String name = p.getFileName() == null ? "" : p.getFileName().toString();
                        if (Files.isDirectory(p)) {
                            dirs.add(name + "/");
                        } else {
                            files.add(name);
                        }
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }

            @Override
            public void onFinished() {
                listModel.clear();
                dirNames.clear();
                if (!"/".equals(path)) {
                    listModel.addElement(ARROW_UP);
                    dirNames.add(ARROW_UP);
                }
                if (err != null) {
                    statusLabel.setText("读取目录失败: " + err);
                    fileList.setModel(listModel);
                    return;
                }
                dirs.sort(Comparator.naturalOrder());
                files.sort(Comparator.naturalOrder());
                for (String d : dirs) {
                    listModel.addElement(d);
                    dirNames.add(d);
                }
                for (String f : files) {
                    listModel.addElement(f);
                    dirNames.add(f);
                }
                statusLabel.setText("目录: " + path + "  (" + (dirs.size() + files.size()) + " 项)");
                fileList.setModel(listModel);
            }
        });
    }

    private void goUp() {
        String current = pathField.getText().trim();
        int idx = current.lastIndexOf('/');
        pathField.setText(idx <= 0 ? "/" : current.substring(0, idx));
        refresh();
    }

    private void openSelected() {
        int index = fileList.getSelectedIndex();
        if (index < 0 || index >= dirNames.size()) {
            return;
        }
        String name = dirNames.get(index);
        if (ARROW_UP.equals(name)) {
            goUp();
            return;
        }
        boolean isDir = name.endsWith("/");
        String finalName = isDir ? name.substring(0, name.length() - 1) : name;
        String current = pathField.getText().trim();
        String child = current.endsWith("/") ? current + finalName : current + "/" + finalName;
        if (isDir) {
            pathField.setText(child);
            refresh();
        } else {
            openRemoteFile(child);
        }
    }

    private void openRemoteFile(String remotePath) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "下载 " + remotePath) {
            private Path temp;
            private String err;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                    temp = Files.createTempFile("cloudtoolkit-", "-" + name);
                    Files.copy(fs.getPath(remotePath), temp, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }

            @Override
            public void onFinished() {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (temp == null) {
                        statusLabel.setText("下载失败: " + err);
                        return;
                    }
                    VirtualFile vf = ReadAction.compute(() ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(temp.toFile()));
                    if (vf == null) {
                        statusLabel.setText("无法打开下载的临时文件");
                        return;
                    }
                    FileEditorManager.getInstance(project).openFile(vf, true);
                    statusLabel.setText("已打开: " + remotePath);
                });
            }
        });
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel top = new JPanel(new BorderLayout(8, 0));
        JButton refreshBtn = new JButton("刷新");
        refreshBtn.addActionListener(e -> refresh());
        top.add(pathField, BorderLayout.CENTER);
        top.add(refreshBtn, BorderLayout.EAST);

        fileList.setPreferredSize(new Dimension(480, 300));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        JButton upBtn = new JButton("上级目录");
        upBtn.addActionListener(e -> goUp());
        JButton openBtn = new JButton("打开文件");
        openBtn.addActionListener(e -> openSelected());
        bottom.add(upBtn, BorderLayout.WEST);
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(openBtn, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(top, BorderLayout.NORTH);
        root.add(fileList, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    @Override
    protected void dispose() {
        try {
            fs.close();
        } catch (Exception ignored) {
            // best effort
        }
        try {
            session.close();
        } catch (Exception ignored) {
            // best effort
        }
        factory.close();
        super.dispose();
    }
}
