package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the SSH credential rules used by the Add Host dialog: a private-key
 * profile without a passphrase is perfectly usable (unencrypted keys), while a password profile
 * without a password is incomplete.
 */
class SshProfileCredentialsTest {

    private static CloudToolkitDb.ProfileRow profile(String user, String passwd,
                                                     String privateKey, String passphrase) {
        CloudToolkitDb.ProfileRow row = new CloudToolkitDb.ProfileRow();
        row.user = user;
        row.passwd = passwd;
        row.privateKey = privateKey;
        row.passphrase = passphrase;
        return row;
    }

    @Test
    void keyProfileWithoutPassphraseIsComplete() {
        CloudToolkitDb.ProfileRow row = profile("root", null, "/root/.ssh/id_rsa", null);
        assertTrue(SshProfileCredentials.isComplete(row));
    }

    @Test
    void passwordProfileWithoutPasswordIsIncomplete() {
        CloudToolkitDb.ProfileRow row = profile("root", null, null, null);
        assertFalse(SshProfileCredentials.isComplete(row));
    }

    @Test
    void profileWithoutUserIsIncomplete() {
        assertFalse(SshProfileCredentials.isComplete(profile("", "secret", null, null)));
        assertFalse(SshProfileCredentials.isComplete(profile(null, "secret", null, null)));
        assertFalse(SshProfileCredentials.isComplete(null));
    }

    @Test
    void resolveFallsBackToTheStoredProfile() {
        CloudToolkitDb.ProfileRow row = profile("root", "secret", null, null);
        SshProfileCredentials.Credentials credentials = SshProfileCredentials.resolve(
                row, "  ", EcsConnection.AuthType.PASSWORD, "", "");
        assertEquals("root", credentials.user());
        assertEquals(EcsConnection.AuthType.PASSWORD, credentials.authType());
        assertEquals("secret", credentials.secret());
    }

    @Test
    void resolveKeepsAnEmptyPassphraseForUnencryptedKeys() {
        CloudToolkitDb.ProfileRow row = profile("root", null, "/root/.ssh/id_rsa", null);
        SshProfileCredentials.Credentials credentials = SshProfileCredentials.resolve(
                row, "root", EcsConnection.AuthType.PRIVATE_KEY, "/root/.ssh/id_rsa", "");
        assertEquals("", credentials.secret());
        assertEquals("/root/.ssh/id_rsa", credentials.privateKeyPath());
        assertEquals(EcsConnection.AuthType.PRIVATE_KEY, credentials.authType());
    }

    @Test
    void formValuesWinOverTheStoredProfile() {
        CloudToolkitDb.ProfileRow row = profile("root", "old", null, null);
        SshProfileCredentials.Credentials credentials = SshProfileCredentials.resolve(
                row, "admin", EcsConnection.AuthType.PASSWORD, "", "new");
        assertEquals("admin", credentials.user());
        assertEquals("new", credentials.secret());
    }
}
