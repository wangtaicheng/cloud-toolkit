package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.deploy.DeployContext;
import com.cloudtoolkit.ecsdeploy.deploy.DeployException.ConfigurationException;
import com.cloudtoolkit.ecsdeploy.settings.CredentialStore;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnectionsService;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class EcsDeployRunConfiguration extends RunConfigurationBase<EcsDeployRunConfigurationOptions> {

    EcsDeployRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override
    protected @NotNull Class<? extends RunConfigurationOptions> getOptionsClass() {
        return EcsDeployRunConfigurationOptions.class;
    }

    @Override
    public @NotNull EcsDeployRunConfigurationOptions getOptions() {
        return (EcsDeployRunConfigurationOptions) super.getOptions();
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new EcsDeploySettingsEditor();
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor,
                                             @NotNull ExecutionEnvironment environment) {
        return null;
    }

    public DeployContext toContext() throws ConfigurationException {
        EcsDeployRunConfigurationOptions options = getOptions();
        EcsConnection connection = EcsConnectionsService.getInstance().findById(options.getConnectionId());
        if (connection == null) {
            throw new ConfigurationException(EcsDeployMessages.message("validation.connection.missing"));
        }
        CredentialStore.SecretKind kind = connection.authType == EcsConnection.AuthType.PRIVATE_KEY
                ? CredentialStore.SecretKind.PASSPHRASE
                : CredentialStore.SecretKind.PASSWORD;
        String secret = new CredentialStore().getSecret(connection.id, kind);
        return new DeployContext(connection, secret, Path.of(options.getArtifactPath()),
                options.getRemoteDirectory(), options.getStopCommand(), options.getStartCommand());
    }
}
