package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.transfer.TransferProgress;

public interface DeployObserver {

    void onStage(DeployStage stage);

    void onOutput(boolean stderr, String line);

    void onProgress(TransferProgress progress);

    void onFinished(boolean success);
}
