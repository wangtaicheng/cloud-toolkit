package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;

/**
 * The settings of the deployment plugin that are not bound to a single host.
 */
@Service(Service.Level.APP)
@State(name = "CloudToolkitDeploySettings", storages = @Storage("cloudtoolkit-deploy.xml"))
public final class CloudToolkitDeploySettings implements PersistentStateComponent<CloudToolkitDeploySettings.State> {

    /** The persisted state of the settings. */
    public static final class State {
        /** Whether the host keys of the servers are trusted without asking (not recommended). */
        public boolean skipHostKeyVerification = false;
    }

    private State state = new State();

    public static CloudToolkitDeploySettings getInstance() {
        return ApplicationManager.getApplication().getService(CloudToolkitDeploySettings.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(State state) {
        XmlSerializerUtil.copyBean(state, this.state);
    }

    /** Whether the host keys of the servers are trusted without asking. */
    public boolean isSkipHostKeyVerification() {
        return state.skipHostKeyVerification;
    }

    public void setSkipHostKeyVerification(boolean value) {
        state.skipHostKeyVerification = value;
    }
}
