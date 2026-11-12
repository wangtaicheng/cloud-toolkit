package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.settings.HostKeyPrompts;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicies;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextField;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
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

    public SftpBrowserDialog(Project project, EcsConnection target) throws Exception {
        super(project, true);
        this.project = project;
        this.factory = new SshSessionFactory(HostKeyPolicies.create(), HostKeyPrompts.TRUST);
        try {
            String secret = EcsConnectionsService.getInstance().getSecret(target.id);
            this.session = factory.open(target, secret);
            this.fs = SftpClientFactory.instance().createSftpFileSystem(session);
        } catch (Exception e) {
            factory.close();
            throw e;
        }
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
        String path = pathField.getText().trim();
        if (path.isEmpty() || !path.startsWith("/")) {
            path = "/";
            pathField.setText(path);
        }
        listModel.clear();
        dirNames.clear();
        if (!"/".equals(path)) {
            listModel.addElement(ARROW_UP);
            dirNames.add(ARROW_UP);
        }
        List<String> dirs = new ArrayList<>();
        List<String> files = new ArrayList<>();
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
            statusLabel.setText("读取目录失败: " + e.getMessage());
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
        try {
            String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            Path temp = Files.createTempFile("cloudtoolkit-", "-" + name);
            Files.copy(fs.getPath(remotePath), temp, StandardCopyOption.REPLACE_EXISTING);
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(temp.toFile());
            if (vf == null) {
                statusLabel.setText("无法打开下载的临时文件");
                return;
            }
            ApplicationManager.getApplication().invokeLater(() ->
                    FileEditorManager.getInstance(project).openFile(vf, true));
            statusLabel.setText("已打开: " + remotePath);
        } catch (Exception e) {
            statusLabel.setText("打开失败: " + e.getMessage());
        }
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
