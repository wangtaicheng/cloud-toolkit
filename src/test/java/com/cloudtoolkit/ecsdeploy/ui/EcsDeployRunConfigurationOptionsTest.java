package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;

public class EcsDeployRunConfigurationOptionsTest extends LightPlatformTestCase {

    public void testOptionsRoundTrip() {
        EcsDeployRunConfigurationOptions options = new EcsDeployRunConfigurationOptions();
        options.setConnectionId("c1");
        options.setArtifactPath("/tmp/app.jar");
        options.setRemoteDirectory("/opt/app");
        options.setStopCommand("echo stop");
        options.setStartCommand("echo start");

        Element xml = XmlSerializer.serialize(options);
        EcsDeployRunConfigurationOptions copy =
                XmlSerializer.deserialize(xml, EcsDeployRunConfigurationOptions.class);

        assertEquals("c1", copy.getConnectionId());
        assertEquals("/opt/app", copy.getRemoteDirectory());
        assertFalse(JDOMUtil.write(xml).contains("SUPER_SECRET"));
    }
}
