package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.ConfigurationException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.RemoteCommandException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SftpTransferException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.transfer.TransferProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployPipelineTest {

    @TempDir
    Path dir;

    private final AtomicBoolean canceled = new AtomicBoolean();
    private FakeDeployTarget target;
    private RecordingObserver observer;
    private DeployPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("app.jar"), "payload", StandardCharsets.UTF_8);
        target = new FakeDeployTarget(canceled);
        observer = new RecordingObserver();
        pipeline = new DeployPipeline(target, observer, canceled::get);
    }

    @Test
    void runsStagesInOrder() throws Exception {
        pipeline.run(validContext());

        assertEquals(List.of(DeployStage.VALIDATE, DeployStage.CONNECT, DeployStage.PREPARE,
                DeployStage.BEFORE_DEPLOY, DeployStage.UPLOAD, DeployStage.STOP, DeployStage.START,
                DeployStage.FINISH),
                observer.stages());
        assertTrue(target.calls.containsAll(List.of("prepare", "upload", "stop", "start")));
        assertTrue(target.closed);
    }

    @Test
    void stopNonZeroDoesNotAbort() throws Exception {
        target.stopExitCode = 1;

        pipeline.run(validContext());

        assertTrue(target.calls.contains("start"));
    }

    @Test
    void runsTheBeforeAndAfterDeployCommands() throws Exception {
        EcsConnection connection = new EcsConnection();
        connection.id = "c1";
        connection.host = "127.0.0.1";
        connection.username = "test";
        connection.authType = EcsConnection.AuthType.PASSWORD;
        DeployContext ctx = new DeployContext(connection, "pw", dir.resolve("app.jar"), "/opt/app",
                "echo stop", "", "echo before", "echo after");

        pipeline.run(ctx);

        assertTrue(target.calls.contains("beforeDeploy"));
        assertTrue(target.calls.contains("afterDeploy"));
        assertTrue(observer.stages().contains(DeployStage.BEFORE_DEPLOY));
        assertTrue(observer.stages().contains(DeployStage.AFTER_DEPLOY));
        assertFalse(observer.stages().contains(DeployStage.START));
    }

    @Test
    void startNonZeroFailsWithExitCodeAndTail() {
        target.startExitCode = 3;

        RemoteCommandException e = assertThrows(RemoteCommandException.class,
                () -> pipeline.run(validContext()));

        assertEquals(3, e.exitCode());
        assertEquals(200, e.tail().size());
    }

    @Test
    void uploadFailureSkipsStart() {
        target.failOn = "upload";

        assertThrows(SftpTransferException.class, () -> pipeline.run(validContext()));
        assertFalse(target.calls.contains("start"));
        assertTrue(target.closed);
    }

    @Test
    void validationFailureSkipsConnect() {
        assertThrows(ConfigurationException.class, () -> pipeline.run(contextWithMissingArtifact()));
        assertFalse(target.calls.contains("prepare"));
    }

    @Test
    void cancelCallsCloseAndStopsBeforeNextStage() {
        target.cancelAfter = "upload";

        assertThrows(DeploymentCanceledException.class, () -> pipeline.run(validContext()));
        assertTrue(target.closed);
        assertFalse(target.calls.contains("start"));
        assertEquals(DeployStage.FINISH, observer.stages().get(observer.stages().size() - 1));
    }

    private DeployContext validContext() {
        return context(dir.resolve("app.jar"));
    }

    private DeployContext contextWithMissingArtifact() {
        return context(dir.resolve("missing.jar"));
    }

    private static DeployContext context(Path artifact) {
        EcsConnection connection = new EcsConnection();
        connection.id = "c1";
        connection.host = "127.0.0.1";
        connection.username = "test";
        connection.authType = EcsConnection.AuthType.PASSWORD;
        return new DeployContext(connection, "pw", artifact, "/opt/app", "echo stop", "echo start");
    }

    private static final class FakeDeployTarget implements DeployTarget {

        private final AtomicBoolean cancelled;
        final List<String> calls = new ArrayList<>();
        boolean closed;
        int stopExitCode;
        int startExitCode;
        String failOn;
        String cancelAfter;

        private FakeDeployTarget(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        private void enter(String stage) throws DeployException {
            calls.add(stage);
            if (stage.equals(failOn)) {
                throw new SftpTransferException("fake failure at " + stage);
            }
            if (stage.equals(cancelAfter)) {
                cancelled.set(true);
            }
        }

        @Override
        public void prepare(DeployContext ctx) throws DeployException {
            enter("prepare");
        }

        @Override
        public void upload(DeployContext ctx, Consumer<TransferProgress> progress,
                           BooleanSupplier canceled) throws DeployException {
            enter("upload");
        }

        @Override
        public int beforeDeploy(DeployContext ctx, BiConsumer<Boolean, String> output,
                                BooleanSupplier canceled) throws DeployException {
            enter("beforeDeploy");
            return 0;
        }

        @Override
        public int afterDeploy(DeployContext ctx, BiConsumer<Boolean, String> output,
                               BooleanSupplier canceled) throws DeployException {
            enter("afterDeploy");
            return 0;
        }

        @Override
        public int stop(DeployContext ctx, BiConsumer<Boolean, String> output,
                        BooleanSupplier canceled) throws DeployException {
            enter("stop");
            return stopExitCode;
        }

        @Override
        public int start(DeployContext ctx, BiConsumer<Boolean, String> output,
                         BooleanSupplier canceled) throws DeployException {
            enter("start");
            if (startExitCode != 0) {
                List<String> tail = new ArrayList<>();
                for (int i = 1; i <= 200; i++) {
                    tail.add("line-" + i);
                }
                throw new RemoteCommandException(startExitCode, tail);
            }
            return 0;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingObserver implements DeployObserver {

        private final List<DeployStage> stages = new ArrayList<>();
        private final List<String> outputs = new ArrayList<>();
        private final List<TransferProgress> progress = new ArrayList<>();
        private Boolean finished;

        List<DeployStage> stages() {
            return stages;
        }

        @Override
        public void onStage(DeployStage stage) {
            stages.add(stage);
        }

        @Override
        public void onOutput(boolean stderr, String line) {
            outputs.add((stderr ? "err:" : "out:") + line);
        }

        @Override
        public void onProgress(TransferProgress value) {
            progress.add(value);
        }

        @Override
        public void onFinished(boolean success) {
            finished = success;
        }
    }
}
