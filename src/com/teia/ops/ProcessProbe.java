package com.teia.ops;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProcessProbe {
    public static final class Proc {
        public final int pid;
        public final String name;
        public final long workingSet;
        public final String commandLine;

        public Proc(int pid, String name, long workingSet, String commandLine) {
            this.pid = pid;
            this.name = name;
            this.workingSet = workingSet;
            this.commandLine = commandLine == null ? "" : commandLine;
        }
    }

    public List<Proc> javaProcesses() {
        List<Proc> list = fromPowerShell();
        if (list.isEmpty()) {
            list = fromWmic();
        }
        return list;
    }

    public Proc match(AppDef app, List<Proc> processes) {
        String image = app.processName.toLowerCase(Locale.ROOT);
        String hint = app.commandHint.toLowerCase(Locale.ROOT);
        Proc fallback = null;
        for (Proc proc : processes) {
            String name = proc.name.toLowerCase(Locale.ROOT);
            String cmd = proc.commandLine.toLowerCase(Locale.ROOT);
            if (name.equals(image) || name.equals(image.replace(".exe", ""))) {
                return proc;
            }
            if (hint.length() > 0) {
                String[] tokens = hint.split(";");
                for (int i = 0; i < tokens.length; i++) {
                    String token = tokens[i].trim();
                    if (token.length() > 0 && cmd.contains(token)) {
                        fallback = proc;
                        break;
                    }
                }
            }
        }
        return fallback;
    }

    private List<Proc> fromPowerShell() {
        File script = new File("config/list-java.ps1");
        if (!script.isFile()) {
            return new ArrayList<Proc>();
        }
        String text = run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath());
        return readLines(text);
    }

    private List<Proc> fromWmic() {
        String out = run("wmic", "process", "where", "name like 'java%'", "get",
                "ProcessId,Name,WorkingSetSize,CommandLine", "/format:list");
        List<Proc> list = new ArrayList<Proc>();
        String pid = null;
        String name = null;
        String mem = null;
        String cmd = null;
        String[] lines = out.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) {
                add(list, pid, name, mem, cmd);
                pid = name = mem = cmd = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if ("ProcessId".equalsIgnoreCase(key)) {
                pid = val;
            } else if ("Name".equalsIgnoreCase(key)) {
                name = val;
            } else if ("WorkingSetSize".equalsIgnoreCase(key)) {
                mem = val;
            } else if ("CommandLine".equalsIgnoreCase(key)) {
                cmd = val;
            }
        }
        add(list, pid, name, mem, cmd);
        return list;
    }

    private void add(List<Proc> list, String pid, String name, String mem, String cmd) {
        if (pid == null || pid.length() == 0) {
            return;
        }
        try {
            long ws = 0L;
            if (mem != null && mem.length() > 0) {
                ws = Long.parseLong(mem);
            }
            list.add(new Proc(Integer.parseInt(pid), name == null ? "" : name, ws, cmd));
        } catch (NumberFormatException ignored) {
        }
    }

    private List<Proc> readLines(String text) {
        List<Proc> list = new ArrayList<Proc>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.indexOf('|') < 0) {
                continue;
            }
            String[] p = trimmed.split("\\|", 4);
            if (p.length < 3) {
                continue;
            }
            try {
                int pid = Integer.parseInt(p[0].trim());
                String name = p[1].trim();
                long ws = 0L;
                if (p[2].trim().length() > 0) {
                    ws = Long.parseLong(p[2].trim());
                }
                String cmd = p.length > 3 ? p[3] : "";
                list.add(new Proc(pid, name, ws, cmd));
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }

    public static String run(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = process.getInputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) >= 0) {
                buf.write(tmp, 0, n);
            }
            process.waitFor();
            return decode(buf.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private static String decode(byte[] raw) {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        int zeros = 0;
        int sample = Math.min(raw.length, 200);
        for (int i = 1; i < sample; i += 2) {
            if (raw[i] == 0) {
                zeros++;
            }
        }
        if (sample > 8 && zeros > sample / 8) {
            return new String(raw, StandardCharsets.UTF_16LE);
        }
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            return new String(raw, Charset.defaultCharset());
        }
        return utf8;
    }
}
