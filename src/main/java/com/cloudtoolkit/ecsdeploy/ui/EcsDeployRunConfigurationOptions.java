package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EcsDeployRunConfigurationOptions extends RunConfigurationOptions {

    /** Build type: {@code upload} (upload a file), {@code maven} or {@code gradle}. */
    public static final String BUILD_UPLOAD = "upload";
    public static final String BUILD_MAVEN = "maven";
    public static final String BUILD_GRADLE = "gradle";

    private final StoredProperty<String> connectionId = string("").provideDelegate(this, "connectionId");
    private final StoredProperty<String> artifactPath = string("").provideDelegate(this, "artifactPath");
    private final StoredProperty<String> remoteDirectory = string("/opt/app").provideDelegate(this, "remoteDirectory");
    private final StoredProperty<String> stopCommand = string("").provideDelegate(this, "stopCommand");
    private final StoredProperty<String> startCommand = string("").provideDelegate(this, "startCommand");
    private final StoredProperty<String> buildType = string(BUILD_UPLOAD).provideDelegate(this, "buildType");
    private final StoredProperty<String> targetHostIds = string("").provideDelegate(this, "targetHostIds");
    private final StoredProperty<String> beforeDeployCommand =
            string("").provideDelegate(this, "beforeDeployCommand");
    private final StoredProperty<String> afterDeployCommand =
            string("").provideDelegate(this, "afterDeployCommand");
    private final StoredProperty<Boolean> openTerminal = property(false).provideDelegate(this, "openTerminal");
    private final StoredProperty<Boolean> autoOpenAfterDeploy =
            property(false).provideDelegate(this, "autoOpenAfterDeploy");

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

    public String getBuildType() {
        String value = normalize(buildType.getValue(this));
        return value.isEmpty() ? BUILD_UPLOAD : value;
    }

    public void setBuildType(String value) {
        buildType.setValue(this, normalize(value));
    }

    /** Comma separated list of the selected target host ids. */
    public String getTargetHostIds() {
        return normalize(targetHostIds.getValue(this));
    }

    public void setTargetHostIds(String value) {
        targetHostIds.setValue(this, normalize(value));
    }

    public List<String> getTargetHostIdList() {
        String raw = getTargetHostIds();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String id : Arrays.asList(raw.split(","))) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public void setTargetHostIdList(List<String> ids) {
        setTargetHostIds(String.join(",", ids == null ? List.of() : ids));
    }

    /** Command executed on the target host before the artifact is uploaded. */
    public String getBeforeDeployCommand() {
        return normalize(beforeDeployCommand.getValue(this));
    }

    public void setBeforeDeployCommand(String value) {
        beforeDeployCommand.setValue(this, normalize(value));
    }

    /** Command executed on the target host after the deployment finished. */
    public String getAfterDeployCommand() {
        return normalize(afterDeployCommand.getValue(this));
    }

    public void setAfterDeployCommand(String value) {
        afterDeployCommand.setValue(this, normalize(value));
    }

    public boolean isOpenTerminal() {
        return Boolean.TRUE.equals(openTerminal.getValue(this));
    }

    public void setOpenTerminal(boolean value) {
        openTerminal.setValue(this, value);
    }

    public boolean isAutoOpenAfterDeploy() {
        return Boolean.TRUE.equals(autoOpenAfterDeploy.getValue(this));
    }

    public void setAutoOpenAfterDeploy(boolean value) {
        autoOpenAfterDeploy.setValue(this, value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
