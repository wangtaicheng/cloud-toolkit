package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.db.CloudToolkitDb;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Backed by {@code ~/.cloudidea/cloudtoolkit.db}. Credentials live in the {@code profiles} table
 * (columns {@code passwd} / {@code private_key} / {@code pass_phrase}); servers live in
 * {@code hosts}; grouping uses {@code tags} via {@code hosts.tag_id}.
 */
@Service(Service.Level.APP)
public final class EcsConnectionsService {

    private final CloudToolkitDb db = CloudToolkitDb.getInstance();

    public static EcsConnectionsService getInstance() {
        return ApplicationManager.getApplication().getService(EcsConnectionsService.class);
    }

    public @NotNull List<EcsConnection> list() {
        List<EcsConnection> result = new ArrayList<>();
        for (CloudToolkitDb.HostRow host : db.listHosts()) {
            EcsConnection connection = toConnection(host);
            if (connection != null) {
                result.add(connection);
            }
        }
        return result;
    }

    public @Nullable EcsConnection findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            int hostId = Integer.parseInt(id);
            return toConnection(db.findHostById(hostId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The stored host with the given address, or null when there is none. */
    public @Nullable EcsConnection findByAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        for (EcsConnection connection : list()) {
            if (address.equalsIgnoreCase(connection.host)) {
                return connection;
            }
        }
        return null;
    }

    public List<EcsConnection> findByTag(String tagName) {
        List<EcsConnection> result = new ArrayList<>();
        CloudToolkitDb.TagRow tag = db.findTagByName(tagName);
        if (tag == null) {
            return result;
        }
        for (CloudToolkitDb.HostRow host : db.listHostsByTag(tag.id)) {
            EcsConnection connection = toConnection(host);
            if (connection != null) {
                result.add(connection);
            }
        }
        return result;
    }

    /**
     * Insert or update a connection. The {@code secret} is stored as the profile password (or
     * private-key passphrase for key based auth). Returns the connection id (host id).
     */
    public String upsert(EcsConnection connection, String secret) {
        String profileName = connection.displayName == null || connection.displayName.isBlank()
                ? connection.host : connection.displayName;
        CloudToolkitDb.ProfileRow profile = new CloudToolkitDb.ProfileRow();
        profile.name = profileName;
        profile.user = connection.username;
        boolean keyAuth = connection.authType == EcsConnection.AuthType.PRIVATE_KEY;
        if (keyAuth) {
            profile.privateKey = connection.privateKeyPath;
            profile.passphrase = secret;
        } else {
            profile.passwd = secret;
        }
        int profileId = db.upsertProfile(profile);

        int tagId = 0;
        if (connection.tag != null && !connection.tag.isBlank()) {
            tagId = db.findOrCreateTag(connection.tag);
        }

        CloudToolkitDb.HostRow host = new CloudToolkitDb.HostRow();
        if (connection.id != null && !connection.id.isBlank()) {
            try {
                host.id = Integer.parseInt(connection.id);
            } catch (NumberFormatException ignored) {
                host.id = 0;
            }
        }
        host.address = connection.host;
        host.port = connection.port;
        host.profileId = profileId;
        host.tagId = tagId;
        host.remark = connection.remark;

        int hostId = db.upsertHost(host);
        connection.id = String.valueOf(hostId);
        return connection.id;
    }

    public void delete(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            db.deleteHost(Integer.parseInt(id));
        } catch (NumberFormatException ignored) {
            // ignore un-parsable ids
        }
    }

