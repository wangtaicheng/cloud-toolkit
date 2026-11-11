package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.DeploymentCanceledException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.RemoteCommandException;
import com.cloudtoolkit.ecsdeploy.util.OutputSanitizer;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public final class RemoteCommandExecutor {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final int TAIL_LIMIT = 200;

    public int execute(ClientSession session, String command,
                       BiConsumer<Boolean, String> output, BooleanSupplier canceled)
            throws IOException, RemoteCommandException, DeploymentCanceledException {
        return run(session, command, output, canceled, new ArrayDeque<>());
    }

    public int executeOrThrow(ClientSession session, String command,
                              BiConsumer<Boolean, String> output, BooleanSupplier canceled)
            throws IOException, RemoteCommandException, DeploymentCanceledException {
        ArrayDeque<String> tail = new ArrayDeque<>();
        int exitCode = run(session, command, output, canceled, tail);
        if (exitCode != 0) {
            throw new RemoteCommandException(exitCode, new ArrayList<>(tail));
        }
        return exitCode;
    }

    private int run(ClientSession session, String command,
                    BiConsumer<Boolean, String> output, BooleanSupplier canceled,
                    ArrayDeque<String> tail) throws IOException, DeploymentCanceledException {
        LineCollector stdout = new LineCollector(false, output, tail);
        LineCollector stderr = new LineCollector(true, output, tail);
        ChannelExec channel = session.createExecChannel(command);
        try {
            channel.setOut(stdout);
            channel.setErr(stderr);
            channel.open().verify(Duration.ofSeconds(15));

            long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
            while (true) {
                if (canceled.getAsBoolean()) {
                    throw new DeploymentCanceledException();
                }
                Set<ClientChannelEvent> events = channel.waitFor(
                        EnumSet.of(ClientChannelEvent.CLOSED), POLL_INTERVAL);
                if (events.contains(ClientChannelEvent.CLOSED)) {
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("远程命令超时：" + command);
                }
            }
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best effort channel cleanup
            }
            stdout.finish();
            stderr.finish();
        }

        Integer status = channel.getExitStatus();
        return status == null ? 0 : status;
    }

    private static final class LineCollector extends OutputStream {

        private final boolean stderr;
        private final BiConsumer<Boolean, String> output;
        private final ArrayDeque<String> tail;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private LineCollector(boolean stderr, BiConsumer<Boolean, String> output, ArrayDeque<String> tail) {
            this.stderr = stderr;
            this.output = output;
            this.tail = tail;
        }

        @Override
        public void write(int value) {
            buffer.write(value);
            if (value == '\n') {
                emit();
            }
        }

        @Override
        public void close() {
            finish();
        }

        private void finish() {
            if (buffer.size() > 0) {
                emit();
            }
        }

        private void emit() {
            byte[] raw = buffer.toByteArray();
            buffer.reset();
            int length = raw.length;
            if (length > 0 && raw[length - 1] == '\n') {
                length--;
            }
            if (length > 0 && raw[length - 1] == '\r') {
                length--;
            }
            String line = OutputSanitizer.decodeUtf8(Arrays.copyOf(raw, length));
            output.accept(stderr, line);
            tail.addLast(line);
            if (tail.size() > TAIL_LIMIT) {
                tail.removeFirst();
            }
        }
    }
}
