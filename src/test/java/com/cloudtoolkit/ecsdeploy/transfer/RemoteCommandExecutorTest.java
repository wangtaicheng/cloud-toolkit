package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.RemoteCommandException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.support.TestSshServer;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCommandExecutorTest {

    private TestSshServer server;
    private SshSessionFactory factory;
    private ClientSession session;
    private RemoteCommandExecutor executor;

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
        executor = new RemoteCommandExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        factory.close();
        server.stop();
    }

    @Test
    void splitsStdoutStderrAndReturnsExitCode() throws Exception {
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();

        int code = executor.execute(session, "echo 部署成功; echo-stderr bad; exit 7",
                (isErr, line) -> (isErr ? err : out).add(line), () -> false);

        assertEquals(7, code);
        assertTrue(out.contains("部署成功"));
        assertTrue(err.contains("bad"));
    }

    @Test
    void carriesTailOnNonZeroExit() throws Exception {
        RemoteCommandException e = assertThrows(RemoteCommandException.class,
                () -> executor.executeOrThrow(session, "seq 1 300; exit 3",
                        (a, b) -> { }, () -> false));

        assertEquals(3, e.exitCode());
        assertEquals(200, e.tail().size());
        assertEquals("300", e.tail().get(199));
    }

    @Test
    void cancelMiddlewareClosesChannel() throws Exception {
        assertThrows(DeploymentCanceledException.class,
                () -> executor.executeOrThrow(session, "sleep 30000",
                        (a, b) -> { }, () -> true));
    }

    @Test
    void passesCommandVerbatimAndHandlesLongLine() throws Exception {
        assertEquals(0, executor.execute(session, "echo 'a && b | c'",
                (a, b) -> { }, () -> false));
        assertEquals("echo 'a && b | c'", server.lastCommand());
        assertEquals(0, executor.execute(session, "long-line 100000",
                (a, b) -> { }, () -> false));
    }
}
