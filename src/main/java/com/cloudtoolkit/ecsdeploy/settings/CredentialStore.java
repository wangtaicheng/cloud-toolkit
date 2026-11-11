package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;

public final class CredentialStore {

    private static final String KEY_PREFIX = "com.cloudtoolkit.ecsdeploy/";

    public enum SecretKind { PASSWORD, PASSPHRASE }

    public String getSecret(String connectionId, SecretKind kind) {
        return PasswordSafe.getInstance().getPassword(attributes(connectionId, kind));
    }

    public void setSecret(String connectionId, SecretKind kind, String value) {
        PasswordSafe.getInstance().setPassword(attributes(connectionId, kind), value);
    }

    public void deleteSecrets(String connectionId) {
        for (SecretKind kind : SecretKind.values()) {
            setSecret(connectionId, kind, null);
        }
    }

    private static CredentialAttributes attributes(String connectionId, SecretKind kind) {
        return new CredentialAttributes(KEY_PREFIX + connectionId + "/" + kind.name());
    }
}
