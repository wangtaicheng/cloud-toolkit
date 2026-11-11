package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshAuthenticationException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.support.TestSshServer;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.cloudtoolkit.ecsdeploy.settings.EcsConnection.AuthType;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshSessionFactoryTest {

    @TempDir
    Path tempDir;

    private TestSshServer server;
    private SshSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        server = TestSshServer.start();
        // An explicit (missing) configuration keeps the SSH configuration of the machine out.
        factory = new SshSessionFactory(new HostKeyPolicy(true), tempDir.resolve("ssh_config"));
    }

    @AfterEach
    void tearDown() {
        factory.close();
        server.stop();
    }

    private EcsConnection conn(AuthType authType) {
        EcsConnection c = new EcsConnection();
        c.id = "test";
        c.displayName = "test";
        c.host = "127.0.0.1";
        c.port = server.port();
        c.username = "test";
        c.authType = authType;
        return c;
    }

    @Test
    void opensSessionWithPassword() throws Exception {
        try (ClientSession session = factory.open(conn(AuthType.PASSWORD), "pw")) {
            assertTrue(session.isAuthenticated());
        }
    }

    @Test
    void reportsMissingPrivateKeyFile() {
        EcsConnection c = conn(AuthType.PRIVATE_KEY);
        c.privateKeyPath = "/no/such/key.pem";

        assertThrows(SshAuthenticationException.class, () -> factory.open(c, null));
    }

    @Test
    void reportsMalformedPrivateKey(@TempDir Path dir) throws Exception {
        Path bad = Files.write(dir.resolve("key.pem"), "not a key".getBytes(StandardCharsets.UTF_8));
        EcsConnection c = conn(AuthType.PRIVATE_KEY);
        c.privateKeyPath = bad.toString();

        assertThrows(SshAuthenticationException.class, () -> factory.open(c, null));
    }

    @Test
    void reportsMissingPassphrase(@TempDir Path dir) throws Exception {
        Path key = TestSshServer.encryptedPrivateKey(dir);
        EcsConnection c = conn(AuthType.PRIVATE_KEY);
        c.privateKeyPath = key.toString();
        c.hasPassphrase = true;

        assertThrows(SshAuthenticationException.class, () -> factory.open(c, null));
    }

    @Test
    void connectsToTheHostOfTheSshConfiguration() throws Exception {
        Path config = Files.writeString(tempDir.resolve("config"), """
                Host 107
                    HostName 127.0.0.1
                    Port %d
                    User test
                """.formatted(server.port()));

        EcsConnection c = conn(AuthType.PASSWORD);
        c.host = "107";
        c.port = 22;

        try (SshSessionFactory aliasFactory = new SshSessionFactory(new HostKeyPolicy(true), config);
             ClientSession session = aliasFactory.open(c, "pw")) {
            assertTrue(session.isAuthenticated());
        }
    }
}
