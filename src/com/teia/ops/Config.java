package com.teia.ops;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class Config {
    public final String bindHost;
    public final int port;
    public final File webRoot;
    public final Store store;
    public volatile List<AppDef> apps;
    public volatile String jarDir;

    private Config(String bindHost, int port, File webRoot, Store store) {
        this.bindHost = bindHost;
        this.port = port;
        this.webRoot = webRoot;
        this.store = store;
    }

    public static Config load(String[] args) throws Exception {
        String bind = "0.0.0.0";
        int port = 18080;
        File base = new File(System.getProperty("user.dir"));
        File configFile = new File(base, "config/apps.properties");
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--bind".equals(args[i]) && i + 1 < args.length) {
                bind = args[++i];
            } else if ("--config".equals(args[i]) && i + 1 < args.length) {
                configFile = new File(args[++i]);
            }
        }
        File web = new File(base, "web");
        if (!web.isDirectory()) {
            web = new File(base, "ops-monitor/web");
        }
        Store store = new Store(new File(base, "data/ops.db"));
        store.init(configFile);
        Config config = new Config(bind, port, web, store);
        config.reload();
        return config;
    }

    public void reload() throws Exception {
        this.apps = Collections.unmodifiableList(store.apps());
        String dir = store.jarDir();
        this.jarDir = dir == null ? "" : dir;
    }

    public AppDef find(String id) {
        for (AppDef app : apps) {
            if (app.id.equalsIgnoreCase(id)) {
                return app;
            }
        }
        return null;
    }
}
