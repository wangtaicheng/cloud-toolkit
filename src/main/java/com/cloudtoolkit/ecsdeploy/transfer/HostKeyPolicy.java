package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.util.PathUtils;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether the key of a server may be trusted.
 *
 * <p>The known hosts file is read and written the way OpenSSH does it: the host of the default
 * port is stored as a plain name (a comma separated list of names is allowed), a host of another
 * port as {@code [host]:port}. That way the file can be shared with the {@code ssh} client and a
 * host the user already connected to with the command line is trusted as well.</p>
 *
 * <p>A host is looked up by the name the connection was made with <em>and</em> by the address it
 * resolved to, so an entry written by OpenSSH ({@code host,address}) matches either of them.</p>
 */
public final class HostKeyPolicy {

    public enum Decision { ACCEPT, UNKNOWN, CHANGED }

    /** The port OpenSSH stores without brackets in the known hosts file. */
    private static final int DEFAULT_SSH_PORT = 22;

    private final boolean skipVerification;
    private final Path knownHosts;

    /** The key of the last verification that did not know the host. */
    private volatile PublicKey unknownServerKey;
    /** The name the unknown key was seen for. */
    private volatile String unknownServerHost;
    /** The address the unknown key was seen for. */
    private volatile String unknownServerAddress;
    /** The port the unknown key was seen for. */
    private volatile int unknownServerPort;
    /** The result of the last verification. */
    private volatile Decision lastDecision;

    public HostKeyPolicy() {
        this(false);
    }

    public HostKeyPolicy(boolean skipVerification) {
        this(skipVerification, defaultKnownHosts());
    }

    public HostKeyPolicy(boolean skipVerification, Path knownHosts) {
        this.skipVerification = skipVerification;
        this.knownHosts = knownHosts == null ? defaultKnownHosts() : knownHosts;
    }

    public boolean skipVerification() {
        return skipVerification;
    }

    /** The known hosts file this policy reads and writes. */
    public Path knownHosts() {
        return knownHosts;
    }

    /**
     * The decision for a key: {@link Decision#ACCEPT} when the key is known, {@link Decision#UNKNOWN}
     * when the host is not in the known hosts file and {@link Decision#CHANGED} when the host is
     * known with another key (a changed key is never accepted).
     */
    public Decision evaluate(Path knownHosts, String host, int port, PublicKey key) {
        return evaluate(knownHosts, List.of(host), port, key);
    }

    /** The decision for a key of a host that is known under several names. */
    public Decision evaluate(Path knownHosts, Collection<String> hosts, int port, PublicKey key) {
        Decision decision = check(knownHosts, hosts, port, key);
        if (decision == Decision.CHANGED) {
            return Decision.CHANGED;
        }
        if (decision == Decision.UNKNOWN && skipVerification) {
            return Decision.ACCEPT;
        }
        return decision;
    }

    public static Decision check(Path knownHosts, String host, int port, PublicKey key) {
        return check(knownHosts, List.of(host), port, key);
    }

