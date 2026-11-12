package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.support.TestSshServer;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.intellij.testFramework.LightPlatformTestCase;

import java.nio.file.Files;

public class EcsConnectionsConfigurableTest extends LightPlatformTestCase {

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

    private TestSshServer server;
    private EcsConnectionsService service;
    private EcsConnectionsConfigurable configurable;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        server = TestSshServer.start();
        service = EcsConnectionsService.getInstance();
        configurable = new EcsConnectionsConfigurable();
    }

    @Override
    protected void tearDown() throws Exception {
        server.stop();
        super.tearDown();
    }

    public void testConnectionSucceedsWithCorrectPassword() {
        assertEquals("ok", EcsConnectionsConfigurable.testConnection(conn("pw"), "pw", skippingHostKeys()));
    }

    public void testConnectionReportsAuthenticationFailure() {
        assertTrue(EcsConnectionsConfigurable.testConnection(conn("pw"), "wrong", skippingHostKeys())
                .contains("认证"));
    }

    public void testConnectionNeverPerformsRemoteWrites() {
        EcsConnectionsConfigurable.testConnection(conn("pw"), "pw", skippingHostKeys());
        assertEquals(0, server.commandCount());
    }

    /** The host key of the test server is not in the known hosts file. */
    private static HostKeyPolicy skippingHostKeys() {
        return new HostKeyPolicy(true);
    }

    public void testAddAndDeletePersistsToDatabase() {
        EcsConnection c = conn("pw");
        configurable.add(c, "pw");

        assertNotNull(c.id);
        assertNotNull(service.findById(c.id));

        configurable.delete(c.id);
        assertNull(service.findById(c.id));
    }

    private EcsConnection conn(String password) {
        EcsConnection connection = new EcsConnection();
        connection.id = "c1";
        connection.displayName = "prod";
        connection.host = "127.0.0.1";
        connection.port = server.port();
        connection.username = "test";
        connection.authType = EcsConnection.AuthType.PASSWORD;
        return connection;
    }
}
