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

    private @Nullable CloudToolkitDb.ProfileRow findProfileById(int id) {
        for (CloudToolkitDb.ProfileRow p : db.listProfiles()) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
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
