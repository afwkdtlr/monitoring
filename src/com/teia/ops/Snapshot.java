package com.teia.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Snapshot {
    private final Config config;
    private final HostProbe host = new HostProbe();
    private final ProcessProbe processes = new ProcessProbe();
    private final JmxProbe jmx = new JmxProbe();
    private final List<Map<String, Object>> cpuHistory = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
    private volatile Map<String, Object> cache = new LinkedHashMap<String, Object>();
    private volatile long cacheAt;

    public Snapshot(Config config) {
        this.config = config;
    }

    public void start() {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        refresh();
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "ops-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    public Map<String, Object> overview() {
        if (System.currentTimeMillis() - cacheAt > 8000L || cache.isEmpty()) {
            refresh();
        }
        return cache;
    }

    public void refresh() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        double cpu = host.systemCpu();
        long total = host.totalMem();
        long free = host.freeMem();
        Map<String, Object> hostMap = new LinkedHashMap<String, Object>();
        hostMap.put("os", host.osName());
        hostMap.put("cpu", Double.valueOf(round(cpu)));
        hostMap.put("cpus", Integer.valueOf(host.processors()));
        hostMap.put("memTotal", Long.valueOf(total));
        hostMap.put("memFree", Long.valueOf(free));
        hostMap.put("memUsed", Long.valueOf(total - free));
        hostMap.put("disks", host.disks());
        hostMap.put("time", Long.valueOf(System.currentTimeMillis()));
        root.put("host", hostMap);

        Map<String, Object> point = new LinkedHashMap<String, Object>();
        point.put("t", Long.valueOf(System.currentTimeMillis()));
        point.put("cpu", Double.valueOf(round(cpu)));
        point.put("mem", Long.valueOf(total - free));
        cpuHistory.add(point);
        while (cpuHistory.size() > 60) {
            cpuHistory.remove(0);
        }
        root.put("history", new ArrayList<Map<String, Object>>(cpuHistory));

        List<ProcessProbe.Proc> procs = processes.javaProcesses();
        Set<Integer> listening = JmxProbe.PortProbe.listeningPorts();
        List<Map<String, Object>> apps = new ArrayList<Map<String, Object>>();
        for (AppDef app : config.apps) {
            apps.add(readApp(app, procs, listening));
        }
        root.put("apps", apps);
        root.put("processes", toProcMaps(procs));
        cache = root;
        cacheAt = System.currentTimeMillis();
    }

    public ProcessProbe.Proc findProcess(AppDef app) {
        return processes.match(app, processes.javaProcesses());
    }

    public boolean responsive(AppDef app) {
        if (findProcess(app) == null) {
            return false;
        }
        return JmxProbe.PortProbe.httpResponds("127.0.0.1", app.httpPort, 1000);
    }

    private Map<String, Object> readApp(AppDef app, List<ProcessProbe.Proc> procs, Set<Integer> listening) {
        ProcessProbe.Proc proc = processes.match(app, procs);
        boolean httpUp = listening.contains(Integer.valueOf(app.httpPort)) || JmxProbe.PortProbe.open("127.0.0.1", app.httpPort, 250);
        boolean httpAlive = httpUp && JmxProbe.PortProbe.httpResponds("127.0.0.1", app.httpPort, 1000);
        boolean wsUp = listening.contains(Integer.valueOf(app.wsPort)) || JmxProbe.PortProbe.open("127.0.0.1", app.wsPort, 250);
        boolean jmxPort = listening.contains(Integer.valueOf(app.jmxPort));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", app.id);
        row.put("name", app.name);
        row.put("httpPort", Integer.valueOf(app.httpPort));
        row.put("wsPort", Integer.valueOf(app.wsPort));
        row.put("jmxPort", Integer.valueOf(app.jmxPort));
        row.put("bat", app.batPath);
        row.put("processName", app.processName);
        row.put("pid", proc == null ? null : Integer.valueOf(proc.pid));
        row.put("workingSet", proc == null ? null : Long.valueOf(proc.workingSet));
        row.put("command", proc == null ? "" : proc.commandLine);
        row.put("httpUp", Boolean.valueOf(httpUp));
        row.put("httpAlive", Boolean.valueOf(httpAlive));
        row.put("wsUp", Boolean.valueOf(wsUp));
        row.put("processUp", Boolean.valueOf(proc != null));
        String state = "DOWN";
        if (proc != null && httpAlive) {
            state = "UP";
        } else if (proc != null || httpUp || jmxPort) {
            state = "DEGRADED";
        }
        row.put("state", state);
        if (jmxPort || proc != null) {
            row.put("jmx", jmx.read(app.jmxPort));
        } else {
            Map<String, Object> down = new LinkedHashMap<String, Object>();
            down.put("up", Boolean.FALSE);
            row.put("jmx", down);
        }
        return row;
    }

    private List<Map<String, Object>> toProcMaps(List<ProcessProbe.Proc> procs) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ProcessProbe.Proc proc : procs) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("pid", Integer.valueOf(proc.pid));
            row.put("name", proc.name);
            row.put("workingSet", Long.valueOf(proc.workingSet));
            String cmd = proc.commandLine;
            if (cmd.length() > 220) {
                cmd = cmd.substring(0, 220) + "...";
            }
            row.put("command", cmd);
            list.add(row);
        }
        return list;
    }

    private static double round(double v) {
        if (v < 0) {
            return 0;
        }
        return Math.round(v * 10.0) / 10.0;
    }
}
