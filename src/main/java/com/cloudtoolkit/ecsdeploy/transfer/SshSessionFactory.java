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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;

public final class SshSessionFactory implements AutoCloseable {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);

    private final HostKeyPolicy hostKeyPolicy;
    private final SshClient client;
    /** Jump-host sessions opened while tunneling; closed together with the factory. */
    private final java.util.List<ClientSession> openedProxies = new java.util.ArrayList<>();

    public SshSessionFactory() {
        this(new HostKeyPolicy());
    }

    public SshSessionFactory(HostKeyPolicy hostKeyPolicy) {
        this.hostKeyPolicy = Objects.requireNonNull(hostKeyPolicy);
        this.client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(hostKeyPolicy.verifier());
        client.start();
    }

    public HostKeyPolicy hostKeyPolicy() {
        return hostKeyPolicy;
    }

    public ClientSession open(EcsConnection connection, String secret)
            throws IOException, SshConnectException, SshAuthenticationException {
        return open(connection, secret, 0);
    }

    private ClientSession open(EcsConnection connection, String secret, int depth)
            throws IOException, SshConnectException, SshAuthenticationException {
        if (connection == null) {
            throw new SshAuthenticationException("连接不存在，请重新选择");
        }
        if (depth > 5) {
            throw new SshConnectException("跳板机层级过深，已停止连接");
        }

        // Open the jump-host session first if this host is configured behind a proxy.
        ClientSession proxySession = null;
        if (connection.proxyId > 0) {
            EcsConnection proxy = EcsConnectionsService.getInstance()
                    .findById(String.valueOf(connection.proxyId));
            if (proxy == null) {
                throw new SshConnectException("跳板机不存在 (id=" + connection.proxyId + ")");
            }
            String proxySecret = EcsConnectionsService.getInstance()
                    .getSecret(String.valueOf(connection.proxyId));
            proxySession = open(proxy, proxySecret, depth + 1);
        }

        ClientSession session;
        try {
            if (proxySession != null) {
                // Tunnel to the real host through the jump host's local port forward.
                SshdSocketAddress local = new SshdSocketAddress("127.0.0.1", 0);
                SshdSocketAddress remote = new SshdSocketAddress(connection.host, connection.port);
                SshdSocketAddress bound = proxySession.startLocalPortForwarding(local, remote);
                session = client.connect(connection.username, bound.getHostName(), bound.getPort())
                        .verify(CONNECT_TIMEOUT)
                        .getSession();
            } else {
                session = client.connect(connection.username, connection.host, connection.port)
                        .verify(CONNECT_TIMEOUT)
                        .getSession();
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(proxySession);
            throw new SshConnectException(
                    "无法连接 " + connection.host + ":" + connection.port
                            + (proxySession != null ? " (经跳板机)" : ""), e);
        }

        try {
            if (connection.authType == EcsConnection.AuthType.PRIVATE_KEY) {
                session.addPublicKeyIdentity(loadKeyPair(connection, secret));
            } else {
                if (secret == null || secret.isBlank()) {
                    throw new SshAuthenticationException("SSH 密码缺失，请重新输入");
                }
                session.addPasswordIdentity(secret);
            }
            AuthFuture auth = session.auth();
            auth.verify(AUTH_TIMEOUT);
            if (!session.isAuthenticated()) {
                throw new SshAuthenticationException("SSH 认证失败");
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

    private static KeyPair loadKeyPair(EcsConnection connection, String secret)
            throws SshAuthenticationException {
        if (connection.privateKeyPath == null || connection.privateKeyPath.isBlank()) {
            throw new SshAuthenticationException("未配置私钥文件路径");
        }
        Path path = PathUtils.expandHome(connection.privateKeyPath);
        if (!Files.isRegularFile(path)) {
            throw new SshAuthenticationException("私钥文件不存在：" + path);
        }
        if (connection.hasPassphrase && (secret == null || secret.isBlank())) {
            throw new SshAuthenticationException("私钥口令缺失，请重新输入");
        }

        FilePasswordProvider provider = (secret == null || secret.isBlank())
                ? FilePasswordProvider.EMPTY
                : FilePasswordProvider.of(secret);
        try (InputStream in = Files.newInputStream(path)) {
            Iterator<KeyPair> pairs = SecurityUtils
                    .loadKeyPairIdentities(null, NamedResource.ofName(path.toString()), in, provider)
                    .iterator();
            if (!pairs.hasNext()) {
                throw new SshAuthenticationException("私钥文件中没有可用密钥：" + path);
            }
            return pairs.next();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new SshAuthenticationException("无法解析私钥文件：" + path, e);
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
