package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;

/**
 * Rules around the SSH credentials stored in a {@code profiles} row.
 *
 * <p>A profile is usable when it has a user plus a credential. For private-key profiles the
 * passphrase is optional (unencrypted keys are very common), for password profiles the password
 * is required. {@link #resolve} merges the values typed in the dialog with the stored profile:
 * the form always wins, the profile is only the fallback for blank fields.</p>
 */
public final class SshProfileCredentials {

    /** The credentials actually used for a connection attempt. */
    public record Credentials(String user, EcsConnection.AuthType authType,
                              String privateKeyPath, String secret) { }

    private SshProfileCredentials() {
    }

    /** Whether the stored profile carries everything needed to connect. */
    public static boolean isComplete(CloudToolkitDb.ProfileRow profile) {
        if (profile == null) {
            return false;
        }
        if (isBlank(profile.user)) {
            return false;
        }
        if (!isBlank(profile.privateKey)) {
            // The passphrase is optional - unencrypted private keys have none.
            return true;
        }
        return !isBlank(profile.passwd);
    }

    /**
     * Merges the dialog values with the profile. Blank form values fall back to the profile, so
     * the read-only echo of an existing profile keeps working.
     */
    public static Credentials resolve(CloudToolkitDb.ProfileRow profile, String user,
                                      EcsConnection.AuthType authType, String privateKeyPath,
                                      String secret) {
        String resolvedUser = trim(user);
        if (resolvedUser.isEmpty() && profile != null) {
            resolvedUser = trim(profile.user);
        }

        EcsConnection.AuthType resolvedType =
                authType == null ? EcsConnection.AuthType.PASSWORD : authType;

        String resolvedKey = trim(privateKeyPath);
        if (resolvedKey.isEmpty() && profile != null) {
            resolvedKey = trim(profile.privateKey);
        }

        String resolvedSecret = secret == null ? "" : secret;
        if (resolvedSecret.isEmpty() && profile != null) {
            resolvedSecret = resolvedType == EcsConnection.AuthType.PRIVATE_KEY
                    ? orEmpty(profile.passphrase)
                    : orEmpty(profile.passwd);
        }
        return new Credentials(resolvedUser, resolvedType, resolvedKey, resolvedSecret);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
