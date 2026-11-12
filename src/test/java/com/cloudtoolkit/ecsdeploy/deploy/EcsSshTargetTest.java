package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.support.TestSshServer;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicy;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteCommandExecutor;
import com.cloudtoolkit.ecsdeploy.transfer.RemoteFileTransfer;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsSshTargetTest {

    @TempDir
    Path dir;

    private TestSshServer server;
    private SshSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        server = TestSshServer.start();
        factory = new SshSessionFactory(new HostKeyPolicy(true));
    }

    @AfterEach
    void tearDown() {
        factory.close();
        server.stop();
    }

    @Test
    void uploadsFileAndRunsStartCommand() throws Exception {
        EcsSshTarget target = new EcsSshTarget(factory, new RemoteFileTransfer(),
                new RemoteCommandExecutor());
        DeployContext ctx = validContext();

        target.prepare(ctx);
        target.upload(ctx, p -> { }, () -> false);
        assertEquals(0, target.start(ctx, (isErr, line) -> { }, () -> false));
        target.close();

        assertTrue(Files.exists(server.root().resolve("opt/app/app.jar")));
        assertEquals("echo started", server.lastCommand());
    }

    @Test
    void blankStopCommandSkipsExecution() throws Exception {
        EcsSshTarget target = new EcsSshTarget(factory, new RemoteFileTransfer(),
                new RemoteCommandExecutor());
        DeployContext ctx = new DeployContext(connection(), "pw", artifact(), "/opt/app", "  ", "echo started");

        target.prepare(ctx);
        int before = server.commandCount();
        assertEquals(0, target.stop(ctx, (isErr, line) -> { }, () -> false));
        target.close();

        assertEquals(before, server.commandCount());
    }

    private DeployContext validContext() throws Exception {
        return new DeployContext(connection(), "pw", artifact(), "/opt/app", "echo stopped", "echo started");
    }

    private EcsConnection connection() {
        EcsConnection c = new EcsConnection();
        c.id = "c1";
        c.host = "127.0.0.1";
        c.port = server.port();
        c.username = "test";
        c.authType = EcsConnection.AuthType.PASSWORD;
        return c;
    }

    private Path artifact() throws Exception {
        return Files.write(dir.resolve("app.jar"), "payload".getBytes(StandardCharsets.UTF_8));
    }
}
