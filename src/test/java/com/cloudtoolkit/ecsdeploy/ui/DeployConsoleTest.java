package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.deploy.DeployStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeployConsoleTest {

    @Test
    void cancelledIsNotRenderedAsError() {
        assertEquals("已取消", DeployConsole.resultText(DeployConsole.Result.CANCELLED));
        assertEquals("部署失败", DeployConsole.resultText(DeployConsole.Result.FAILURE));
    }

    @Test
    void stageTitlesAreLocalizedForAllSevenStages() {
        for (DeployStage stage : DeployStage.values()) {
            assertFalse(DeployConsole.stageTitle(stage).isBlank());
            assertFalse(DeployConsole.stageTitle(stage).equals(stage.name()));
        }
    }
}
