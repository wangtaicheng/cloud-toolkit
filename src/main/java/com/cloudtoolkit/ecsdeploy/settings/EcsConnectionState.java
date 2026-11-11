package com.cloudtoolkit.ecsdeploy.settings;

import com.intellij.util.xmlb.annotations.Property;
import com.intellij.util.xmlb.annotations.Tag;

import java.util.ArrayList;
import java.util.List;

public final class EcsConnectionState {

    @Tag("connection")
    @Property(surroundWithTag = true)
    public List<EcsConnection> connections = new ArrayList<>();
}
