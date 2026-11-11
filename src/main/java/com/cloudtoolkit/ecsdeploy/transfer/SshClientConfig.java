package com.cloudtoolkit.ecsdeploy.transfer;

import com.cloudtoolkit.ecsdeploy.util.PathUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The OpenSSH client configuration ({@code ~/.ssh/config}).
 *
 * <p>Only the directives the plugin needs are interpreted: {@code HostName}, {@code User},
 * {@code Port}, {@code IdentityFile}, {@code ProxyJump} and {@code IdentitiesOnly}. The matching
 * follows OpenSSH: the patterns of a {@code Host} block may contain {@code *} / {@code ?}
 * wildcards and a leading {@code !} negates the match, and for every directive the value of the
 * first matching block wins. {@code Match} blocks are not supported - their directives are
 * ignored instead of failing the whole file.</p>
 */
public final class SshClientConfig {

    /** The directives that apply to one host. */
    public record HostOptions(String hostName, String user, Integer port,
                              List<String> identityFiles, String proxyJump,
                              boolean identitiesOnly) {
        public HostOptions {
            identityFiles = identityFiles == null ? List.of() : List.copyOf(identityFiles);
        }
    }

    private static final String KEY_HOST = "host";
    private static final String KEY_MATCH = "match";
    private static final String KEY_HOST_NAME = "hostname";
    private static final String KEY_USER = "user";
    private static final String KEY_PORT = "port";
    private static final String KEY_IDENTITY_FILE = "identityfile";
    private static final String KEY_PROXY_JUMP = "proxyjump";
    private static final String KEY_IDENTITIES_ONLY = "identitiesonly";

    private final List<Block> blocks;

    private SshClientConfig(List<Block> blocks) {
        this.blocks = blocks;
    }

    /** The default location of the OpenSSH client configuration. */
    public static Path defaultFile() {
        return PathUtils.expandHome("~/.ssh/config");
    }

    /** Reads the given configuration file; a missing file yields an empty configuration. */
    public static SshClientConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new SshClientConfig(List.of());
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 SSH 配置: " + file, e);
        }
    }

    /** Parses the given configuration lines. */
    public static SshClientConfig parse(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        boolean insideMatch = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = separatorIndex(line);
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = unquote(valueOf(line, separator));
            if (KEY_HOST.equals(key)) {
                current = new Block(splitPatterns(value));
                blocks.add(current);
                insideMatch = false;
                continue;
            }
            if (KEY_MATCH.equals(key)) {
                // Match blocks are not supported: their directives are skipped.
                current = null;
                insideMatch = true;
                continue;
            }
            if (current == null) {
                if (insideMatch) {
                    continue;
                }
                // Directives before the first Host block apply to every host.
                current = new Block(List.of("*"));
                blocks.add(current);
            }
            current.apply(key, value);
        }
        return new SshClientConfig(blocks);
    }

    /** The directives that apply to the given host, or {@code null} when no block matches. */
    public HostOptions resolve(String host) {
        if (host == null || host.isBlank() || blocks.isEmpty()) {
            return null;
        }
        String hostName = null;
        String user = null;
        Integer port = null;
        String proxyJump = null;
        boolean identitiesOnly = false;
        List<String> identityFiles = new ArrayList<>();
        boolean matched = false;
        for (Block block : blocks) {
            if (!matches(host, block.patterns)) {
                continue;
            }
            matched = true;
            if (hostName == null) {
                hostName = block.hostName;
            }
            if (user == null) {
                user = block.user;
            }
            if (port == null) {
                port = block.port;
            }
            if (proxyJump == null) {
                proxyJump = block.proxyJump;
            }
            identityFiles.addAll(block.identityFiles);
            identitiesOnly = identitiesOnly || block.identitiesOnly;
        }
        if (!matched) {
            return null;
        }
        return new HostOptions(hostName, user, port, identityFiles, proxyJump, identitiesOnly);
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** The index of the directive separator, or -1 when the line carries no value. */
    private static int separatorIndex(String line) {
        int equals = line.indexOf('=');
        int space = -1;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                space = i;
                break;
            }
        }
        if (equals >= 0 && (space < 0 || equals < space)) {
            return equals;
        }
        return space;
    }

    /** The value part of a directive line. */
    private static String valueOf(String line, int separator) {
        String value = line.substring(separator).trim();
        if (value.startsWith("=")) {
            value = value.substring(1).trim();
        }
        return value;
    }

    /** Splits a {@code Host} value into its patterns. */
    private static List<String> splitPatterns(String value) {
        List<String> patterns = new ArrayList<>();
        for (String part : value.split("[\\s,]+")) {
            if (!part.isEmpty()) {
                patterns.add(part);
            }
        }
        return patterns;
    }

    /** Removes the surrounding quotes of a value. */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Whether the host matches the patterns of a {@code Host} block. */
    private static boolean matches(String host, List<String> patterns) {
        boolean matched = false;
        for (String pattern : patterns) {
            boolean negated = pattern.startsWith("!");
            String value = negated ? pattern.substring(1) : pattern;
            if (globMatches(host, value)) {
                if (negated) {
                    return false;
                }
                matched = true;
            }
        }
        return matched;
    }

    /** Matches a host against a pattern that may contain {@code *} and {@code ?}. */
    private static boolean globMatches(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(value).matches();
    }

    /** One {@code Host} block of the configuration. */
    private static final class Block {

        private final List<String> patterns;
        private String hostName;
        private String user;
        private Integer port;
        private final List<String> identityFiles = new ArrayList<>();
        private String proxyJump;
        private boolean identitiesOnly;

        private Block(List<String> patterns) {
            this.patterns = patterns;
        }

        private void apply(String key, String value) {
            switch (key) {
                case KEY_HOST_NAME -> hostName = value;
                case KEY_USER -> user = value;
                case KEY_PORT -> {
                    try {
                        port = Integer.valueOf(value);
                    } catch (NumberFormatException ignored) {
                        // an invalid port is ignored, the caller keeps its own value
                    }
                }
                case KEY_IDENTITY_FILE -> identityFiles.add(value);
                case KEY_PROXY_JUMP -> proxyJump = value;
                case KEY_IDENTITIES_ONLY -> identitiesOnly =
                        "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
                default -> {
                    // any other directive is not used by the plugin
                }
            }
        }
    }
}
