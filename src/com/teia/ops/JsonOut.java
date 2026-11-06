package com.teia.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class JsonOut {
    private JsonOut() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("rawtypes")
    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append(Json.quote((String) value));
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            sb.append(number((Number) value));
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Object e : ((Map) value).entrySet()) {
                Map.Entry entry = (Map.Entry) e;
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(Json.quote(String.valueOf(entry.getKey()))).append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection) {
            writeArray(sb, (Collection) value);
        } else if (value instanceof Object[]) {
            List<Object> list = new ArrayList<Object>();
            Object[] arr = (Object[]) value;
            for (int i = 0; i < arr.length; i++) {
                list.add(arr[i]);
            }
            writeArray(sb, list);
        } else {
            sb.append(Json.quote(String.valueOf(value)));
        }
    }

    @SuppressWarnings("rawtypes")
    private static void writeArray(StringBuilder sb, Collection values) {
        sb.append('[');
        boolean first = true;
        for (Object item : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            write(sb, item);
        }
        sb.append(']');
    }

    private static String number(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return "null";
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(n.longValue());
    }
}
