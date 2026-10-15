package io.github.abysstudio.mingui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Buddy accessories as data (res/raw/accessories.json; the format is described at the top of that file).
 * A pack file named {@value #PACK} in the app's files folder is merged on top by id, so new accessories
 * can be added without changing code. Index 0 of {@link #all} is always "none".
 */
final class Accessories {
    static final String PACK = "accessories.json";

    private Accessories() {
    }

    /** One accessory: how to unlock it and what to draw. */
    static final class Def {
        final String id;
        final Map<String, String> names;
        final int streak;
        /** yyyy-MM-dd before which it isn't offered at all; null = always. */
        final String from;
        final boolean hat;
        final boolean eyes;
        final float rotDeg, rotX, rotY;
        final List<Layer> layers;

        Def(String id, Map<String, String> names, int streak, String from, boolean hat, boolean eyes,
            float rotDeg, float rotX, float rotY, List<Layer> layers) {
            this.id = id;
            this.names = names;
            this.streak = streak;
            this.from = from;
            this.hat = hat;
            this.eyes = eyes;
            this.rotDeg = rotDeg;
            this.rotX = rotX;
            this.rotY = rotY;
            this.layers = layers;
        }

        String name(Context c) {
            String lang = c.getResources().getConfiguration().getLocales().get(0).getLanguage();
            String n = names.get(lang);
            if (n == null) n = names.get("en");
            return n != null ? n : id;
        }
    }

    /** A coordinate: dp + multiples of the buddy's live measurements. */
    static final class Num {
        final float dp, bw, bh, ex, lk;

        Num(float dp, float bw, float bh, float ex, float lk) {
            this.dp = dp;
            this.bw = bw;
            this.bh = bh;
            this.ex = ex;
            this.lk = lk;
        }

        float px(Ctx k) {
            return dp * k.d + bw * k.bodyW + bh * k.bodyH + ex * k.eyeDx + lk * k.look;
        }
    }

    static final class Layer {
        String type;
        Num[] v;
        float start, sweep, radius, stroke;
        String color;
        boolean clip;
        Path path;
        /** {@link #path} scaled to pixels, kept until the scale changes. */
        Path scaled;
        float scaledD;
    }

    /** The buddy's measurements at draw time, in pixels. */
    static final class Ctx {
        float d, cx, top, eyeY, bodyW, bodyH, eyeDx, look;
    }

    private static List<Def> cache;
    private static String cacheDay;
    private static Locale cacheLocale;

    /** Everything on offer today: "none" first, then the file's order. */
    static synchronized List<Def> all(Context c) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Locale loc = c.getResources().getConfiguration().getLocales().get(0);
        if (cache != null && today.equals(cacheDay) && loc.equals(cacheLocale)) return cache;
        LinkedHashMap<String, Def> byId = new LinkedHashMap<>();
        try (InputStream in = c.getResources().openRawResource(R.raw.accessories)) {
            parse(read(in), byId);
        } catch (IOException | JSONException | RuntimeException ignored) {
            // A broken bundled file would be a build bug; the buddy just goes without.
        }
        File pack = new File(c.getFilesDir(), PACK);
        if (pack.isFile()) {
            try (InputStream in = new FileInputStream(pack)) {
                parse(read(in), byId);
            } catch (IOException | JSONException | RuntimeException ignored) {
                // A bad pack is skipped; the bundled set still works.
            }
        }
        List<Def> list = new ArrayList<>();
        list.add(new Def("none", Collections.emptyMap(), 0, null, false, false, 0, 0, 0,
                Collections.emptyList()));
        for (Def a : byId.values()) {
            if (a.from == null || a.from.compareTo(today) <= 0) list.add(a);
        }
        cache = Collections.unmodifiableList(list);
        cacheDay = today;
        cacheLocale = loc;
        return cache;
    }

    static synchronized void invalidate() {
        cache = null;
    }

    static int indexOf(Context c, String id) {
        List<Def> all = all(c);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) return i;
        return 0;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void parse(String json, Map<String, Def> into) throws JSONException {
        JSONArray arr = new JSONObject(json).getJSONArray("accessories");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String id = o.getString("id");
            if (id.equals("none")) continue;
            Map<String, String> names = new HashMap<>();
            JSONObject nm = o.optJSONObject("name");
            if (nm != null) {
                for (java.util.Iterator<String> it = nm.keys(); it.hasNext(); ) {
                    String k = it.next();
                    names.put(k, nm.getString(k));
                }
            }
            JSONObject rot = o.optJSONObject("rotate");
            List<Layer> layers = new ArrayList<>();
            JSONArray la = o.getJSONArray("layers");
            for (int j = 0; j < la.length(); j++) {
                Layer l = layer(la.getJSONObject(j));
                if (l != null) layers.add(l);
            }
            into.remove(id); // a pack replacing an id also takes its place at the end
            into.put(id, new Def(id, names, Math.max(0, o.optInt("streak", 0)),
                    o.has("from") ? o.getString("from") : null, o.optBoolean("hat", false),
                    "eyes".equals(o.optString("anchor", "head")),
                    rot == null ? 0 : (float) rot.optDouble("deg", 0),
                    rot == null ? 0 : (float) rot.optDouble("x", 0),
                    rot == null ? 0 : (float) rot.optDouble("y", 0), layers));
        }
    }

    private static final String[] KEYS_CIRCLE = {"x", "y", "r"}, KEYS_LINE = {"x1", "y1", "x2", "y2"},
            KEYS_BOX = {"l", "t", "r", "b"};

    private static Layer layer(JSONObject o) throws JSONException {
        Layer l = new Layer();
        l.type = o.getString("type");
        String[] keys;
        switch (l.type) {
            case "circle": keys = KEYS_CIRCLE; break;
            case "line": keys = KEYS_LINE; break;
            case "rect": case "rrect": case "arc": keys = KEYS_BOX; break;
            case "path": keys = new String[0]; l.path = svgPath(o.getString("d")); break;
            default: return null; // unknown shapes from newer packs are skipped
        }
        l.v = new Num[keys.length];
        for (int i = 0; i < keys.length; i++) l.v[i] = num(o.opt(keys[i]));
        l.start = (float) o.optDouble("start", 0);
        l.sweep = (float) o.optDouble("sweep", 360);
        l.radius = (float) o.optDouble("radius", 0);
        l.stroke = (float) o.optDouble("stroke", 0);
        l.color = o.optString("color", "#FFFFFF");
        l.clip = o.optBoolean("clip", false);
        return l;
    }

    /** "-1bw+1" -> -1 x body half-width + 1dp. Plain numbers are dp. */
    static Num num(Object v) {
        if (v == null) return new Num(0, 0, 0, 0, 0);
        if (v instanceof Number) return new Num(((Number) v).floatValue(), 0, 0, 0, 0);
        String s = v.toString().replace(" ", "");
        float[] k = new float[5];
        int i = 0;
        while (i < s.length()) {
            int startTerm = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            String numPart = s.substring(startTerm, i);
            float coef = numPart.isEmpty() || numPart.equals("+") ? 1
                    : numPart.equals("-") ? -1 : Float.parseFloat(numPart);
            String unit = i + 2 <= s.length() ? s.substring(i, i + 2) : "";
            int slot = unit.equals("bw") ? 1 : unit.equals("bh") ? 2 : unit.equals("ex") ? 3
                    : unit.equals("lk") ? 4 : 0;
            if (slot > 0) i += 2;
            k[slot] += coef;
            if (i == startTerm) throw new IllegalArgumentException("Bad coordinate: " + s);
        }
        return new Num(k[0], k[1], k[2], k[3], k[4]);
    }

    /** Minimal SVG path parser (M L H V Q C Z, absolute and relative), in dp around the anchor. */
    static Path svgPath(String d) {
        Path p = new Path();
        List<String> tok = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[MmLlHhVvQqCcZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").matcher(d);
        while (m.find()) tok.add(m.group());
        float x = 0, y = 0, sx = 0, sy = 0;
        char cmd = 'M';
        int i = 0;
        while (i < tok.size()) {
            String t = tok.get(i);
            if (Character.isLetter(t.charAt(0))) {
                cmd = t.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.close();
                    x = sx;
                    y = sy;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + f(tok, i++);
                    y = oy + f(tok, i++);
                    p.moveTo(x, y);
                    sx = x;
                    sy = y;
                    cmd = rel ? 'l' : 'L'; // further pairs are line-tos
                    break;
                case 'L':
                    x = ox + f(tok, i++);
                    y = oy + f(tok, i++);
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + f(tok, i++);
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + f(tok, i++);
                    p.lineTo(x, y);
                    break;
                case 'Q': {
                    float x1 = ox + f(tok, i++), y1 = oy + f(tok, i++);
                    x = ox + f(tok, i++);
                    y = oy + f(tok, i++);
                    p.quadTo(x1, y1, x, y);
                    break;
                }
                case 'C': {
                    float x1 = ox + f(tok, i++), y1 = oy + f(tok, i++);
                    float x2 = ox + f(tok, i++), y2 = oy + f(tok, i++);
                    x = ox + f(tok, i++);
                    y = oy + f(tok, i++);
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported path command: " + cmd);
            }
        }
        return p;
    }

    private static float f(List<String> tok, int i) {
        return Float.parseFloat(tok.get(i));
    }

    static int color(String s) {
        switch (s) {
            case "accent": return MainActivity.ACCENT;
            case "accent2": return MainActivity.ACCENT2;
            default:
                try {
                    return Color.parseColor(s);
                } catch (IllegalArgumentException e) {
                    return Color.WHITE;
                }
        }
    }

    private static final Matrix M = new Matrix();
    private static final RectF BOX = new RectF();

    /** Draws {@code a} on the buddy described by {@code k}. Main thread only (shared scratch objects). */
    static void draw(Canvas c, Paint p, Def a, Ctx k) {
        if (a == null || a.layers.isEmpty()) return;
        float ax = k.cx, ay = a.eyes ? k.eyeY : k.top;
        c.save();
        c.translate(ax, ay);
        if (a.rotDeg != 0) c.rotate(a.rotDeg, a.rotX * k.d, a.rotY * k.d);
        Path lastPath = null;
        for (Layer l : a.layers) {
            p.setColor(color(l.color));
            if (l.stroke > 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(l.stroke * k.d);
                p.setStrokeCap(Paint.Cap.ROUND);
            } else {
                p.setStyle(Paint.Style.FILL);
            }
            boolean clipped = l.clip && lastPath != null;
            if (clipped) {
                c.save();
                c.clipPath(lastPath);
            }
            switch (l.type) {
                case "circle":
                    c.drawCircle(l.v[0].px(k), l.v[1].px(k), l.v[2].px(k), p);
                    break;
                case "line":
                    c.drawLine(l.v[0].px(k), l.v[1].px(k), l.v[2].px(k), l.v[3].px(k), p);
                    break;
                case "rect":
                case "rrect":
                case "arc":
                    BOX.set(l.v[0].px(k), l.v[1].px(k), l.v[2].px(k), l.v[3].px(k));
                    if (l.type.equals("rect")) c.drawRect(BOX, p);
                    else if (l.type.equals("rrect")) c.drawRoundRect(BOX, l.radius * k.d, l.radius * k.d, p);
                    else c.drawArc(BOX, l.start, l.sweep, false, p);
                    break;
                case "path": {
                    if (l.scaled == null || l.scaledD != k.d) {
                        M.setScale(k.d, k.d);
                        l.scaled = new Path();
                        l.path.transform(M, l.scaled);
                        l.scaledD = k.d;
                    }
                    c.drawPath(l.scaled, p);
                    lastPath = l.scaled;
                    break;
                }
                default:
                    break;
            }
            if (clipped) c.restore();
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }
}
