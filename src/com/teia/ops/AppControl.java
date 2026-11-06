package com.teia.ops;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppControl {
    private final List<String> journal = Collections.synchronizedList(new ArrayList<String>());

    public String start(AppDef app) throws Exception {
        File bat = new File(app.batPath);
        if (!bat.isFile()) {
            throw new IllegalStateException("기동 스크립트가 없습니다: " + bat.getAbsolutePath());
        }
        File dir = bat.getParentFile();
        File launcher = new File(System.getProperty("java.io.tmpdir"), "ops-start-" + app.id + ".bat");
        writeLauncher(launcher, app.name, dir, bat);
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", launcher.getAbsolutePath());
        pb.directory(dir);
        pb.start();
        note(app.name + " 기동 요청 " + bat.getName());
        return "기동 창을 열었습니다";
    }

    public String deployJar(AppDef app, String jarDir) throws Exception {
        if (app.jarSource == null || app.jarSource.length() == 0) {
            throw new IllegalStateException(app.name + " JAR 원본 경로가 없습니다");
        }
        if (jarDir == null || jarDir.trim().length() == 0) {
            throw new IllegalStateException("설정에서 JAR 폴더를 입력하세요");
        }
        File source = new File(app.jarSource);
        if (!source.isFile()) {
            throw new IllegalStateException("target JAR가 없습니다: " + source.getAbsolutePath());
        }
        File destDir = new File(jarDir.trim());
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IllegalStateException("폴더를 만들지 못했습니다: " + destDir.getAbsolutePath());
        }
        File dest = new File(destDir, source.getName());
        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        note(app.name + " JAR 이동 " + dest.getName() + " " + dest.length() + " bytes");
        return dest.getName() + " 을 " + destDir.getAbsolutePath() + " 로 옮겼습니다";
    }

    private static void writeLauncher(File launcher, String name, File dir, File bat) throws Exception {
        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(launcher), Charset.forName("MS949"));
        try {
            writer.write("@echo off\r\n");
            writer.write("start \"OPS-" + name + "\" /D \"" + dir.getAbsolutePath()
                    + "\" /MIN cmd /c \"" + bat.getAbsolutePath() + "\"\r\n");
        } finally {
            writer.close();
        }
    }

    public String stop(AppDef app, ProcessProbe.Proc proc) throws Exception {
        if (proc == null) {
            throw new IllegalStateException(app.name + " 프로세스를 찾지 못했습니다");
        }
        String self = String.valueOf(selfPid());
        if (self.equals(String.valueOf(proc.pid))) {
            throw new IllegalStateException("모니터링 프로세스 자신은 종료하지 않습니다");
        }
        ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(proc.pid));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int code = process.waitFor();
        note(app.name + " 종료 요청 pid=" + proc.pid + " code=" + code);
        if (code != 0) {
            throw new IllegalStateException("taskkill 실패 code=" + code);
        }
        return "pid " + proc.pid + " 종료";
    }

    public List<String> recent() {
        synchronized (journal) {
            int from = Math.max(0, journal.size() - 30);
            return new ArrayList<String>(journal.subList(from, journal.size()));
        }
    }

    private void note(String line) {
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + " " + line;
        journal.add(stamped);
        if (journal.size() > 200) {
            journal.remove(0);
        }
    }

    private static int selfPid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        if (at > 0) {
            try {
                return Integer.parseInt(name.substring(0, at));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    public static Map<String, Object> action(String okMessage) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("ok", Boolean.TRUE);
        map.put("message", okMessage);
        return map;
    }
}
