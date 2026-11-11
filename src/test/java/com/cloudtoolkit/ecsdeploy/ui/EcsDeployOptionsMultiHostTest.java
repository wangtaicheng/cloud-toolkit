package com.cloudtoolkit.ecsdeploy.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsDeployOptionsMultiHostTest {

    @Test
    void targetHostIdsRoundTrip() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        options.setTargetHostIdList(List.of("3", "7"));
        assertEquals(List.of("3", "7"), options.getTargetHostIdList());
    }

    @Test
    void targetHostIdsIgnoreBlanksAndDuplicates() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        options.setTargetHostIds(" 3 , 3 ,, 7 ");
        assertEquals(List.of("3", "7"), options.getTargetHostIdList());
    }

    @Test
    void buildTypeDefaultsToUpload() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        assertEquals(EcsDeployRunConfigurationOptions.BUILD_UPLOAD, options.getBuildType());
        options.setBuildType(EcsDeployRunConfigurationOptions.BUILD_MAVEN);
        assertEquals(EcsDeployRunConfigurationOptions.BUILD_MAVEN, options.getBuildType());
    }

    @Test
    void commandAndTerminalOptionsRoundTrip() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        options.setBeforeDeployCommand("stop.sh");
        options.setAfterDeployCommand("tail -f app.log");
        options.setOpenTerminal(true);
        options.setAutoOpenAfterDeploy(true);
        assertEquals("stop.sh", options.getBeforeDeployCommand());
        assertEquals("tail -f app.log", options.getAfterDeployCommand());
        assertTrue(options.isOpenTerminal());
        assertTrue(options.isAutoOpenAfterDeploy());
    }

    @Test
    void terminalOptionsDefaultToDisabled() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        assertFalse(options.isOpenTerminal());
        assertFalse(options.isAutoOpenAfterDeploy());
        assertEquals("", options.getBeforeDeployCommand());
        assertEquals("", options.getAfterDeployCommand());
    }
}
