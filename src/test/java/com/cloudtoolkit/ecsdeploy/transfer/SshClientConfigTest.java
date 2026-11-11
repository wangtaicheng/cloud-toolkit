package com.cloudtoolkit.ecsdeploy.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshClientConfigTest {

    @Test
    void resolvesTheDirectivesOfAnAlias() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    User root",
                "    Port 2222",
                "    IdentityFile ~/.ssh/id_rsa"));

        SshClientConfig.HostOptions options = config.resolve("107");

        assertNotNull(options);
        assertEquals("10.0.0.7", options.hostName());
        assertEquals("root", options.user());
        assertEquals(2222, options.port());
        assertEquals(List.of("~/.ssh/id_rsa"), options.identityFiles());
    }

    @Test
    void ignoresHostsWithoutAMatchingBlock() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7"));

        assertNull(config.resolve("108"));
    }

    @Test
    void matchesWildcardPatterns() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host *gitee.com",
                "    User wtchxx"));

        assertNotNull(config.resolve("gitee.com"));
        assertNotNull(config.resolve("www.gitee.com"));
        assertNull(config.resolve("gitee.org"));
    }

    @Test
    void excludesNegatedPatterns() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host * !secret",
                "    User root"));

        assertNotNull(config.resolve("107"));
        assertNull(config.resolve("secret"));
    }

    @Test
    void keepsTheValueOfTheFirstMatchingBlock() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    User root",
                "Host *",
                "    User other",
                "    Port 2222"));

        SshClientConfig.HostOptions options = config.resolve("107");

        assertNotNull(options);
        assertEquals("root", options.user());
        assertEquals(2222, options.port());
    }

    @Test
    void appliesDirectivesBeforeTheFirstHostToEveryHost() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "User global",
                "Host 107",
                "    HostName 10.0.0.7"));

        assertEquals("global", config.resolve("107").user());
        assertEquals("global", config.resolve("108").user());
    }

    @Test
    void skipsMatchBlocks() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "Match host 108",
                "    HostName 10.0.0.8",
                "Host 109",
                "    HostName 10.0.0.9"));

        assertEquals("10.0.0.7", config.resolve("107").hostName());
        assertNull(config.resolve("108"));
        assertEquals("10.0.0.9", config.resolve("109").hostName());
    }

    @Test
    void readsValuesSeparatedByAnEqualsSign() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName=10.0.0.7",
                "    Port=2222"));

        assertEquals("10.0.0.7", config.resolve("107").hostName());
        assertEquals(2222, config.resolve("107").port());
    }

    @Test
    void collectsEveryIdentityFile() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    IdentityFile ~/.ssh/id_rsa",
                "    IdentityFile ~/.ssh/id_ed25519"));

        assertEquals(List.of("~/.ssh/id_rsa", "~/.ssh/id_ed25519"),
                config.resolve("107").identityFiles());
    }

    @Test
    void readsTheProxyJumpHost() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    ProxyJump bastion"));

        SshClientConfig.HostOptions options = config.resolve("107");

        assertNotNull(options);
        assertEquals("bastion", options.proxyJump());
        assertFalse(options.identitiesOnly());
    }

    @Test
    void ignoresCommentsAndEmptyLines() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "# a comment",
                "",
                "Host 107",
                "    HostName 10.0.0.7"));

        assertEquals("10.0.0.7", config.resolve("107").hostName());
    }

    @Test
    void loadsAMissingFileAsAnEmptyConfiguration(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        SshClientConfig config = SshClientConfig.load(dir.resolve("config"));

        assertTrue(config.isEmpty());
        assertNull(config.resolve("107"));
    }
}
