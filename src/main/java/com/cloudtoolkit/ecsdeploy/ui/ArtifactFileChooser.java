package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;

/**
 * Shared "select build artifact / upload file" chooser, reused by the Upload action, the
 * run configuration editor and the Deploy-to-Host dialog so they all present one consistent
 * file selection box.
 */
public final class ArtifactFileChooser {

    private static final String TITLE = "选择构建产物 / 上传文件";

    private ArtifactFileChooser() {
    }

    /** Attaches the artifact browse listener to the given field, bound to {@code project}. */
    public static void attach(Project project, TextFieldWithBrowseButton field) {
        field.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle(TITLE));
    }
}
