package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class EcsDeployConfigurationType extends ConfigurationTypeBase {

    public static final String ID = "EcsDeployRunConfiguration";

    public EcsDeployConfigurationType() {
        super(ID, EcsDeployMessages.message("configuration.type.name"),
                "Builds a Java artifact and deploys it to an ECS instance over SSH.",
                AllIcons.RunConfigurations.Application);
        addFactory(new EcsDeployFactory(this));
    }

    private static final class EcsDeployFactory extends ConfigurationFactory {

        private EcsDeployFactory(ConfigurationType type) {
            super(type);
        }

        @Override
        public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new EcsDeployRunConfiguration(project, this, "Deploy to ECS");
        }

        @Override
        public @NotNull String getId() {
            return ID;
        }
    }
}
