package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

public final class EcsDeployRunConfigurationOptions extends RunConfigurationOptions {

    private final StoredProperty<String> connectionId = string("").provideDelegate(this, "connectionId");
    private final StoredProperty<String> artifactPath = string("").provideDelegate(this, "artifactPath");
    private final StoredProperty<String> remoteDirectory = string("/opt/app").provideDelegate(this, "remoteDirectory");
    private final StoredProperty<String> stopCommand = string("").provideDelegate(this, "stopCommand");
    private final StoredProperty<String> startCommand = string("").provideDelegate(this, "startCommand");

    public String getConnectionId() {
        return normalize(connectionId.getValue(this));
    }

    public void setConnectionId(String value) {
        connectionId.setValue(this, normalize(value));
    }

    public String getArtifactPath() {
        return normalize(artifactPath.getValue(this));
    }

    public void setArtifactPath(String value) {
        artifactPath.setValue(this, normalize(value));
    }

    public String getRemoteDirectory() {
        return normalize(remoteDirectory.getValue(this));
    }

    public void setRemoteDirectory(String value) {
        remoteDirectory.setValue(this, normalize(value));
    }

    public String getStopCommand() {
        return normalize(stopCommand.getValue(this));
    }

    public void setStopCommand(String value) {
        stopCommand.setValue(this, normalize(value));
    }

    public String getStartCommand() {
        return normalize(startCommand.getValue(this));
    }

    public void setStartCommand(String value) {
        startCommand.setValue(this, normalize(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
