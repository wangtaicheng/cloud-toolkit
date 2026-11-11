package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.util.PathUtils;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

public final class HostKeyPolicy {

    public enum Decision { ACCEPT, UNKNOWN, CHANGED }

    private final boolean skipVerification;
    private volatile PublicKey unknownServerKey;

    public HostKeyPolicy() {
        this(false);
    }

    public HostKeyPolicy(boolean skipVerification) {
        this.skipVerification = skipVerification;
    }

    public boolean skipVerification() {
        return skipVerification;
    }

    public Decision evaluate(Path knownHosts, String host, int port, PublicKey key) {
        Decision decision = check(knownHosts, host, port, key);
        if (decision == Decision.CHANGED) {
            return Decision.CHANGED;
        }
        if (decision == Decision.UNKNOWN && skipVerification) {
            return Decision.ACCEPT;
        }
        return decision;
    }

    public static Decision check(Path knownHosts, String host, int port, PublicKey key) {
        String marker = marker(host, port);
        String expected = encode(key);
        Decision result = Decision.UNKNOWN;
        for (String line : readLines(knownHosts)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[0].equals(marker)) {
                if (parts[2].equals(expected)) {
                    return Decision.ACCEPT;
                }
                result = Decision.CHANGED;
            }
        }
        return result;
    }

    public static void write(Path knownHosts, String host, int port, PublicKey key) {
        if (check(knownHosts, host, port, key) != Decision.UNKNOWN) {
            return;
        }
        String line = marker(host, port) + " " + key.getAlgorithm() + " " + encode(key) + System.lineSeparator();
        try {
            Path parent = knownHosts.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(knownHosts, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot update known hosts", e);
        }
    }

    public static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    public static Path defaultKnownHosts() {
        return PathUtils.expandHome("~/.ssh/known_hosts");
    }

    public PublicKey unknownServerKey() {
        return unknownServerKey;
    }

    public void clearUnknownServerKey() {
        unknownServerKey = null;
    }

    public ServerKeyVerifier verifier() {
        return (ClientSession session, SocketAddress address, PublicKey key) -> {
            if (!(address instanceof InetSocketAddress socket)) {
                return false;
            }
            String host = socket.getAddress() != null
                    ? socket.getAddress().getHostAddress()
                    : socket.getHostString();
            Decision decision = evaluate(defaultKnownHosts(), host, socket.getPort(), key);
            if (decision == Decision.UNKNOWN) {
                unknownServerKey = key;
            }
            return decision == Decision.ACCEPT;
        };
    }

    private static String marker(String host, int port) {
        return "[" + host + "]:" + port;
    }

    private static String encode(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static List<String> readLines(Path knownHosts) {
        if (knownHosts == null || !Files.isRegularFile(knownHosts)) {
            return List.of();
        }
        try {
            return Files.readAllLines(knownHosts, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read known hosts", e);
        }
    }
}
