package com.patelheet.capitalengine.dashboard.web;

import java.util.Collection;
import java.util.Map;

/** Writes maps, lists, strings, numbers, booleans and null as JSON. Non-finite numbers become null (JSON has no NaN). */
public final class Json {
    private Json() {}

    public static String write(Object o) { StringBuilder sb = new StringBuilder(); write(sb, o); return sb.toString(); }

    private static void write(StringBuilder sb, Object o) {
        switch (o) {
            case null -> sb.append("null");
            case String s -> str(sb, s);
            case Boolean b -> sb.append(b);
            case Double v -> num(sb, v);
            case Float v -> num(sb, v);
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    str(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object x : c) { if (!first) sb.append(','); first = false; write(sb, x); }
                sb.append(']');
            }
            case double[] a -> {
                sb.append('[');
                for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); num(sb, a[i]); }
                sb.append(']');
            }
            default -> str(sb, o.toString());
        }
    }

    private static void num(StringBuilder sb, double v) {
        if (!Double.isFinite(v)) sb.append("null");
        else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append((long) v);
        else sb.append(v);
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // < > & escaped too, so a string can never close a <script> or start markup if a page ever inlines it
                default -> { if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
