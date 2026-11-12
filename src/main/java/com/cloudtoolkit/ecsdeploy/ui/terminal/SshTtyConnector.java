package com.cloudtoolkit.ecsdeploy.ui.terminal;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.HostKeyPrompts;
import com.cloudtoolkit.ecsdeploy.transfer.HostKeyPolicies;
import com.cloudtoolkit.ecsdeploy.transfer.SshSessionFactory;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.session.ClientSession;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A JediTerm {@link TtyConnector} backed by an Apache MINA SSHD {@code ChannelShell}, so the
 * dashboard 终端 button gets a fully interactive remote shell (type commands, see output, resize).
 * The connector owns its {@link SshSessionFactory}/{@code ClientSession}; closing it tears the
 * SSH session (and any jump-host tunnel) down.
 */
public final class SshTtyConnector implements TtyConnector, Disposable {

    private static final Logger LOG = Logger.getInstance(SshTtyConnector.class);

    private final String title;
    private final SshSessionFactory factory;
    private final ClientSession session;
    private PtyCapableChannelSession shell;
    private InputStreamReader reader;
    private OutputStream writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SshTtyConnector(EcsConnection connection, String secret, String title,
                           String initialCommand) throws IOException {
        this.title = title;
        this.factory = new SshSessionFactory(HostKeyPolicies.create(), HostKeyPrompts.TRUST);
        try {
            this.session = factory.open(connection, secret);
        } catch (Exception e) {
            factory.close();
            throw new IOException("无法打开终端会话: " + connection.host, e);
        }
        try {
            ChannelShell channel = session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(80);
            channel.setPtyLines(24);
            channel.open().verify(SshSessionFactory.CONNECT_TIMEOUT);
            this.shell = channel;
            this.writer = channel.getInvertedIn();
            this.reader = new InputStreamReader(channel.getInvertedOut(), StandardCharsets.UTF_8);
            if (initialCommand != null && !initialCommand.isBlank()) {
                writer.write((initialCommand + "\n").getBytes(StandardCharsets.UTF_8));
                writer.flush();
            }
        } catch (IOException | RuntimeException e) {
            closeSession();
            throw new IOException("无法打开远程 shell", e);
        }
    }

    @Override
    public String getName() {
        return title;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        return reader.read(buffer, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (writer != null) {
            writer.write(bytes);
            writer.flush();
        }
    }

    @Override
    public void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return shell != null && shell.isOpen() && !closed.get();
    }

    @Override
    public boolean ready() {
        try {
            return reader.ready();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!closed.get() && (shell == null || shell.isOpen())) {
            Thread.sleep(100L);
        }
        return 0;
    }

    @Override
    public void resize(TermSize termSize) {
        if (shell == null || !shell.isOpen()) {
            return;
        }
        try {
            shell.sendWindowChange(termSize.getColumns(), termSize.getRows(), 0, 0);
        } catch (IOException | RuntimeException e) {
            LOG.debug("send window change failed", e);
        }
    }

    /** Legacy Dimension-based resize kept for older JediTerm callbacks. */
    public void resize(Dimension termSize) {
        if (termSize != null) {
            resize(new TermSize(termSize.width, termSize.height));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeSession();
    }

    @Override
    public void dispose() {
        close();
    }

    private void closeSession() {
        try {
            if (shell != null) {
                shell.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        shell = null;
        try {
            session.close();
        } catch (IOException ignored) {
            // best effort
        }
        factory.close();
    }
}
