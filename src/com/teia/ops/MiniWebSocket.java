package com.teia.ops;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.xml.bind.DatatypeConverter;

/**
 * JDK 8 순수 소켓 웹소켓 클라이언트.
 * 운영 앱의 SOCKET_SET 채널(ws://host:2999x/)에 텍스트 한 프레임을 보내고 응답을 받는다.
 * permessage-deflate 는 협상하지 않는다.
 */
public final class MiniWebSocket {
    private static final SecureRandom RANDOM = new SecureRandom();

    public String request(String host, int port, String payload, String execute, int timeoutMs) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 1500);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] key = new byte[16];
            RANDOM.nextBytes(key);
            String keyB64 = DatatypeConverter.printBase64Binary(key);
            String req = "GET / HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + keyB64 + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            out.write(req.getBytes("UTF-8"));
            out.flush();
            String header = readHttpHeader(in);
            if (header.indexOf(" 101 ") < 0 && header.indexOf("101 ") != 0 && !header.startsWith("HTTP/1.1 101")) {
                throw new IllegalStateException("websocket upgrade 실패: " + firstLine(header));
            }
            writeText(out, payload);
            StringBuilder acc = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String text = readText(in, out);
                if (text == null) {
                    break;
                }
                acc.append(text);
                if (execute == null || text.indexOf(execute) >= 0) {
                    return text;
                }
            }
            if (acc.length() == 0) {
                throw new IllegalStateException("응답 없음");
            }
            return acc.toString();
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String firstLine(String header) {
        int n = header.indexOf('\n');
        return n < 0 ? header : header.substring(0, n).trim();
    }

    private static String readHttpHeader(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = 0;
        int pprev = 0;
        int ppprev = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buf.write(b);
            if (ppprev == '\r' && pprev == '\n' && prev == '\r' && b == '\n') {
                break;
            }
            ppprev = pprev;
            pprev = prev;
            prev = b;
            if (buf.size() > 8192) {
                break;
            }
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    private static void writeText(OutputStream out, String payload) throws Exception {
        byte[] data = payload.getBytes("UTF-8");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        int len = data.length;
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((l >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < data.length; i++) {
            frame.write(data[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private static String readText(InputStream in, OutputStream out) throws Exception {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                return msg.size() == 0 ? null : new String(msg.toByteArray(), "UTF-8");
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            int rsv = b0 & 0x70;
            if (rsv != 0) {
                throw new IllegalStateException("압축 프레임은 지원하지 않습니다");
            }
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | (in.read() & 0xFF);
                }
            }
            if (len > 8L * 1024L * 1024L) {
                throw new IllegalStateException("프레임이 너무 큽니다");
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] data = new byte[(int) len];
            readFully(in, data);
            if (masked) {
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) (data[i] ^ mask[i & 3]);
                }
            }
            if (opcode == 0x8) {
                return msg.size() == 0 ? null : new String(msg.toByteArray(), "UTF-8");
            }
            if (opcode == 0x9) {
                writePong(out, data);
                continue;
            }
            if (opcode == 0xA || opcode == 0x2) {
                continue;
            }
            if (opcode == 0x1 || opcode == 0x0) {
                msg.write(data);
            }
            if (fin && (opcode == 0x1 || opcode == 0x0)) {
                return new String(msg.toByteArray(), "UTF-8");
            }
        }
    }

    private static void writePong(OutputStream out, byte[] data) throws Exception {
        byte[] payload = data == null ? new byte[0] : data;
        if (payload.length > 125) {
            payload = Arrays.copyOf(payload, 125);
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x8A);
        frame.write(0x80 | payload.length);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IllegalStateException("연결이 중간에 끊겼습니다");
            }
            off += n;
        }
    }
}