    /** The decision for a key of a host that is known under several names. */
    public static Decision check(Path knownHosts, Collection<String> hosts, int port, PublicKey key) {
        String expected = encode(key);
        Set<String> markers = markers(hosts, port);
        Decision result = Decision.UNKNOWN;
        boolean changed = false;
        for (String line : readLines(knownHosts)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !matches(parts[0], markers)) {
                continue;
            }
            if (expected.equals(parts[2])) {
                result = Decision.ACCEPT;
            } else {
                changed = true;
            }
        }
        // A host that is known with another key is a changed key, even when another entry matches.
        return changed ? Decision.CHANGED : result;
    }

    public static void write(Path knownHosts, String host, int port, PublicKey key) {
        write(knownHosts, List.of(host), port, key);
    }

    /** Adds a key to the known hosts file (an existing entry is never overwritten). */
    public static void write(Path knownHosts, Collection<String> hosts, int port, PublicKey key) {
        if (check(knownHosts, hosts, port, key) != Decision.UNKNOWN) {
            return;
        }
        String line = hostToken(hosts, port) + " " + keyType(key) + " " + encode(key)
                + System.lineSeparator();
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

    /** The name the unknown key was seen for. */
    public String unknownServerHost() {
        return unknownServerHost;
    }

    /** The address the unknown key was seen for. */
    public String unknownServerAddress() {
        return unknownServerAddress;
    }

    /** The port the unknown key was seen for. */
    public int unknownServerPort() {
        return unknownServerPort;
    }

    /** The result of the last verification. */
    public Decision lastDecision() {
        return lastDecision;
    }

    public void clearUnknownServerKey() {
        unknownServerKey = null;
    }

    /** Forgets the result of the last verification (before a new attempt is made). */
    public void reset() {
        unknownServerKey = null;
        unknownServerHost = null;
        unknownServerAddress = null;
        unknownServerPort = 0;
        lastDecision = null;
    }

    public ServerKeyVerifier verifier() {
        return (ClientSession session, SocketAddress address, PublicKey key) -> {
            if (!(address instanceof InetSocketAddress socket)) {
                return false;
            }
            List<String> names = names(socket);
            Decision decision = evaluate(knownHosts, names, socket.getPort(), key);
            lastDecision = decision;
            if (decision == Decision.UNKNOWN) {
                unknownServerKey = key;
                unknownServerHost = socket.getHostString();
                unknownServerAddress = addressOf(socket);
                unknownServerPort = socket.getPort();
            }
            return decision == Decision.ACCEPT;
        };
    }

    /** The names a host is known under: the name of the connection and the address it resolved to. */
    private static List<String> names(InetSocketAddress socket) {
        Set<String> names = new LinkedHashSet<>();
        String host = socket.getHostString();
        if (host != null && !host.isBlank()) {
            names.add(host);
        }
        String address = addressOf(socket);
        if (address != null) {
            names.add(address);
        }
        if (names.isEmpty()) {
            names.add("unknown");
        }
        return List.copyOf(names);
    }

    private static String addressOf(InetSocketAddress socket) {
        InetAddress address = socket.getAddress();
        return address == null ? null : address.getHostAddress();
    }

    /** The markers a host is looked up under. */
    private static Set<String> markers(Collection<String> hosts, int port) {
        Set<String> markers = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                continue;
            }
            markers.add(marker(host, port));
        }
        if (markers.isEmpty()) {
            markers.add(marker("unknown", port));
        }
        return markers;
    }

    /** The name of a host in the known hosts file. */
    public static String marker(String host, int port) {
        return port == DEFAULT_SSH_PORT ? host : "[" + host + "]:" + port;
    }

    /** The host part of a known hosts line. */
    private static String hostToken(Collection<String> hosts, int port) {
        List<String> tokens = new ArrayList<>();
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                continue;
            }
            tokens.add(marker(host, port));
        }
        if (tokens.isEmpty()) {
            tokens.add(marker("unknown", port));
        }
        return String.join(",", tokens);
    }

    /** Whether the host part of a known hosts line describes one of the given markers. */
    private static boolean matches(String token, Set<String> markers) {
        for (String part : token.split(",")) {
            if (markers.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** The key type in the notation of OpenSSH. */
    private static String keyType(PublicKey key) {
        return switch (key.getAlgorithm()) {
            case "RSA" -> "ssh-rsa";
            case "DSA" -> "ssh-dss";
            case "EC" -> "ecdsa-sha2-" + curve(key);
            case "Ed25519", "EdDSA", "EdEC" -> "ssh-ed25519";
            default -> key.getAlgorithm();
        };
    }

    private static String curve(PublicKey key) {
        if (key instanceof ECPublicKey ec) {
            int size = ec.getParams().getCurve().getField().getFieldSize();
            return switch (size) {
                case 256 -> "nistp256";
                case 384 -> "nistp384";
                case 521 -> "nistp521";
                default -> "nistp" + size;
            };
        }
        return "nistp256";
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
