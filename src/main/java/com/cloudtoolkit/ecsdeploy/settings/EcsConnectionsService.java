package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.APP)
@State(name = "EcsConnections", storages = @Storage("cloud-toolkit-ecs.xml"))
public final class EcsConnectionsService implements PersistentStateComponent<EcsConnectionState> {

    private final CredentialStore credentialStore = new CredentialStore();
    private EcsConnectionState state = new EcsConnectionState();

    public static EcsConnectionsService getInstance() {
        return ApplicationManager.getApplication().getService(EcsConnectionsService.class);
    }

    @Override
    public @NotNull EcsConnectionState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull EcsConnectionState state) {
        if (state.connections == null) {
            state.connections = new ArrayList<>();
        }
        this.state = state;
    }

    public List<EcsConnection> list() {
        return state.connections;
    }

    public EcsConnection findById(String id) {
        if (id == null) {
            return null;
        }
        for (EcsConnection connection : state.connections) {
            if (id.equals(connection.id)) {
                return connection;
            }
        }
        return null;
    }

    public void upsert(EcsConnection connection, String secret) {
        CredentialStore.SecretKind kind = connection.authType == EcsConnection.AuthType.PRIVATE_KEY
                ? CredentialStore.SecretKind.PASSPHRASE
                : CredentialStore.SecretKind.PASSWORD;
        credentialStore.setSecret(connection.id, kind, secret);

        for (int i = 0; i < state.connections.size(); i++) {
            if (connection.id.equals(state.connections.get(i).id)) {
                state.connections.set(i, connection);
                return;
            }
        }
        state.connections.add(connection);
    }

    public void delete(String id) {
        state.connections.removeIf(connection -> id != null && id.equals(connection.id));
        credentialStore.deleteSecrets(id);
    }
}
