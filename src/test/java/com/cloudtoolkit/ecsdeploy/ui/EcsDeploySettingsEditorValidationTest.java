package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.intellij.testFramework.LightPlatformTestCase;

import java.util.List;

public class EcsDeploySettingsEditorValidationTest extends LightPlatformTestCase {

    private EcsConnectionsService service;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = EcsConnectionsService.getInstance();
        EcsConnection connection = new EcsConnection();
        connection.id = "c1";
        connection.displayName = "prod";
        connection.host = "127.0.0.1";
        connection.username = "test";
        connection.authType = EcsConnection.AuthType.PASSWORD;
        service.upsert(connection, "pw");
    }

    @Override
    protected void tearDown() throws Exception {
        service.delete("c1");
        super.tearDown();
    }

    public void testReportsMissingConnection() {
        EcsDeployRunConfigurationOptions options = validOptions();
        options.setConnectionId("deleted-id");

        assertTrue(EcsDeploySettingsEditor.validate(options, service)
                .contains("连接不存在，请重新选择"));
    }

    public void testReportsRelativeRemoteDirectory() {
        EcsDeployRunConfigurationOptions options = validOptions();
        options.setRemoteDirectory("opt/app");

        assertTrue(EcsDeploySettingsEditor.validate(options, service).stream()
                .anyMatch(message -> message.contains("绝对路径")));
    }

    public void testReportsEmptyStartCommand() {
        EcsDeployRunConfigurationOptions options = validOptions();
        options.setStartCommand("");

        assertTrue(EcsDeploySettingsEditor.validate(options, service).stream()
                .anyMatch(message -> message.contains("start")));
    }

    public void testStopCommandMayBeEmpty() {
        EcsDeployRunConfigurationOptions options = validOptions();
        options.setStopCommand("");

        List<String> errors = EcsDeploySettingsEditor.validate(options, service);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private static EcsDeployRunConfigurationOptions validOptions() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        options.setConnectionId("c1");
        options.setArtifactPath("/tmp/app.jar");
        options.setRemoteDirectory("/opt/app");
        options.setStopCommand("echo stop");
        options.setStartCommand("echo start");
        return options;
    }
}
