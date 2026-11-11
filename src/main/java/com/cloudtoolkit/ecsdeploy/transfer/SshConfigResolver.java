package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies the user's OpenSSH configuration ({@code ~/.ssh/config}) to a stored connection.
 *
 * <p>The plugin stores an address, a port and a profile (user + credential). When the address is
 * an alias the OpenSSH configuration knows (e.g. {@code Host 107}), the configuration decides how
 * the host is reached - just like {@code ssh 107} would:</p>
 * <ul>
 *   <li>{@code HostName} replaces the address: the alias is resolved to the real host.</li>
 *   <li>{@code Port} is used while the plugin still carries the default port 22.</li>
 *   <li>{@code User} and {@code IdentityFile} fill in what the plugin does not provide.</li>
 *   <li>{@code ProxyJump} adds the jump host the connection has to be tunnelled through.</li>
 * </ul>
 *
 * <p>Values the plugin stores explicitly (a user, a private key, a port other than 22) always
 * win, so a stored connection is never silently re-pointed at another host.</p>
 */
public final class SshConfigResolver {

    /** The default SSH port. */
    public static final int DEFAULT_PORT = 22;

    /** How many jump hosts a connection may chain through. */
    private static final int MAX_JUMP_DEPTH = 5;

    /** The effective target of one SSH connection. */
    public record Target(String host, int port, String username, String privateKeyPath,
                         EcsConnection.AuthType authType, boolean keyFromConfig,
                         boolean passwordAuth, boolean passphraseRequired,
                         Target jump, String alias) {

        /** Whether this target came from an alias of the OpenSSH configuration. */
        public boolean fromAlias() {
            return alias != null && !alias.equals(host);
        }
    }

    private SshConfigResolver() {
    }

    /** Resolves the connection against the user's OpenSSH configuration. */
    public static Target resolve(EcsConnection connection) {
        return resolve(connection, SshClientConfig.load(SshClientConfig.defaultFile()));
    }

    /** Resolves the connection against the given OpenSSH configuration. */
    public static Target resolve(EcsConnection connection, SshClientConfig config) {
        if (connection == null) {
            throw new IllegalArgumentException("connection is required");
        }
        String alias = connection.host == null ? "" : connection.host.trim();
        String host = alias;
        int port = connection.port > 0 ? connection.port : DEFAULT_PORT;
        String username = trimToNull(connection.username);
        EcsConnection.AuthType authType = connection.authType == null
                ? EcsConnection.AuthType.PASSWORD : connection.authType;
        String privateKeyPath = authType == EcsConnection.AuthType.PRIVATE_KEY
                ? trimToNull(connection.privateKeyPath) : null;
        boolean keyFromConfig = false;

        SshClientConfig.HostOptions options = config == null ? null : config.resolve(alias);
        if (options != null) {
            if (isNotBlank(options.hostName())) {
                host = options.hostName().trim();
            }
            if (options.port() != null && port == DEFAULT_PORT) {
                port = options.port();
            }
            if (username == null && isNotBlank(options.user())) {
                username = options.user().trim();
            }
            if (privateKeyPath == null) {
                String identity = firstExisting(options.identityFiles());
                if (identity != null) {
                    privateKeyPath = identity;
                    keyFromConfig = true;
                }
            }
        }

        Target self = new Target(host, port, username, privateKeyPath, authType, keyFromConfig,
                authType == EcsConnection.AuthType.PASSWORD, connection.hasPassphrase, null, alias);
        Target jump = null;
        if (options != null && isUsableProxyJump(options.proxyJump())) {
            jump = resolveJump(options.proxyJump().trim(), config, 1, self);
        }
        return new Target(self.host(), self.port(), self.username(), self.privateKeyPath(),
                self.authType(), self.keyFromConfig(), self.passwordAuth(),
                self.passphraseRequired(), jump, self.alias());
    }

