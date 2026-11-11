package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.testFramework.LightPlatformTestCase;

public class CredentialStoreTest extends LightPlatformTestCase {

    public void testSetGetDeleteSecrets() {
        CredentialStore store = new CredentialStore();

        store.setSecret("c2", CredentialStore.SecretKind.PASSPHRASE, "phrase");
        assertEquals("phrase", store.getSecret("c2", CredentialStore.SecretKind.PASSPHRASE));

        store.deleteSecrets("c2");
        assertNull(store.getSecret("c2", CredentialStore.SecretKind.PASSPHRASE));
    }
}
