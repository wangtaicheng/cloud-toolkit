package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.testFramework.LightPlatformTestCase;

import java.nio.file.Files;

public class EcsConnectionsServiceTest extends LightPlatformTestCase {

    static {
        if (System.getProperty("cloudtoolkit.db.path") == null) {
            try {
                System.setProperty("cloudtoolkit.db.path",
                        Files.createTempFile("cloudtoolkit-test", ".db").toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void testUpsertFindAndDeleteRoundTripsSecret() {
        EcsConnectionsService service = EcsConnectionsService.getInstance();

        EcsConnection c = new EcsConnection();
        c.displayName = "test-profile-" + System.nanoTime();
        c.host = "127.0.0.1";
        c.port = 22;
        c.username = "test";
        c.tag = "test-tag-" + System.nanoTime();
        service.upsert(c, "SUPER_SECRET");

        assertNotNull(c.id);
        EcsConnection found = service.findById(c.id);
        assertNotNull(found);
        assertEquals("SUPER_SECRET", service.getSecret(c.id));

        service.delete(c.id);
        assertNull(service.findById(c.id));
    }

    public void testFindByIdMissingReturnsNull() {
        assertNull(EcsConnectionsService.getInstance().findById("999999"));
        assertNull(EcsConnectionsService.getInstance().findById("not-a-number"));
    }
}
