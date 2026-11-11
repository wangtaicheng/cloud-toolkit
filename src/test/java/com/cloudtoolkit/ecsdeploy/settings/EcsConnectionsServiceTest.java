package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.testFramework.LightPlatformTestCase;

public class EcsConnectionsServiceTest extends LightPlatformTestCase {

    public void testUpsertFindAndDeleteRemovesCredentials() {
        EcsConnectionsService service = EcsConnectionsService.getInstance();
        CredentialStore store = new CredentialStore();

        EcsConnection c = new EcsConnection();
        c.id = "c1";
        c.displayName = "prod";
        service.upsert(c, "SUPER_SECRET");

        assertEquals("prod", service.findById("c1").displayName);
        assertEquals("SUPER_SECRET", store.getSecret("c1", CredentialStore.SecretKind.PASSWORD));

        service.delete("c1");

        assertNull(service.findById("c1"));
        assertNull(store.getSecret("c1", CredentialStore.SecretKind.PASSWORD));
    }

    public void testDeletedConnectionReturnsNull() {
        assertNull(EcsConnectionsService.getInstance().findById("does-not-exist"));
    }
}
