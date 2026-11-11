package com.cloudtoolkit.ecsdeploy.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostKeyPolicyTest {

    private static final int PORT = 2222;

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
}
