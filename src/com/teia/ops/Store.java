package com.teia.ops;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 사이트마다 다른 경로를 data/ops.db 에 둔다.
 * 처음 실행 때만 config/apps.properties 를 읽어 채운다.
 */
public final class Store {
    public static final String DEFAULT_JAR_DIR = "C:\\SDOP\\APP\\jar";

    private final File dbFile;
    private Connection conn;

    public Store(File dbFile) {
        this.dbFile = dbFile;
    }

    public synchronized void init(File seed) throws Exception {
        Class.forName("org.sqlite.JDBC");
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("DB 폴더를 만들지 못했습니다: " + parent.getAbsolutePath());
        }
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/');
        conn = DriverManager.getConnection(url);
        Statement st = conn.createStatement();
        try {
            st.execute("CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY, value TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS app ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, http_port INTEGER NOT NULL, "
                    + "ws_port INTEGER NOT NULL, jmx_port INTEGER NOT NULL, process_name TEXT NOT NULL, "
                    + "bat_path TEXT NOT NULL, command_hint TEXT, jar_source TEXT, sort_no INTEGER NOT NULL)");
        } finally {
            st.close();
        }
        if (countApps() == 0 && seed != null && seed.isFile()) {
            save(DEFAULT_JAR_DIR, readSeed(seed));
        } else if (jarDir() == null) {
            putSetting("jarDir", DEFAULT_JAR_DIR);
        }
    }

    public synchronized String jarDir() throws Exception {
        PreparedStatement ps = conn.prepareStatement("SELECT value FROM setting WHERE key = ?");
        try {
            ps.setString(1, "jarDir");
            ResultSet rs = ps.executeQuery();
            try {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    public synchronized List<AppDef> apps() throws Exception {
        PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, http_port, ws_port, jmx_port, process_name, bat_path, command_hint, jar_source "
                        + "FROM app ORDER BY sort_no, id");
        try {
            ResultSet rs = ps.executeQuery();
            try {
                List<AppDef> list = new ArrayList<AppDef>();
                while (rs.next()) {
                    list.add(new AppDef(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getInt(3),
                            rs.getInt(4),
                            rs.getInt(5),
                            rs.getString(6),
                            rs.getString(7),
                            rs.getString(8) == null ? "" : rs.getString(8),
                            rs.getString(9) == null ? "" : rs.getString(9)
                    ));
                }
                return list;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    public synchronized void save(String jarDir, List<AppDef> apps) throws Exception {
        boolean prev = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            putSetting("jarDir", jarDir == null ? "" : jarDir);
            Statement del = conn.createStatement();
            try {
                del.execute("DELETE FROM app");
            } finally {
                del.close();
            }
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO app (id, name, http_port, ws_port, jmx_port, process_name, bat_path, command_hint, jar_source, sort_no) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)");
            try {
                for (int i = 0; i < apps.size(); i++) {
                    AppDef app = apps.get(i);
                    ps.setString(1, app.id);
                    ps.setString(2, app.name);
                    ps.setInt(3, app.httpPort);
                    ps.setInt(4, app.wsPort);
                    ps.setInt(5, app.jmxPort);
                    ps.setString(6, app.processName);
                    ps.setString(7, app.batPath);
                    ps.setString(8, app.commandHint);
                    ps.setString(9, app.jarSource);
                    ps.setInt(10, i);
                    ps.addBatch();
                }
                ps.executeBatch();
            } finally {
                ps.close();
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prev);
        }
    }

    private int countApps() throws Exception {
        Statement st = conn.createStatement();
        try {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM app");
            try {
                rs.next();
                return rs.getInt(1);
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
    }

    private void putSetting(String key, String value) throws Exception {
        PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO setting (key, value) VALUES (?, ?)");
        try {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } finally {
            ps.close();
        }
    }

    private static List<AppDef> readSeed(File file) throws Exception {
        List<AppDef> list = new ArrayList<AppDef>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.charAt(0) == '#') {
                    continue;
                }
                String[] p = trimmed.split("\\|", -1);
                if (p.length < 9) {
                    throw new IllegalArgumentException("apps.properties 형식 오류: " + trimmed);
                }
                list.add(new AppDef(
                        p[0].trim(),
                        p[1].trim(),
                        Integer.parseInt(p[2].trim()),
                        Integer.parseInt(p[3].trim()),
                        Integer.parseInt(p[4].trim()),
                        p[5].trim(),
                        p[6].trim().replace("\\\\", "\\"),
                        p[7].trim(),
                        p[8].trim().replace("\\\\", "\\")
                ));
            }
        } finally {
            reader.close();
        }
        return list;
    }
}
