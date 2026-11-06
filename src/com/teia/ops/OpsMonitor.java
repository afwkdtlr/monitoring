package com.teia.ops;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class OpsMonitor {
    private static final String[] ALLOWED = new String[] {
            "MONITER_SOCKET_LIST",
            "MONITER_CHANNEL_LIST",
            "MONITER_CHANNEL_READ_LIST",
            "MONITER_CHANNEL_WRITE_LIST",
            "MONITER_CHECK_PING_SK",
            "MONITER_THREAD_LIST",
            "SK_START",
            "SK_STOP",
            "SUB_START",
            "SUB_STOP",
            "RERUN_CLIENT",
            "SK_KEEP_ALIVE"
    };

    private static final long BOOT_WAIT_MS = 120000L;
    private static final long BOOT_POLL_MS = 2000L;

    private final Config config;
    private final Snapshot snapshot;
    private final AppControl control = new AppControl();
    private final MiniWebSocket sockets = new MiniWebSocket();
    private final ScriptEngine engine;
    private final ExecutorService actions = Executors.newSingleThreadExecutor();

    public OpsMonitor(Config config) throws Exception {
        this.config = config;
        this.snapshot = new Snapshot(config);
        ScriptEngineManager manager = new ScriptEngineManager();
        engine = manager.getEngineByName("nashorn");
        if (engine == null) {
            throw new IllegalStateException("Nashorn 이 없습니다. Java 8 로 실행하세요.");
        }
        engine.eval("function parseJson(s){ return Java.asJSONCompatible(JSON.parse(s)); }");
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load(args);
        OpsMonitor app = new OpsMonitor(config);
        app.snapshot.start();
        HttpServer server = HttpServer.create(new InetSocketAddress(config.bindHost, config.port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();
        System.out.println("TEIA OPS http://" + ("0.0.0.0".equals(config.bindHost) ? "127.0.0.1" : config.bindHost) + ":" + config.port);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendFile(ex, new File(config.webRoot, "index.html"));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/assets/")) {
                String name = path.substring("/assets/".length());
                if (name.indexOf("..") >= 0 || name.indexOf(':') >= 0) {
                    send(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"bad path\"}");
                    return;
                }
                sendFile(ex, new File(config.webRoot, name));
                return;
            }
            if ("GET".equals(method) && "/api/overview".equals(path)) {
                Map<String, Object> body = new LinkedHashMap<String, Object>(snapshot.overview());
                body.put("ok", Boolean.TRUE);
                body.put("journal", control.recent());
                body.put("jarDir", config.jarDir);
                send(ex, 200, "application/json; charset=utf-8", JsonOut.write(body));
                return;
            }
            if ("GET".equals(method) && "/api/settings".equals(path)) {
                send(ex, 200, "application/json; charset=utf-8", JsonOut.write(settingsBody()));
                return;
            }
            if ("POST".equals(method) && "/api/settings".equals(path)) {
                saveSettings(readBody(ex));
                snapshot.refresh();
                sendOk(ex, "설정을 저장했습니다");
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/apps/") && path.endsWith("/deploy")) {
                AppDef app = appFrom(path, "/deploy");
                sendOk(ex, control.deployJar(app, config.jarDir));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/apps/") && path.endsWith("/start")) {
                final AppDef app = appFrom(path, "/start");
                handOff(ex, new Runnable() {
                    public void run() {
                        finishAction(ex, app, false);
                    }
                });
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/apps/") && path.endsWith("/stop")) {
                AppDef app = appFrom(path, "/stop");
                String message = control.stop(app, snapshot.findProcess(app));
                snapshot.refresh();
                sendOk(ex, message);
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/apps/") && path.endsWith("/restart")) {
                final AppDef app = appFrom(path, "/restart");
                handOff(ex, new Runnable() {
                    public void run() {
                        finishAction(ex, app, true);
                    }
                });
                return;
            }
            if ("POST".equals(method) && "/api/socket".equals(path)) {
                send(ex, 200, "application/json; charset=utf-8", socketCall(readBody(ex)));
                return;
            }
            send(ex, 404, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"not found\"}");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            send(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"message\":" + Json.quote(msg) + "}");
        }
    }

    private Map<String, Object> settingsBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", Boolean.TRUE);
        body.put("jarDir", config.jarDir);
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (AppDef app : config.apps) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", app.id);
            row.put("name", app.name);
            row.put("httpPort", Integer.valueOf(app.httpPort));
            row.put("wsPort", Integer.valueOf(app.wsPort));
            row.put("jmxPort", Integer.valueOf(app.jmxPort));
            row.put("processName", app.processName);
            row.put("batPath", app.batPath);
            row.put("commandHint", app.commandHint);
            row.put("jarSource", app.jarSource);
            rows.add(row);
        }
        body.put("apps", rows);
        return body;
    }

    @SuppressWarnings("rawtypes")
    private void saveSettings(String body) throws Exception {
        Object parsed = ((Invocable) engine).invokeFunction("parseJson", body);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("JSON 객체가 필요합니다");
        }
        Map map = (Map) parsed;
        String jarDir = str(map.get("jarDir")).trim();
        Object rawApps = map.get("apps");
        if (!(rawApps instanceof List)) {
            throw new IllegalArgumentException("앱 목록이 필요합니다");
        }
        List raw = (List) rawApps;
        java.util.ArrayList<AppDef> apps = new java.util.ArrayList<AppDef>();
        java.util.HashSet<String> ids = new java.util.HashSet<String>();
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map)) {
                throw new IllegalArgumentException("앱 항목 형식 오류");
            }
            Map row = (Map) raw.get(i);
            String id = str(row.get("id")).trim();
            String name = str(row.get("name")).trim();
            String process = str(row.get("processName")).trim();
            String bat = str(row.get("batPath")).trim();
            if (!id.matches("[A-Za-z0-9_\\-]{1,32}")) {
                throw new IllegalArgumentException("앱 ID는 영문, 숫자, _, - 만 가능합니다: " + id);
            }
            if (!ids.add(id.toLowerCase())) {
                throw new IllegalArgumentException("앱 ID가 겹칩니다: " + id);
            }
            if (name.length() == 0 || process.length() == 0 || bat.length() == 0) {
                throw new IllegalArgumentException(id + " 이름, 프로세스, 기동 bat 은 비울 수 없습니다");
            }
            apps.add(new AppDef(
                    id,
                    name,
                    portOf(row.get("httpPort"), id + " HTTP"),
                    portOf(row.get("wsPort"), id + " WS"),
                    portOf(row.get("jmxPort"), id + " JMX"),
                    process,
                    bat,
                    str(row.get("commandHint")).trim(),
                    str(row.get("jarSource")).trim()
            ));
        }
        config.store.save(jarDir, apps);
        config.reload();
    }

    private static int portOf(Object value, String label) {
        int port;
        try {
            if (value instanceof Number) {
                port = ((Number) value).intValue();
            } else {
                port = Integer.parseInt(str(value).trim());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " 포트가 숫자가 아닙니다");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(label + " 포트 범위가 아닙니다");
        }
        return port;
    }

    private void handOff(final HttpExchange ex, Runnable job) {
        actions.execute(job);
    }

    private void finishAction(HttpExchange ex, AppDef app, boolean restart) {
        try {
            String message = bringUp(app, restart);
            snapshot.refresh();
            sendOk(ex, message);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                send(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"message\":" + Json.quote(msg) + "}");
            } catch (IOException ignored) {
            }
        }
    }

    private String bringUp(AppDef app, boolean restart) throws Exception {
        ProcessProbe.Proc proc = snapshot.findProcess(app);
        if (!restart) {
            if (proc != null && snapshot.responsive(app)) {
                return "이미 HTTP 응답 중 pid " + proc.pid;
            }
            if (proc != null) {
                throw new IllegalStateException("프로세스는 있으나 HTTP 응답이 없습니다. 재시작하세요");
            }
        } else if (proc != null) {
            control.stop(app, proc);
            Thread.sleep(10000L);
        }
        control.start(app);
        long deadline = System.currentTimeMillis() + BOOT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(BOOT_POLL_MS);
            ProcessProbe.Proc started = snapshot.findProcess(app);
            if (started != null && JmxProbe.PortProbe.httpResponds("127.0.0.1", app.httpPort, 1000)) {
                String prefix = restart && proc != null ? "종료 후 10초 대기, " : "";
                return prefix + "HTTP 응답 확인 pid " + started.pid;
            }
        }
        throw new IllegalStateException("120초 안에 HTTP 응답이 없습니다");
    }

    private AppDef appFrom(String path, String suffix) {
        String id = path.substring("/api/apps/".length(), path.length() - suffix.length());
        try {
            id = URLDecoder.decode(id, "UTF-8");
        } catch (Exception ignored) {
        }
        AppDef app = config.find(id);
        if (app == null) {
            throw new IllegalArgumentException("없는 앱: " + id);
        }
        return app;
    }

    @SuppressWarnings("rawtypes")
    private String socketCall(String body) throws Exception {
        Object parsed = ((Invocable) engine).invokeFunction("parseJson", body);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("JSON 객체가 필요합니다");
        }
        Map map = (Map) parsed;
        String appId = str(map.get("app"));
        String execute = str(map.get("execute"));
        AppDef app = config.find(appId);
        if (app == null) {
            throw new IllegalArgumentException("없는 앱: " + appId);
        }
        if (!allowed(execute)) {
            throw new IllegalArgumentException("허용되지 않은 명령: " + execute);
        }
        String workJson = workJson(map.get("work"));
        String payload = "{\"COMMAND\":\"SOCKET_SET\",\"EXECUTE\":" + Json.quote(execute)
                + ",\"WORK_VALUE\":" + workJson + ",\"DATA_LIST\":\"\"}";
        long t0 = System.currentTimeMillis();
        String text = sockets.request("127.0.0.1", app.wsPort, payload, execute, 8000);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("app", app.id);
        out.put("execute", execute);
        out.put("elapsedMs", Long.valueOf(System.currentTimeMillis() - t0));
        out.put("text", text);
        return JsonOut.write(out);
    }

    @SuppressWarnings("rawtypes")
    private String workJson(Object work) {
        if (!(work instanceof Map)) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        Iterator it = ((Map) work).entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry) it.next();
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(Json.quote(String.valueOf(e.getKey()))).append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(String.valueOf(v));
            } else {
                sb.append(Json.quote(String.valueOf(v)));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean allowed(String execute) {
        for (int i = 0; i < ALLOWED.length; i++) {
            if (ALLOWED[i].equals(execute)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private void sendOk(HttpExchange ex, String message) throws IOException {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("ok", Boolean.TRUE);
        map.put("message", message);
        send(ex, 200, "application/json; charset=utf-8", JsonOut.write(map));
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) >= 0) {
            buf.write(tmp, 0, n);
            if (buf.size() > 1024 * 1024) {
                break;
            }
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    private void sendFile(HttpExchange ex, File file) throws IOException {
        if (file == null || !file.isFile()) {
            send(ex, 404, "text/plain; charset=utf-8", "file not found");
            return;
        }
        byte[] bytes = readAll(file);
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", mime(file.getName()));
        headers.set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) >= 0) {
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String mime(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