    /** Whether the OpenSSH configuration provides the user for the given host. */
    public static boolean providesUser(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        SshClientConfig.HostOptions options =
                SshClientConfig.load(SshClientConfig.defaultFile()).resolve(host);
        return options != null && isNotBlank(options.user());
    }

    /** A short summary of the configuration entry of the given host, or null when there is none. */
    public static String describe(String host) {
        return describe(SshClientConfig.load(SshClientConfig.defaultFile()), host);
    }

    /** A short summary of the configuration entry of the given host, or null when there is none. */
    public static String describe(SshClientConfig config, String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        SshClientConfig.HostOptions options = config == null ? null : config.resolve(host);
        if (options == null) {
            return null;
        }
        StringBuilder summary = new StringBuilder();
        summary.append(isNotBlank(options.hostName()) ? options.hostName().trim() : host);
        if (options.port() != null) {
            summary.append(':').append(options.port());
        }
        if (isNotBlank(options.user())) {
            summary.append(" (user=").append(options.user().trim()).append(')');
        }
        String identity = firstExisting(options.identityFiles());
        if (identity != null) {
            summary.append(" (key=").append(identity).append(')');
        }
        if (isUsableProxyJump(options.proxyJump())) {
            summary.append(" (proxyJump=").append(options.proxyJump().trim()).append(')');
        }
        return summary.toString();
    }

    /** Resolves the jump host chain of the OpenSSH configuration. */
    private static Target resolveJump(String alias, SshClientConfig config, int depth, Target parent) {
        if (config == null || depth > MAX_JUMP_DEPTH) {
            return null;
        }
        SshClientConfig.HostOptions options = config.resolve(alias);
        String host = alias;
        int port = DEFAULT_PORT;
        String username = parent == null ? null : parent.username();
        String privateKeyPath = null;
        boolean keyFromConfig = false;
        Target jump = null;
        if (options != null) {
            if (isNotBlank(options.hostName())) {
                host = options.hostName().trim();
            }
            if (options.port() != null) {
                port = options.port();
            }
            if (isNotBlank(options.user())) {
                username = options.user().trim();
            }
            String identity = firstExisting(options.identityFiles());
            if (identity != null) {
                privateKeyPath = identity;
                keyFromConfig = true;
            }
        }
        if (privateKeyPath == null && parent != null) {
            // The jump host has no key of its own - the one of the target is used.
            privateKeyPath = parent.privateKeyPath();
            keyFromConfig = parent.keyFromConfig();
        }
        boolean keyAuth = privateKeyPath != null;
        Target self = new Target(host, port, username, privateKeyPath,
                keyAuth ? EcsConnection.AuthType.PRIVATE_KEY : EcsConnection.AuthType.PASSWORD,
                keyFromConfig, parent != null && parent.passwordAuth() && !keyAuth,
                parent != null && parent.passphraseRequired(), null, alias);
        if (options != null && isUsableProxyJump(options.proxyJump())) {
            jump = resolveJump(options.proxyJump().trim(), config, depth + 1, self);
        }
        return new Target(self.host(), self.port(), self.username(), self.privateKeyPath(),
                self.authType(), self.keyFromConfig(), self.passwordAuth(),
                self.passphraseRequired(), jump, self.alias());
    }

    /** The first identity file of the configuration that exists on disk. */
    private static String firstExisting(List<String> identityFiles) {
        if (identityFiles == null) {
            return null;
        }
        for (String identity : identityFiles) {
            if (!isNotBlank(identity)) {
                continue;
            }
            Path path = com.cloudtoolkit.ecsdeploy.util.PathUtils.expandHome(identity.trim());
            if (Files.isRegularFile(path)) {
                return path.toString();
            }
        }
        return null;
    }

    /** Whether the ProxyJump directive points at a usable jump host. */
    private static boolean isUsableProxyJump(String proxyJump) {
        return isNotBlank(proxyJump) && !"none".equalsIgnoreCase(proxyJump.trim());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
