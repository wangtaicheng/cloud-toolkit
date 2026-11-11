package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.support.TestSshServer;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;

public class EcsConnectionsConfigurableTest extends LightPlatformTestCase {

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
        service.delete("c1");
        server.stop();
        super.tearDown();
    }

    public void testConnectionSucceedsWithCorrectPassword() {
        assertEquals("ok", EcsConnectionsConfigurable.testConnection(conn("pw"), "pw"));
    }

    public void testConnectionReportsAuthenticationFailure() {
        assertTrue(EcsConnectionsConfigurable.testConnection(conn("pw"), "wrong").contains("认证"));
    }

    public void testConnectionNeverPerformsRemoteWrites() {
        EcsConnectionsConfigurable.testConnection(conn("pw"), "pw");

        assertEquals(0, server.commandCount());
    }

    public void testAddEditDeletePersistsOnlyNonSensitiveFields() {
        configurable.add(conn("pw"), "pw");

        EcsConnectionState state = service.getState();
        Element xml = XmlSerializer.serialize(state);
        assertFalse(JDOMUtil.write(xml).contains("pw"));

        configurable.delete("c1");
        assertTrue(configurable.list().isEmpty());
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
