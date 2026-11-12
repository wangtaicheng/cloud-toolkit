package com.cloudtoolkit.ecsdeploy.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Read/write access to the Cloud Toolkit local database ({@code ~/.cloudidea/cloudtoolkit.db}).
 *
 * <p>The database already exists and ships its own schema (tables {@code profiles}, {@code hosts},
 * {@code tags}, {@code host_tag}, {@code commands}, {@code docker_registries}, ...). This class only
 * reuses the existing tables and never drops or recreates them.</p>
 */
public final class CloudToolkitDb {

    public static final class ProfileRow {
        public int id;
        public String name;
        public String user;
        public String passwd;
        public String privateKey;
        public String passphrase;
    }

    public static final class TagRow {
        public int id;
        public String name;
    }

    public static final class HostRow {
        public int id;
        public String address;
        public int port;
        public int profileId;
        public int hostType;
        public int tagId;
        public int proxyId;
        public String remark;
    }

    public static final class CommandRow {
        public int id;
        public String content;
        public String digest;
    }

    private static final String DB_FILE_NAME = "cloudtoolkit.db";
    private static final CloudToolkitDb INSTANCE = new CloudToolkitDb();

    private final String dbPath;
    private final Connection connection;

    private CloudToolkitDb() {
        Path dbFile;
        String override = System.getProperty("cloudtoolkit.db.path");
        if (override != null && !override.isBlank()) {
            dbFile = Path.of(override);
        } else {
            Path dir = Path.of(System.getProperty("user.home", ".")).resolve(".cloudidea");
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
                // best effort; the file may already exist
            }
            dbFile = dir.resolve(DB_FILE_NAME);
        }
        this.dbPath = dbFile.toString();
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            this.connection.setAutoCommit(true);
            ensureSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("无法打开 Cloud Toolkit 数据库: " + dbPath, e);
        }
    }

    /** Creates the tables this plugin relies on if they are missing (harmless when they already exist). */
    private void ensureSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS profiles ("
                    + "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL, user TEXT NOT NULL, passwd TEXT, "
                    + "private_key TEXT, pass_phrase TEXT, UNIQUE (name))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS tags ("
                    + "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, UNIQUE (name))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS hosts ("
                    + "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, address TEXT NOT NULL, "
                    + "port INTEGER NOT NULL, profile_id INTEGER NOT NULL, host_type INTEGER DEFAULT 0, "
                    + "tag_id INTEGER DEFAULT 0, proxy_id INTEGER DEFAULT 0, remark TEXT, "
                    + "UNIQUE (address, port, host_type, tag_id))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS commands ("
                    + "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, "
                    + "digest TEXT NOT NULL, UNIQUE (digest))");
        }
    }

    public static CloudToolkitDb getInstance() {
        return INSTANCE;
    }

    public String getDbPath() {
        return dbPath;
    }

    // ------------------------------------------------------------------------------------------
    // profiles
    // ------------------------------------------------------------------------------------------

    public synchronized List<ProfileRow> listProfiles() {
        List<ProfileRow> rows = new ArrayList<>();
        String sql = "SELECT id, name, user, passwd, private_key, pass_phrase FROM profiles ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(mapProfile(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 profiles 失败", e);
        }
        return rows;
    }

    public synchronized ProfileRow findProfileByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String sql = "SELECT id, name, user, passwd, private_key, pass_phrase FROM profiles WHERE name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapProfile(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 profile 失败", e);
        }
    }

    /** Insert or update a profile by its unique {@code name}. Returns the profile id. */
    public synchronized int upsertProfile(ProfileRow profile) {
        ProfileRow existing = findProfileByName(profile.name);
        if (existing == null) {
            String sql = "INSERT INTO profiles(name, user, passwd, private_key, pass_phrase) "
                    + "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, profile.name);
                ps.setString(2, profile.user);
                ps.setString(3, orNull(profile.passwd));
                ps.setString(4, orNull(profile.privateKey));
                ps.setString(5, orNull(profile.passphrase));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getInt(1) : -1;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("插入 profile 失败", e);
            }
        }
        String sql = "UPDATE profiles SET user = ?, passwd = ?, private_key = ?, pass_phrase = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, profile.user);
            ps.setString(2, orNull(profile.passwd));
            ps.setString(3, orNull(profile.privateKey));
            ps.setString(4, orNull(profile.passphrase));
            ps.setInt(5, existing.id);
            ps.executeUpdate();
            return existing.id;
        } catch (SQLException e) {
            throw new IllegalStateException("更新 profile 失败", e);
        }
    }

    /** Update an existing profile's columns by id. */
    public synchronized void updateProfileById(int id, ProfileRow profile) {
        String sql = "UPDATE profiles SET name = ?, user = ?, passwd = ?, private_key = ?, pass_phrase = ? "
                + "WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, profile.name);
            ps.setString(2, profile.user);
            ps.setString(3, orNull(profile.passwd));
            ps.setString(4, orNull(profile.privateKey));
            ps.setString(5, orNull(profile.passphrase));
            ps.setInt(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新 profile 失败", e);
        }
    }

    /** Delete a profile by id. */
    public synchronized void deleteProfile(int id) {
        String sql = "DELETE FROM profiles WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 profile 失败", e);
        }
    }

    /** Number of hosts currently assigned to the given profile. */
    public synchronized int countHostsByProfile(int profileId) {
        String sql = "SELECT COUNT(*) FROM hosts WHERE profile_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 profile 关联主机失败", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // tags
    // ------------------------------------------------------------------------------------------

    public synchronized List<TagRow> listTags() {
        List<TagRow> rows = new ArrayList<>();
        String sql = "SELECT id, name FROM tags ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TagRow tag = new TagRow();
                tag.id = rs.getInt("id");
                tag.name = rs.getString("name");
                rows.add(tag);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 tags 失败", e);
        }
        return rows;
    }

    public synchronized TagRow findTagByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String sql = "SELECT id, name FROM tags WHERE name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    TagRow tag = new TagRow();
                    tag.id = rs.getInt("id");
                    tag.name = rs.getString("name");
                    return tag;
                }
                return null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 tag 失败", e);
        }
    }

    /** Returns the id of the tag, creating it when missing. */
    public synchronized int findOrCreateTag(String name) {
        TagRow existing = findTagByName(name);
        if (existing != null) {
            return existing.id;
        }
        String sql = "INSERT INTO tags(name) VALUES (?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建 tag 失败", e);
        }
    }

    /** Update an existing tag's name by id. */
    public synchronized void updateTag(int id, String name) {
        String sql = "UPDATE tags SET name = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新 tag 失败", e);
        }
    }

    /** Delete a tag by id. */
    public synchronized void deleteTag(int id) {
        String sql = "DELETE FROM tags WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 tag 失败", e);
        }
    }

    /** Number of hosts currently assigned to the given tag. */
    public synchronized int countHostsByTag(int tagId) {
        String sql = "SELECT COUNT(*) FROM hosts WHERE tag_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 tag 关联主机失败", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // hosts
    // ------------------------------------------------------------------------------------------

    public synchronized List<HostRow> listHosts() {
        List<HostRow> rows = new ArrayList<>();
        String sql = "SELECT id, address, port, profile_id, host_type, tag_id, proxy_id, remark FROM hosts "
                + "ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(mapHost(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 hosts 失败", e);
        }
        return rows;
    }

    public synchronized List<HostRow> listHostsByTag(int tagId) {
        List<HostRow> rows = new ArrayList<>();
        String sql = "SELECT id, address, port, profile_id, host_type, tag_id, proxy_id, remark "
                + "FROM hosts WHERE tag_id = ? ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapHost(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 hosts 失败", e);
        }
        return rows;
    }

    public synchronized HostRow findHostById(int id) {
        String sql = "SELECT id, address, port, profile_id, host_type, tag_id, proxy_id, remark "
                + "FROM hosts WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapHost(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 host 失败", e);
        }
    }

    /** Insert or update a host. Returns its id (generated on insert). */
    public synchronized int upsertHost(HostRow host) {
        if (host.id <= 0) {
            String sql = "INSERT INTO hosts(address, port, profile_id, host_type, tag_id, proxy_id, remark) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindHost(ps, host);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getInt(1) : -1;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("插入 host 失败", e);
            }
        }
        String sql = "UPDATE hosts SET address = ?, port = ?, profile_id = ?, host_type = ?, "
                + "tag_id = ?, proxy_id = ?, remark = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindHost(ps, host);
            ps.setInt(8, host.id);
            ps.executeUpdate();
            return host.id;
        } catch (SQLException e) {
            throw new IllegalStateException("更新 host 失败", e);
        }
    }

    public synchronized void deleteHost(int id) {
        String sql = "DELETE FROM hosts WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 host 失败", e);
        }
    }

    public synchronized String getSecretForHost(int hostId) {
        HostRow host = findHostById(hostId);
        if (host == null) {
            return null;
        }
        ProfileRow profile = findProfileById(host.profileId);
        if (profile == null) {
            return null;
        }
        boolean keyAuth = profile.privateKey != null && !profile.privateKey.isBlank();
        return keyAuth ? profile.passphrase : profile.passwd;
    }

    // ------------------------------------------------------------------------------------------
    // commands
    // ------------------------------------------------------------------------------------------

    public synchronized List<CommandRow> listCommands() {
        List<CommandRow> rows = new ArrayList<>();
        String sql = "SELECT id, content, digest FROM commands ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                CommandRow cmd = new CommandRow();
                cmd.id = rs.getInt("id");
                cmd.content = rs.getString("content");
                cmd.digest = rs.getString("digest");
                rows.add(cmd);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 commands 失败", e);
        }
        return rows;
    }

    /** Insert a command if its digest is new, otherwise return the existing id. */
    public synchronized int saveCommand(String content) {
        if (content == null || content.isBlank()) {
            return -1;
        }
        String digest = digest(content);
        CommandRow existing = findByDigest(digest);
        if (existing != null) {
            return existing.id;
        }
        String sql = "INSERT INTO commands(content, digest) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, content);
            ps.setString(2, digest);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("插入 command 失败", e);
        }
    }

    /** Update an existing command's content (digest recomputed). */
    public synchronized void updateCommand(int id, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String sql = "UPDATE commands SET content = ?, digest = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setString(2, digest(content));
            ps.setInt(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新 command 失败", e);
        }
    }

    public synchronized void deleteCommand(int id) {
        String sql = "DELETE FROM commands WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 command 失败", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    public ProfileRow findProfileById(int id) {
        if (id <= 0) {
            return null;
        }
        String sql = "SELECT id, name, user, passwd, private_key, pass_phrase FROM profiles WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapProfile(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 profile 失败", e);
        }
    }

    private CommandRow findByDigest(String digest) {
        String sql = "SELECT id, content, digest FROM commands WHERE digest = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, digest);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    CommandRow cmd = new CommandRow();
                    cmd.id = rs.getInt("id");
                    cmd.content = rs.getString("content");
                    cmd.digest = rs.getString("digest");
                    return cmd;
                }
                return null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 command 失败", e);
        }
    }

    public static String digest(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static ProfileRow mapProfile(ResultSet rs) throws SQLException {
        ProfileRow p = new ProfileRow();
        p.id = rs.getInt("id");
        p.name = rs.getString("name");
        p.user = rs.getString("user");
        p.passwd = rs.getString("passwd");
        p.privateKey = rs.getString("private_key");
        p.passphrase = rs.getString("pass_phrase");
        return p;
    }

    private static HostRow mapHost(ResultSet rs) throws SQLException {
        HostRow h = new HostRow();
        h.id = rs.getInt("id");
        h.address = rs.getString("address");
        h.port = rs.getInt("port");
        h.profileId = rs.getInt("profile_id");
        h.hostType = rs.getInt("host_type");
        h.tagId = rs.getInt("tag_id");
        h.proxyId = rs.getInt("proxy_id");
        h.remark = rs.getString("remark");
        return h;
    }

    private static void bindHost(PreparedStatement ps, HostRow host) throws SQLException {
        ps.setString(1, host.address);
        ps.setInt(2, host.port);
        ps.setInt(3, host.profileId);
        ps.setInt(4, host.hostType);
        ps.setInt(5, host.tagId);
        ps.setInt(6, host.proxyId);
        ps.setString(7, orNull(host.remark));
    }

    private static String orNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
