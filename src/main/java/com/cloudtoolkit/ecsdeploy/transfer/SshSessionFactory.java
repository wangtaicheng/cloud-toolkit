package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshAuthenticationException;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.SshConnectException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.cloudtoolkit.ecsdeploy.util.PathUtils;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class SshSessionFactory implements AutoCloseable {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

    /** How many jump hosts a connection may chain through. */
    private static final int MAX_JUMP_DEPTH = 5;

    private final HostKeyPolicy hostKeyPolicy;
    private final SshClient client;
    /** The OpenSSH configuration to apply; {@code null} means {@code ~/.ssh/config}. */
    private final Path sshConfigFile;
    /** Jump-host sessions opened while tunneling; closed together with the factory. */
    private final List<ClientSession> openedProxies = new ArrayList<>();

    public SshSessionFactory() {
        this(new HostKeyPolicy());
    }

    public SshSessionFactory(HostKeyPolicy hostKeyPolicy) {
        this(hostKeyPolicy, null);
    }

    /**
     * @param sshConfigFile the OpenSSH configuration used to resolve host aliases, or
     *                       {@code null} to use {@code ~/.ssh/config}
     */
    public SshSessionFactory(HostKeyPolicy hostKeyPolicy, Path sshConfigFile) {
        this.hostKeyPolicy = Objects.requireNonNull(hostKeyPolicy);
        this.sshConfigFile = sshConfigFile;
        this.client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(hostKeyPolicy.verifier());
        client.start();
    }

    public HostKeyPolicy hostKeyPolicy() {
        return hostKeyPolicy;
    }

    public ClientSession open(EcsConnection connection, String secret)
            throws IOException, SshConnectException, SshAuthenticationException {
        return open(connection, secret, 0, true);
    }

    /**
     * Opens a session for the given connection.
     *
     * @param useConfigJump whether the {@code ProxyJump} of the OpenSSH configuration may be used;
     *                      it is skipped for a jump host that was opened because of it
     */
    private ClientSession open(EcsConnection connection, String secret, int depth, boolean useConfigJump)
            throws IOException, SshConnectException, SshAuthenticationException {
        if (connection == null) {
            throw new SshAuthenticationException("连接不存在，请重新选择");
        }
        if (depth > MAX_JUMP_DEPTH) {
            throw new SshConnectException("跳板机层级过深，已停止连接");
        }

        SshClientConfig config = SshClientConfig.load(configFile());
        SshConfigResolver.Target target = SshConfigResolver.resolve(connection, config);

        ClientSession proxySession = null;
        if (connection.proxyId > 0) {
            // A jump host configured in the plugin wins over the one of the OpenSSH configuration.
            EcsConnectionsService service = service();
            EcsConnection proxy = service == null ? null
                    : service.findById(String.valueOf(connection.proxyId));
            if (proxy == null) {
                throw new SshConnectException("跳板机不存在 (id=" + connection.proxyId + ")");
            }
            String proxySecret = service.getSecret(String.valueOf(connection.proxyId));
            proxySession = open(proxy, proxySecret, depth + 1, true);
        } else if (useConfigJump && target.jump() != null) {
            proxySession = openJump(target.jump(), secret, depth + 1);
        }
        return connect(target, secret, proxySession);
    }

    /** Opens the jump host of the OpenSSH configuration (a stored host provides its credentials). */
    private ClientSession openJump(SshConfigResolver.Target jump, String secret, int depth)
            throws IOException, SshConnectException, SshAuthenticationException {
        EcsConnectionsService service = service();
        if (service != null) {
            EcsConnection stored = service.findByAddress(jump.alias());
            if (stored != null) {
                return open(stored, service.getSecret(stored.id), depth, false);
            }
        }
        return openTarget(jump, secret, depth);
    }

    /** The local database of the stored hosts, or null when it is not available. */
    private static EcsConnectionsService service() {
        try {
            return EcsConnectionsService.getInstance();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Opens a target that was already resolved (used for the jump host chain). */
    private ClientSession openTarget(SshConfigResolver.Target target, String secret, int depth)
            throws IOException, SshConnectException, SshAuthenticationException {
        ClientSession proxySession = null;
        if (target.jump() != null) {
            proxySession = openTarget(target.jump(), secret, depth + 1);
        }
        return connect(target, secret, proxySession);
    }

    /** Connects to the target and authenticates it, optionally through a jump host. */
    private ClientSession connect(SshConfigResolver.Target target, String secret,
                                  ClientSession proxySession)
            throws IOException, SshConnectException, SshAuthenticationException {
        if (target.username() == null || target.username().isBlank()) {
            throw new SshAuthenticationException("主机 " + target.host()
                    + " 未配置 SSH 用户名（缺少 SSH 配置），请在「属性」中重新选择或创建 SSH 配置");
        }
        if (target.passphraseRequired() && (secret == null || secret.isBlank())) {
            throw new SshAuthenticationException("私钥口令缺失，请重新输入");
        }

        ClientSession session;
        try {
            if (proxySession != null) {
                // Tunnel to the real host through the jump host's local port forward.
                SshdSocketAddress local = new SshdSocketAddress("127.0.0.1", 0);
                SshdSocketAddress remote = new SshdSocketAddress(target.host(), target.port());
                SshdSocketAddress bound = proxySession.startLocalPortForwarding(local, remote);
                session = client.connect(target.username(), bound.getHostName(), bound.getPort())
                        .verify(CONNECT_TIMEOUT)
                        .getSession();
            } else {
                session = client.connect(target.username(), target.host(), target.port())
                        .verify(CONNECT_TIMEOUT)
                        .getSession();
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(proxySession);
            throw new SshConnectException(
                    "无法连接 " + target.host() + ":" + target.port()
                            + (proxySession != null ? " (经跳板机)" : "")
                            + (target.fromAlias() ? "（SSH 配置 " + target.alias() + "）" : ""), e);
        }

        try {
            boolean credential = false;
            if (target.privateKeyPath() != null) {
                // A key of the OpenSSH configuration has no passphrase in the plugin.
                String passphrase = target.keyFromConfig() ? null : secret;
                session.addPublicKeyIdentity(loadKeyPair(target.privateKeyPath(), passphrase,
                        target.keyFromConfig()));
                credential = true;
            }
            if (target.passwordAuth() && secret != null && !secret.isBlank()) {
                session.addPasswordIdentity(secret);
                credential = true;
            }
            if (!credential) {
                throw new SshAuthenticationException(target.authType() == EcsConnection.AuthType.PRIVATE_KEY
                        ? "未配置私钥文件路径"
                        : "SSH 密码缺失，请重新输入");
            }
            AuthFuture auth = session.auth();
            auth.verify(AUTH_TIMEOUT);
            if (!session.isAuthenticated()) {
                throw new SshAuthenticationException("SSH 认证失败: 用户名 "
                        + target.username() + " @ " + target.host() + ":" + target.port());
            }
            if (proxySession != null) {
                openedProxies.add(proxySession);
            }
            return session;
        } catch (SshAuthenticationException e) {
            closeQuietly(session);
            closeQuietly(proxySession);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(session);
            closeQuietly(proxySession);
            throw new SshAuthenticationException("SSH 认证失败", e);
        }
    }

    /** The OpenSSH configuration in use. */
    private Path configFile() {
        return sshConfigFile == null ? SshClientConfig.defaultFile() : sshConfigFile;
    }

    private static KeyPair loadKeyPair(String privateKeyPath, String passphrase, boolean fromConfig)
            throws SshAuthenticationException {
        Path path = PathUtils.expandHome(privateKeyPath);
        if (!Files.isRegularFile(path)) {
            throw new SshAuthenticationException("私钥文件不存在：" + path);
        }

        FilePasswordProvider provider = (passphrase == null || passphrase.isBlank())
                ? FilePasswordProvider.EMPTY
                : FilePasswordProvider.of(passphrase);
        try (InputStream in = Files.newInputStream(path)) {
            Iterator<KeyPair> pairs = SecurityUtils
                    .loadKeyPairIdentities(null, NamedResource.ofName(path.toString()), in, provider)
                    .iterator();
            if (!pairs.hasNext()) {
                throw new SshAuthenticationException("私钥文件中没有可用密钥：" + path);
            }
            return pairs.next();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new SshAuthenticationException("无法解析私钥文件：" + path
                    + (fromConfig ? "（该私钥来自 SSH 配置，若已加密请在「属性」中配置口令）" : ""), e);
        }
    }

    private static void closeQuietly(ClientSession session) {
        try {
            if (session != null) {
                session.close();
            }
        } catch (IOException ignored) {
            // best effort cleanup after a failed authentication
        }
    }

    @Override
    public void close() {
        for (ClientSession proxy : openedProxies) {
            closeQuietly(proxy);
        }
        openedProxies.clear();
        client.stop();
    }
}
