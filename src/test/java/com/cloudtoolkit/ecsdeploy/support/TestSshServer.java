package com.cloudtoolkit.ecsdeploy.support;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestSshServer {

    private static final String READ_ONLY_DIRECTORY = "readonly";
    private static final String TEST_PASSWORD = "pw";

    private final SshServer server;
    private final Path root;
    private final Path readOnly;
    private final AtomicInteger commandCount = new AtomicInteger();
    private volatile String lastCommand;
    private int port;

    private TestSshServer(SshServer server, Path root, Path readOnly) {
        this.server = server;
        this.root = root;
        this.readOnly = readOnly;
    }

    public static TestSshServer start() throws Exception {
        Path root = Files.createTempDirectory("ecs-ssh-");
        Path readOnly = Files.createDirectories(root.resolve(READ_ONLY_DIRECTORY));

        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(root.resolve("host-key.ser")));
        server.setPasswordAuthenticator((user, password, session) ->
                "test".equals(user) && TEST_PASSWORD.equals(password));
        server.setPublickeyAuthenticator((user, key, session) -> true);

        VirtualFileSystemFactory fileSystem = new VirtualFileSystemFactory(root);
        fileSystem.setDefaultHomeDir(root);
        server.setFileSystemFactory(fileSystem);

        SftpSubsystemFactory sftp = new SftpSubsystemFactory.Builder()
                .withFileSystemAccessor(new ReadOnlyGuard())
                .build();
        server.setSubsystemFactories(List.of(sftp));

        TestSshServer fixture = new TestSshServer(server, root, readOnly);
        server.setCommandFactory((channel, command) -> fixture.createCommand(command));
        server.start();

        for (SocketAddress address : server.getBoundAddresses()) {
            if (address instanceof InetSocketAddress inet) {
                fixture.port = inet.getPort();
                break;
            }
        }
        if (fixture.port <= 0) {
            fixture.stop();
            throw new IllegalStateException("SSH server did not bind to a TCP port");
        }
        return fixture;
    }

    private Command createCommand(String command) {
        lastCommand = command;
        commandCount.incrementAndGet();
        return new InMemoryCommand(command);
    }

    public int port() {
        return port;
    }

    public Path root() {
        return root;
    }

    public String readOnlyDirectory() {
        return "/" + READ_ONLY_DIRECTORY;
    }

    public String lastCommand() {
        return lastCommand;
    }

    public int commandCount() {
        return commandCount.get();
    }

    public void stop() {
        try {
            server.stop(true);
        } catch (IOException ignored) {
            // fixture teardown is best effort
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // temporary fixture directory can be left behind on Windows
        }
    }

    public static Path encryptedPrivateKey(Path dir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        PBEParameterSpec parameters = new PBEParameterSpec(salt, 4096);
        PBEKeySpec keySpec = new PBEKeySpec("phrase".toCharArray());
        SecretKey secretKey = SecretKeyFactory
                .getInstance("PBEWithHMACSHA256AndAES_256")
                .generateSecret(keySpec);
        Cipher cipher = Cipher.getInstance("PBEWithHMACSHA256AndAES_256");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameters);
        byte[] encrypted = cipher.doFinal(pair.getPrivate().getEncoded());
        byte[] algorithmIdentifier = cipher.getParameters().getEncoded();
        byte[] der = derTagged((byte) 0x30, algorithmIdentifier, derTagged((byte) 0x04, encrypted));

        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        Path file = dir.resolve("encrypted-key.pem");
        Files.writeString(file, "-----BEGIN ENCRYPTED PRIVATE KEY-----\n" + body
                + "\n-----END ENCRYPTED PRIVATE KEY-----\n", StandardCharsets.UTF_8);
        return file;
    }

    private static byte[] derTagged(byte tag, byte[]... content) {
        int length = 0;
        for (byte[] part : content) {
            length += part.length;
        }
        byte[] encodedLength = derLength(length);
        byte[] result = new byte[1 + encodedLength.length + length];
        result[0] = tag;
        System.arraycopy(encodedLength, 0, result, 1, encodedLength.length);
        int offset = 1 + encodedLength.length;
        for (byte[] part : content) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        }
        int bytes = 0;
        for (int remaining = length; remaining > 0; remaining >>>= 8) {
            bytes++;
        }
        byte[] result = new byte[bytes + 1];
        result[0] = (byte) (0x80 | bytes);
        for (int i = bytes; i > 0; i--) {
            result[i] = (byte) (length & 0xff);
            length >>>= 8;
        }
        return result;
    }

    private static final class InMemoryCommand implements Command {

        private final String command;
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;

        private InMemoryCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment environment) throws IOException {
            int exitCode = 0;
            for (String raw : command.split(";")) {
                if (destroyed.getCount() == 0) {
                    return;
                }
                String step = raw.trim();
                if (step.isEmpty()) {
                    continue;
                }
                String[] parts = step.split("\\s+", 2);
                String verb = parts[0];
                String argument = parts.length > 1 ? parts[1].trim() : "";
                switch (verb) {
                    case "echo" -> write(out, unquote(argument));
                    case "echo-stderr" -> write(err, unquote(argument));
                    case "seq" -> {
                        String[] bounds = argument.split("\\s+");
                        long from = Long.parseLong(bounds[0]);
                        long to = Long.parseLong(bounds[1]);
                        StringBuilder text = new StringBuilder();
                        for (long i = from; i <= to; i++) {
                            text.append(i).append('\n');
                        }
                        write(out, text.toString());
                    }
                    case "long-line" -> write(out, "x".repeat(Integer.parseInt(argument)) + "\n");
                    case "sleep" -> {
                        try {
                            destroyed.await(Long.parseLong(argument), TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    case "exit" -> {
                        exitCode = Integer.parseInt(argument);
                    }
                    default -> write(out, step);
                }
                if ("exit".equals(verb)) {
                    break;
                }
            }
            if (destroyed.getCount() > 0 && callback != null) {
                callback.onExit(exitCode);
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            destroyed.countDown();
        }

        private static void write(OutputStream stream, String text) throws IOException {
            if (stream == null) {
                return;
            }
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }

        private static String unquote(String text) {
            if (text.length() >= 2
                    && ((text.startsWith("'") && text.endsWith("'"))
                    || (text.startsWith("\"") && text.endsWith("\"")))) {
                return text.substring(1, text.length() - 1);
            }
            return text;
        }
    }

    private static final class ReadOnlyGuard implements SftpFileSystemAccessor {

        private static final String READ_ONLY_PREFIX = "/readonly";

        @Override
        public SeekableByteChannel openFile(SftpSubsystemProxy proxy, FileHandle handle, Path file,
                                           String mode, Set<? extends OpenOption> options,
                                           FileAttribute<?>... attrs) throws IOException {
            if (isUnderReadOnly(file) && isWrite(options, mode)) {
                throw new AccessDeniedException(file.toString());
            }
            return SftpFileSystemAccessor.DEFAULT.openFile(proxy, handle, file, mode, options, attrs);
        }

        @Override
        public void createDirectory(SftpSubsystemProxy proxy, Path path) throws IOException {
            if (isUnderReadOnly(path) && !isReadOnlyRoot(path)) {
                throw new AccessDeniedException(path.toString());
            }
            SftpFileSystemAccessor.DEFAULT.createDirectory(proxy, path);
        }

        @Override
        public void renameFile(SftpSubsystemProxy proxy, Path source, Path target,
                              Collection<CopyOption> options) throws IOException {
            if (isUnderReadOnly(target)) {
                throw new AccessDeniedException(target.toString());
            }
            SftpFileSystemAccessor.DEFAULT.renameFile(proxy, source, target, options);
        }

        private static boolean isUnderReadOnly(Path path) {
            return normalize(path).contains(READ_ONLY_PREFIX);
        }

        private static boolean isReadOnlyRoot(Path path) {
            return normalize(path).equals(READ_ONLY_PREFIX)
                    || normalize(path).endsWith(READ_ONLY_PREFIX);
        }

        private static String normalize(Path path) {
            return path.toString().replace('\\', '/');
        }

        private static boolean isWrite(Set<? extends OpenOption> options, String mode) {
            return (mode != null && (mode.contains("w") || mode.contains("a")))
                    || options.contains(StandardOpenOption.WRITE)
                    || options.contains(StandardOpenOption.APPEND)
                    || options.contains(StandardOpenOption.CREATE);
        }
    }
}
