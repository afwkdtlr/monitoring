package com.teia.ops;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.management.OperatingSystemMXBean;

public final class HostProbe {
    private final OperatingSystemMXBean os;

    public HostProbe() {
        os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    public double systemCpu() {
        double v = os.getSystemCpuLoad();
        if (v < 0) {
            return -1;
        }
        return v * 100.0;
    }

    public long totalMem() {
        return os.getTotalPhysicalMemorySize();
    }

    public long freeMem() {
        return os.getFreePhysicalMemorySize();
    }

    public int processors() {
        return os.getAvailableProcessors();
    }

    public String osName() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch");
    }

    public List<Map<String, Object>> disks() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        File[] roots = File.listRoots();
        if (roots == null) {
            return list;
        }
        for (File root : roots) {
            long total = root.getTotalSpace();
            if (total <= 0) {
                continue;
            }
            long free = root.getFreeSpace();
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("path", root.getAbsolutePath());
            row.put("total", Long.valueOf(total));
            row.put("free", Long.valueOf(free));
            row.put("used", Long.valueOf(total - free));
            list.add(row);
        }
        return list;
    }
}
