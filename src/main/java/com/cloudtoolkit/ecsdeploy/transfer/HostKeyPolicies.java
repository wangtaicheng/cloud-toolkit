package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.settings.CloudToolkitDeploySettings;

/**
 * Creates the host key policy of the plugin.
 *
 * <p>Every entry point (the dashboard, the settings page, the run configuration) uses the policy of
 * this factory, so a connection behaves the same no matter where it was started.</p>
 */
public final class HostKeyPolicies {

    private HostKeyPolicies() {
    }

    /** The policy of the current settings: unknown keys are only accepted when the user allows it. */
    public static HostKeyPolicy create() {
        return new HostKeyPolicy(skipHostKeyVerification());
    }

    private static boolean skipHostKeyVerification() {
        try {
            return CloudToolkitDeploySettings.getInstance().isSkipHostKeyVerification();
        } catch (RuntimeException e) {
            // The IDE is not available (unit tests for example): verify the host keys.
            return false;
        }
    }
}
