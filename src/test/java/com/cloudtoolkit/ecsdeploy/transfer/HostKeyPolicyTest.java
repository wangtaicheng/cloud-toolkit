package com.cloudtoolkit.ecsdeploy.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostKeyPolicyTest {

    private static final int PORT = 2222;
    private static final int DEFAULT_PORT = 22;

    @TempDir
    Path dir;

    private Path knownHosts;
    private Path emptyHosts;
    private KeyPair keyPair;
    private KeyPair otherKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        knownHosts = dir.resolve("known_hosts");
        emptyHosts = dir.resolve("empty_hosts");
        keyPair = generateKeyPair();
        otherKeyPair = generateKeyPair();
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    @Test
    void acceptsKnownHostKey() {
        HostKeyPolicy.write(knownHosts, "127.0.0.1", PORT, keyPair.getPublic());

        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                HostKeyPolicy.check(knownHosts, "127.0.0.1", PORT, keyPair.getPublic()));
    }

    @Test
    void flagsUnknownHostKey() {
        assertEquals(HostKeyPolicy.Decision.UNKNOWN,
                HostKeyPolicy.check(emptyHosts, "127.0.0.1", PORT, keyPair.getPublic()));
    }

    @Test
    void rejectsChangedHostKey() {
        HostKeyPolicy.write(knownHosts, "127.0.0.1", PORT, keyPair.getPublic());

        assertEquals(HostKeyPolicy.Decision.CHANGED,
                HostKeyPolicy.check(knownHosts, "127.0.0.1", PORT, otherKeyPair.getPublic()));
    }

    @Test
    void skipVerificationNeverBypassesChangedKey() {
        HostKeyPolicy.write(knownHosts, "127.0.0.1", PORT, keyPair.getPublic());

        assertEquals(HostKeyPolicy.Decision.CHANGED,
                new HostKeyPolicy(true).evaluate(knownHosts, "127.0.0.1", PORT, otherKeyPair.getPublic()));
        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                new HostKeyPolicy(true).evaluate(emptyHosts, "127.0.0.1", PORT, keyPair.getPublic()));
    }

    @Test
    void writesTheDefaultPortAsAPlainName() throws Exception {
        HostKeyPolicy.write(knownHosts, "ecs.example.com", DEFAULT_PORT, keyPair.getPublic());

        assertTrue(Files.readString(knownHosts).startsWith("ecs.example.com ssh-rsa "));
    }

    @Test
    void writesOtherPortsWithBrackets() throws Exception {
        HostKeyPolicy.write(knownHosts, "ecs.example.com", PORT, keyPair.getPublic());

        assertTrue(Files.readString(knownHosts).startsWith("[ecs.example.com]:" + PORT + " ssh-rsa "));
    }

    @Test
    void acceptsTheEntryOfTheSshClient() throws Exception {
        // The ssh client writes the host of the default port as a plain name, with the address added.
        Files.writeString(knownHosts, "ecs.example.com,10.0.0.1 ssh-rsa " + encode(keyPair.getPublic())
                + System.lineSeparator());

        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                HostKeyPolicy.check(knownHosts, "ecs.example.com", DEFAULT_PORT, keyPair.getPublic()));
        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                HostKeyPolicy.check(knownHosts, "10.0.0.1", DEFAULT_PORT, keyPair.getPublic()));
    }

    @Test
    void findsTheHostByItsAddress() {
        HostKeyPolicy.write(knownHosts, List.of("ecs.example.com", "10.0.0.1"), DEFAULT_PORT,
                keyPair.getPublic());

        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                HostKeyPolicy.check(knownHosts, "10.0.0.1", DEFAULT_PORT, keyPair.getPublic()));
    }

    @Test
    void keepsAnExistingEntry() throws Exception {
        HostKeyPolicy.write(knownHosts, "127.0.0.1", PORT, keyPair.getPublic());
        String content = Files.readString(knownHosts);

        HostKeyPolicy.write(knownHosts, "127.0.0.1", PORT, otherKeyPair.getPublic());

        assertEquals(content, Files.readString(knownHosts));
    }

    @Test
    void replaceRemovesTheConflictingEntry() throws Exception {
        HostKeyPolicy.write(knownHosts, "127.0.0.1", PORT, keyPair.getPublic());

        HostKeyPolicy.replace(knownHosts, "127.0.0.1", PORT, otherKeyPair.getPublic());

        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                HostKeyPolicy.check(knownHosts, "127.0.0.1", PORT, otherKeyPair.getPublic()));
        assertEquals(HostKeyPolicy.Decision.CHANGED,
                HostKeyPolicy.check(knownHosts, "127.0.0.1", PORT, keyPair.getPublic()));
    }

    @Test
    void replaceKeepsUnrelatedEntries() throws Exception {
        HostKeyPolicy.write(knownHosts, "10.0.0.9", PORT, keyPair.getPublic());

        HostKeyPolicy.replace(knownHosts, "127.0.0.1", PORT, otherKeyPair.getPublic());

        assertEquals(HostKeyPolicy.Decision.ACCEPT,
                HostKeyPolicy.check(knownHosts, "10.0.0.9", PORT, keyPair.getPublic()));
        assertTrue(Files.readString(knownHosts).contains("10.0.0.9"));
    }

    private static String encode(java.security.PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
