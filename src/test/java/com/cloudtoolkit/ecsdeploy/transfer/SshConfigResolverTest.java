package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshConfigResolverTest {

    private static EcsConnection connection(String host, int port, String user) {
        EcsConnection connection = new EcsConnection();
        connection.host = host;
        connection.port = port;
        connection.username = user;
        connection.authType = EcsConnection.AuthType.PASSWORD;
        return connection;
    }

    @Test
    void resolvesTheAliasOfTheSshConfiguration() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    Port 2222"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, "admin"), config);

        assertEquals("10.0.0.7", target.host());
        assertEquals(2222, target.port());
        assertTrue(target.fromAlias());
        assertEquals("107", target.alias());
    }

    @Test
    void keepsTheStoredUserAndPort() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    User root",
                "    Port 2222"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, "admin"), config);

        assertEquals("admin", target.username());
        assertEquals(2222, target.port());
    }

    @Test
    void keepsAnExplicitPort() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    Port 2222"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 2200, "admin"), config);

        assertEquals(2200, target.port());
    }

    @Test
    void takesTheUserFromTheConfigurationWhenItIsMissing() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    User root"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, null), config);

        assertEquals("root", target.username());
    }

    @Test
    void keepsAConnectionWithoutAMatchingBlock() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("192.168.0.8", 22, "admin"), config);

        assertEquals("192.168.0.8", target.host());
        assertEquals(22, target.port());
        assertEquals("admin", target.username());
        assertNull(target.jump());
        assertFalse(target.fromAlias());
    }

    @Test
    void takesTheIdentityFileFromTheConfiguration(@TempDir Path dir) throws IOException {
        Path key = Files.writeString(dir.resolve("id_rsa"), "key");
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    IdentityFile " + key));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, "admin"), config);

        assertEquals(key.toString(), target.privateKeyPath());
        assertTrue(target.keyFromConfig());
    }

    @Test
    void keepsTheStoredPrivateKey(@TempDir Path dir) throws IOException {
        Path key = Files.writeString(dir.resolve("id_rsa"), "key");
        Path configured = Files.writeString(dir.resolve("configured"), "key");
        EcsConnection connection = connection("107", 22, "admin");
        connection.authType = EcsConnection.AuthType.PRIVATE_KEY;
        connection.privateKeyPath = key.toString();
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    IdentityFile " + configured));

        SshConfigResolver.Target target = SshConfigResolver.resolve(connection, config);

        assertEquals(key.toString(), target.privateKeyPath());
        assertFalse(target.keyFromConfig());
    }

    @Test
    void buildsTheJumpHostOfTheConfiguration() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host bastion",
                "    HostName 10.0.0.2",
                "    User jumper",
                "Host 107",
                "    HostName 10.0.0.7",
                "    ProxyJump bastion"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, "admin"), config);

        assertNotNull(target.jump());
        assertEquals("10.0.0.2", target.jump().host());
        assertEquals("jumper", target.jump().username());
        assertNull(target.jump().jump());
    }

    @Test
    void keepsTheCredentialsOfTheTargetForAnUnknownJumpHost() {
        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    ProxyJump bastion"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, "admin"), config);

        assertNotNull(target.jump());
        assertEquals("bastion", target.jump().host());
        assertEquals("admin", target.jump().username());
    }

    @Test
    void ignoresAProxyJumpOfNone() {        SshClientConfig config = SshClientConfig.parse(List.of(
                "Host 107",
                "    HostName 10.0.0.7",
                "    ProxyJump none"));

        SshConfigResolver.Target target =
                SshConfigResolver.resolve(connection("107", 22, "admin"), config);

        assertNull(target.jump());
    }
}
