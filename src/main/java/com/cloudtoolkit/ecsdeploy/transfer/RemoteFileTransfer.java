package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SftpTransferException;
import com.cloudtoolkit.ecsdeploy.util.PathUtils;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class RemoteFileTransfer {

    private static final Logger LOG = Logger.getInstance(RemoteFileTransfer.class);
    private static final int BUFFER_SIZE = 32 * 1024;

    public void upload(ClientSession session, Path local, String remoteDir,
                       Consumer<TransferProgress> progress)
            throws IOException, SftpTransferException {
        String directory = PathUtils.normalizeRemoteDirectory(remoteDir);
        String fileName = local.getFileName().toString();
        String temporary = PathUtils.remoteFilePath(directory, fileName + "." + System.nanoTime() + ".tmp");
        String target = PathUtils.remoteFilePath(directory, fileName);
        long total = Files.size(local);

        try (SftpClient client = SftpClientFactory.instance().createSftpClient(session)) {
            LOG.info("SFTP 协商协议版本: " + client.getVersion()
                    + " (v3 不支持 CopyMode.Overwrite，已改用删除后重命名)");
            boolean renamed = false;
            try {
                mkdirs(client, directory);
                try (InputStream in = Files.newInputStream(local);
                     SftpClient.CloseableHandle handle = client.open(temporary,
                             SftpClient.OpenMode.Write,
                             SftpClient.OpenMode.Create,
                             SftpClient.OpenMode.Truncate)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long transferred = 0;
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        client.write(handle, transferred, buffer, 0, read);
                        transferred += read;
                        progress.accept(new TransferProgress(transferred, total));
                    }
                }
                // CopyMode.Overwrite sends a flagged SSH_FXP_RENAME only supported on
                // SFTP v6+/servers advertising the rename extension. OpenSSH sftp-server
                // (v3) rejects it with "copy options can not be used with this SFTP
                // version". Remove any existing target first, then do a plain rename for
                // maximum compatibility across SFTP versions.
                if (targetExists(client, target)) {
                    removeQuietly(client, target);
                }
                client.rename(temporary, target);
                renamed = true;
            } finally {
                if (!renamed) {
                    removeQuietly(client, temporary);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new SftpTransferException("SFTP 上传失败：" + target, e);
        }
    }

    private static void mkdirs(SftpClient client, String directory) throws IOException {
        StringBuilder current = new StringBuilder();
        for (String segment : directory.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current.append('/').append(segment);
            String path = current.toString();
            try {
                client.stat(path);
            } catch (IOException | RuntimeException missing) {
                try {
                    client.mkdir(path);
                } catch (IOException | RuntimeException denied) {
                    throw new IOException("无法创建远端目录：" + path, denied);
                }
            }
        }
    }

    private static void removeQuietly(SftpClient client, String path) {
        try {
            client.remove(path);
        } catch (IOException | RuntimeException ignored) {
            // best effort cleanup of the temporary upload
        }
    }

    private static boolean targetExists(SftpClient client, String path) {
        try {
            client.stat(path);
            return true;
        } catch (IOException | RuntimeException missing) {
            return false;
        }
    }
}
