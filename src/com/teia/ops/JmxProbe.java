package com.teia.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public final class JmxProbe {
    public Map<String, Object> read(int port) {
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("up", Boolean.FALSE);
        if (!PortProbe.open("127.0.0.1", port, 400)) {
            out.put("error", "jmx port closed");
            return out;
        }
        JMXConnector connector = null;
        try {
            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
            Map<String, Object> env = new HashMap<String, Object>();
            env.put("jmx.remote.x.request.waiting.timeout", Long.valueOf(2500L));
            connector = JMXConnectorFactory.connect(url, env);
            MBeanServerConnection mb = connector.getMBeanServerConnection();
            out.put("up", Boolean.TRUE);
            ObjectName runtime = new ObjectName("java.lang:type=Runtime");
            ObjectName memory = new ObjectName("java.lang:type=Memory");
            ObjectName thread = new ObjectName("java.lang:type=Threading");
            ObjectName os = new ObjectName("java.lang:type=OperatingSystem");
            out.put("uptime", mb.getAttribute(runtime, "Uptime"));
            out.put("vm", String.valueOf(mb.getAttribute(runtime, "VmName")));
            out.put("startTime", mb.getAttribute(runtime, "StartTime"));
            CompositeData heap = (CompositeData) mb.getAttribute(memory, "HeapMemoryUsage");
            out.put("heapUsed", heap.get("used"));
            out.put("heapCommitted", heap.get("committed"));
            out.put("heapMax", heap.get("max"));
            out.put("threads", mb.getAttribute(thread, "ThreadCount"));
            out.put("peakThreads", mb.getAttribute(thread, "PeakThreadCount"));
            out.put("daemonThreads", mb.getAttribute(thread, "DaemonThreadCount"));
            putCpu(out, mb, os, "ProcessCpuLoad", "procCpu");
            putCpu(out, mb, os, "SystemCpuLoad", "sysCpu");
        } catch (Exception e) {
            out.put("up", Boolean.FALSE);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connector != null) {
                try {
                    connector.close();
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    private void putCpu(Map<String, Object> out, MBeanServerConnection mb, ObjectName os, String attr, String key) {
        try {
            Object v = mb.getAttribute(os, attr);
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (d >= 0) {
                    out.put(key, Double.valueOf(d * 100.0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static final class PortProbe {
        private PortProbe() {}

        public static boolean open(String host, int port, int timeoutMs) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        public static boolean httpResponds(String host, int port, int timeoutMs) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                OutputStream out = socket.getOutputStream();
                String req = "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes("US-ASCII"));
                out.flush();
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[12];
                int got = 0;
                while (got < 5) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n < 0) {
                        break;
                    }
                    got += n;
                }
                if (got < 5) {
                    return false;
                }
                String line = new String(buf, 0, got, "US-ASCII");
                return line.startsWith("HTTP/");
            } catch (IOException e) {
                return false;
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        public static Set<Integer> listeningPorts() {
            Set<Integer> ports = new HashSet<Integer>();
            String text = ProcessProbe.run("netstat", "-ano", "-p", "tcp");
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim();
                if (t.length() == 0) {
                    continue;
                }
                String upper = t.toUpperCase();
                if (upper.indexOf("LISTENING") < 0 && t.indexOf("수신") < 0) {
                    continue;
                }
                String[] tok = t.split("\\s+");
                if (tok.length < 2) {
                    continue;
                }
                String local = tok[1];
                int colon = local.lastIndexOf(':');
                if (colon < 0) {
                    continue;
                }
                try {
                    ports.add(Integer.valueOf(Integer.parseInt(local.substring(colon + 1))));
                } catch (NumberFormatException ignored) {
                }
            }
            return ports;
        }
    }
}