    public @Nullable String getSecret(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        try {
            return db.getSecretForHost(Integer.parseInt(connectionId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public List<String> listTags() {
        List<String> names = new ArrayList<>();
        for (CloudToolkitDb.TagRow tag : db.listTags()) {
            names.add(tag.name);
        }
        return names;
    }

    /** All stored tag rows (id + name) for the tag management UI. */
    public List<CloudToolkitDb.TagRow> listTagRows() {
        return db.listTags();
    }

    public @Nullable CloudToolkitDb.TagRow findTagByName(String name) {
        return db.findTagByName(name);
    }

    public void createTag(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        db.findOrCreateTag(name);
    }

    public void updateTag(int id, String name) {
        db.updateTag(id, name);
    }

    public void deleteTag(int id) {
        if (db.countHostsByTag(id) > 0) {
            throw new IllegalStateException("该标签仍被主机使用，无法删除");
        }
        db.deleteTag(id);
    }

    /** All stored SSH profiles (for the "use existing profile" mode). */
    public List<CloudToolkitDb.ProfileRow> listProfiles() {
        return db.listProfiles();
    }

    public @Nullable CloudToolkitDb.ProfileRow findProfileById(int id) {
        return db.findProfileById(id);
    }

    public @Nullable CloudToolkitDb.ProfileRow findProfileByName(String name) {
        return db.findProfileByName(name);
    }

    public void createProfile(CloudToolkitDb.ProfileRow profile) {
        if (profile == null || profile.name == null || profile.name.isBlank()) {
            return;
        }
        if (db.findProfileByName(profile.name) != null) {
            throw new IllegalStateException("Profile 名称已存在：" + profile.name);
        }
        db.upsertProfile(profile);
    }

    public void updateProfile(int id, CloudToolkitDb.ProfileRow profile) {
        db.updateProfileById(id, profile);
    }

    public void deleteProfile(int id) {
        if (db.countHostsByProfile(id) > 0) {
            throw new IllegalStateException("该 Profile 仍被主机使用，无法删除");
        }
        db.deleteProfile(id);
    }

    /**
     * Insert or update a host that reuses an existing {@code profileId} (instead of creating a new
     * profile) and optionally points at a jump host ({@code proxyHostId}, 0 = none). Passing a
     * {@code hostId} &gt; 0 updates that row, otherwise a new host is inserted.
     */
    public String upsertHostWithProfile(int hostId, String address, int port, String tag,
                                        String remark, int profileId, int proxyHostId) {
        int tagId = (tag == null || tag.isBlank()) ? 0 : db.findOrCreateTag(tag);
        CloudToolkitDb.HostRow host = new CloudToolkitDb.HostRow();
        host.id = Math.max(hostId, 0);
        host.address = address;
        host.port = port;
        host.profileId = profileId;
        host.tagId = tagId;
        host.proxyId = Math.max(proxyHostId, 0);
        host.remark = remark;
        int id = db.upsertHost(host);
        return String.valueOf(id);
    }

    private @Nullable EcsConnection toConnection(@Nullable CloudToolkitDb.HostRow host) {
        if (host == null) {
            return null;
        }
        CloudToolkitDb.ProfileRow profile = host.profileId > 0 ? findProfileById(host.profileId) : null;
        EcsConnection connection = new EcsConnection();
        connection.id = String.valueOf(host.id);
        connection.host = host.address;
        connection.port = host.port;
        connection.remark = host.remark;
        connection.proxyId = host.proxyId;
        connection.tag = host.tagId > 0 ? tagName(host.tagId) : null;
        if (profile != null) {
            connection.displayName = profile.name;
            connection.username = profile.user;
            boolean keyAuth = profile.privateKey != null && !profile.privateKey.isBlank();
            connection.authType = keyAuth ? EcsConnection.AuthType.PRIVATE_KEY : EcsConnection.AuthType.PASSWORD;
            connection.privateKeyPath = profile.privateKey;
            connection.hasPassphrase = profile.passphrase != null && !profile.passphrase.isBlank();
        } else {
            connection.username = "";
            connection.authType = EcsConnection.AuthType.PASSWORD;
        }
        return connection;
    }

    private @Nullable String tagName(int tagId) {
        for (CloudToolkitDb.TagRow t : db.listTags()) {
            if (t.id == tagId) {
                return t.name;
            }
        }
        return null;
    }
}
