package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SftpTransferException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFileTransferTest {

    private TestSshServer server;
    private SshSessionFactory factory;
    private ClientSession session;
    private RemoteFileTransfer transfer;

    @BeforeEach
    void setUp() throws Exception {
        server = TestSshServer.start();
        factory = new SshSessionFactory(new HostKeyPolicy(true));
        EcsConnection connection = new EcsConnection();
        connection.host = "127.0.0.1";
        connection.port = server.port();
        connection.username = "test";
        connection.authType = EcsConnection.AuthType.PASSWORD;
        session = factory.open(connection, "pw");
        transfer = new RemoteFileTransfer();
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        factory.close();
        server.stop();
    }

    @Test
    void createsDirectoryAndUploadsAtomically(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("app.jar"), "payload".getBytes(StandardCharsets.UTF_8));
        List<TransferProgress> seen = new ArrayList<>();

        transfer.upload(session, jar, "/opt/my app/deploy", seen::add);

        assertEquals("payload", Files.readString(server.root().resolve("opt/my app/deploy/app.jar")));
        assertTrue(seen.stream().anyMatch(p -> p.percent() == 100));
        try (Stream<Path> files = Files.list(server.root().resolve("opt/my app/deploy"))) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void failsWhenRemoteDirectoryIsNotWritable(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("app.jar"), "payload".getBytes(StandardCharsets.UTF_8));

        assertThrows(SftpTransferException.class,
                () -> transfer.upload(session, jar, server.readOnlyDirectory(), p -> { }));
    }
}
