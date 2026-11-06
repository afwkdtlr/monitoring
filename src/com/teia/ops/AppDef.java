package com.teia.ops;

public final class AppDef {
    public final String id;
    public final String name;
    public final int httpPort;
    public final int wsPort;
    public final int jmxPort;
    public final String processName;
    public final String batPath;
    public final String commandHint;
    public final String jarSource;

    public AppDef(String id, String name, int httpPort, int wsPort, int jmxPort,
                  String processName, String batPath, String commandHint, String jarSource) {
        this.id = id;
        this.name = name;
        this.httpPort = httpPort;
        this.wsPort = wsPort;
        this.jmxPort = jmxPort;
        this.processName = processName;
        this.batPath = batPath;
        this.commandHint = commandHint;
        this.jarSource = jarSource;
    }
}
