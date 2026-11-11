package com.cloudtoolkit.ecsdeploy.settings;

public final class EcsConnection {

    public enum AuthType { PASSWORD, PRIVATE_KEY }

    public String id;
    public String displayName;
    public String host;
    public int port = 22;
    public String username;
    public AuthType authType = AuthType.PASSWORD;
    public String privateKeyPath;
    public boolean hasPassphrase;
    public String tag;
    public String remark;
}
