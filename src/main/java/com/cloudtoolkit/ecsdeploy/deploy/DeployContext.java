package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.ConfigurationException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.util.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DeployContext {

    private final EcsConnection connection;
    private final String secret;
    private final Path artifactPath;
    private final String remoteDirectory;
    private final String stopCommand;
    private final String startCommand;

    public DeployContext(EcsConnection connection, String secret, Path artifactPath,
                         String remoteDirectory, String stopCommand, String startCommand) {
        this.connection = connection;
        this.secret = secret;
        this.artifactPath = artifactPath;
        this.remoteDirectory = remoteDirectory;
        this.stopCommand = stopCommand;
        this.startCommand = startCommand;
    }

    public EcsConnection connection() {
        return connection;
    }

    public String secret() {
        return secret;
    }

    public Path artifactPath() {
        return artifactPath;
    }

    public String remoteDirectory() {
        return remoteDirectory;
    }

    public String stopCommand() {
        return stopCommand;
    }

    public String startCommand() {
        return startCommand;
    }

    public void validate() throws ConfigurationException {
        if (connection == null) {
            throw new ConfigurationException("连接配置不存在，请重新选择");
        }
        if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
            throw new ConfigurationException("构建产物不存在或不是文件: " + artifactPath);
        }
        try {
            if (Files.size(artifactPath) == 0) {
                throw new ConfigurationException("构建产物为空文件: " + artifactPath);
            }
        } catch (IOException e) {
            throw new ConfigurationException("无法读取构建产物: " + artifactPath, e);
        }
        if (remoteDirectory == null || !PathUtils.isAbsoluteRemote(remoteDirectory)) {
            throw new ConfigurationException("远端目录必须是绝对路径: " + remoteDirectory);
        }
        if (startCommand == null || startCommand.isBlank()) {
            throw new ConfigurationException("start 命令不能为空");
        }
    }

    @Override
    public String toString() {
        return "DeployContext{connection=" + (connection == null ? null : connection.id)
                + ", artifactPath=" + artifactPath
                + ", remoteDirectory=" + remoteDirectory
                + ", stopCommand=" + stopCommand
                + ", startCommand=" + startCommand + "}";
    }
}
