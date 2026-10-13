package pl.anarchia.autorynek;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import net.minecraft.class_7923;
import org.lwjgl.glfw.GLFW;

/**
 * Menu moda (/autorynek) - ciemny, płaski wygląd, trzy zakładki:
 * Moje wpisy / Eventówki Anarchii / Wszystkie itemy z wyszukiwarką.
 * Przyciski są prawdziwymi przyciskami gry (obsługa kliknięć), ale przykryte własną grafiką.
 */
public class AutoRynekScreen extends class_437 {

    // paleta: ciemny, mocno fioletowy motyw
    private static final int C_OVERLAY = 0xD0080212;
    private static final int C_PANEL_TOP = 0xFF1A0B33;
    private static final int C_PANEL = 0xFF0F0620;
    private static final int C_PANEL_BOT = 0xFF0A0415;
    private static final int C_BORDER = 0xFF5B21B6;
    private static final int C_HEADER_TOP = 0xFF3B1480;
    private static final int C_HEADER = 0xFF150829;
    private static final int C_ROW = 0xFF1A0E30;
    private static final int C_ROW_ALT = 0xFF150A28;
    private static final int C_ROW_HOVER = 0xFF2A1650;
    private static final int C_BTN = 0xFF2E1A5E;
    private static final int C_ACCENT = 0xFF8B3DFF;
    private static final int C_ACCENT_L = 0xFFC4A1FF;
    private static final int C_GREEN = 0xFF16A34A;
    private static final int C_RED = 0xFFDC2626;
    private static final int C_TEXT = 0xFFF5F0FF;
    private static final int C_MUTED = 0xFF9B8DC4;
    private static final int C_WARN = 0xFFFBBF24;

    private static final int ROW_H = 26;
    private static final int BTN_H = 22;

    private static final int TAB_MY = 0, TAB_EVENT = 1, TAB_ITEMS = 2;

    private static final class Skin {
        int x, y, w, h, color, textColor = C_TEXT;
        String label;
        boolean active;
        boolean flat;
        Runnable action;
        Anim anim;
        /** Dla przycisków "+ Dodaj" w wierszach - stan przełącznika liczony na bieżąco. */
        BooleanSupplier toggled;
        /** Numer wiersza dla przycisków "+ Dodaj" (-1 = zwykły przycisk). */
        int row = -1;
    }

    /** Stan animacji pojedynczego przycisku - przeżywa przebudowę ekranu (klucz = pozycja). */
    private static final class Anim {
        float hover;
        float press;
        float rx, ry;
        int color;
        boolean init;
    }

    private static final class ItemRow {
        final String id;
        final String display;
        final class_1792 item;

        ItemRow(String id, String display, class_1792 item) {
            this.id = id;
            this.display = display;
            this.item = item;
        }
    }

    private static List<ItemRow> allItems;
    private static boolean fillBroken = false;
    private static boolean iconBroken = false;

    private final AutoRynekConfig cfg = AutoRynekConfig.get();
    private final List<Skin> skins = new ArrayList<>();

    private int tab = TAB_MY;
    private int page = 0;
    private String searchValue = "";
    private String addPriceValue = "";
    private String statusText = "";
    private int statusColor = C_MUTED;
    private List<ItemRow> results = new ArrayList<>();

    private final Map<Long, Anim> anims = new HashMap<>();
    private final float[] rowHover = new float[256];
    private final List<int[]> fieldBoxes = new ArrayList<>();
    private final List<class_342> fieldWidgets = new ArrayList<>();
    private final long openStart = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private float indX = -1, indW = 0;
    private int indTargetX = 0, indTargetW = 0;
    private int lastMouseX = 0, lastMouseY = 0;
    private static float gAlpha = 1f;
    private int lastSig = -1;
    private long contentStartNs = System.nanoTime();

    private int px, py, pw, ph, listTop, perPage;
    private long lastAct = 0;
    private boolean prevDown = false;
    private static boolean pollBroken = false;

    public AutoRynekScreen() {
        super(class_2561.method_43470("Anarchia AutoRynek"));
        ensureItems();
        refilter();
    }

    // ---------- dane ----------

    private static Map<String, class_1792> itemsById = new HashMap<>();

    /** Ikona dla wpisu z listy: "id:minecraft:..." albo nazwa eventówki (ikona z EventItems). Null = brak. */
    private static class_1792 iconForEntry(String name) {
        if (name == null) return null;
        if (name.startsWith("id:")) {
            return itemsById.get(name.substring(3).trim());
        }
        for (String[] ev : EventItems.ALL) {
            if (ev[0].equalsIgnoreCase(name)) {
                return itemsById.get("minecraft:" + ev[2]);
            }
        }
        return null;
    }

    private static synchronized void ensureItems() {
        if (allItems != null) {
            return;
        }
        List<ItemRow> out = new ArrayList<>();
        try {
            for (class_1792 it : class_7923.field_41178) {
                String id = class_7923.field_41178.method_10221(it).toString();
                if (id.equals("minecraft:air")) {
                    continue;
                }
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                out.add(new ItemRow(id, pretty(path), it));
            }
        } catch (Throwable t) {
            AutoRynekClient.LOGGER.warn("Nie udało się wczytać listy przedmiotów: {}", t.toString());
        }
        out.sort((a, b) -> a.display.compareToIgnoreCase(b.display));
        Map<String, class_1792> map = new HashMap<>();
        for (ItemRow r : out) {
            map.put(r.id, r.item);
        }
        itemsById = map;
        allItems = out;
    }

    private static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private void refilter() {
        String q = MarketScanner.norm(searchValue);
        List<ItemRow> out = new ArrayList<>();
        String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");
        for (ItemRow r : allItems) {
            String hay = (r.display + " " + r.id).toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String t : tokens) {
                if (!hay.contains(t)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(r);
        }
        results = out;
    }

    private AutoRynekConfig.Entry find(String name) {
        for (AutoRynekConfig.Entry e : cfg.entries) {
            if (e.name.equalsIgnoreCase(name)) return e;
        }
        return null;
    }

    private double addPrice() {
        if (addPriceValue.trim().isEmpty()) return 0;
        OptionalDouble v = PriceParser.parseUserInput(addPriceValue);
        return v.isPresent() ? v.getAsDouble() : -1;
    }

    private void toggleEntry(String name, String category) {
        AutoRynekConfig.Entry ex = find(name);
        if (ex != null) {
            cfg.entries.remove(ex);
            AutoRynekConfig.save();
            setStatus("Usunięto z listy: " + shortName(name), C_MUTED);
            return;
        }
        double price = addPrice();
        if (price < 0) {
            setStatus("Niepoprawna cena. Przykłady: 1500, 2,5k, 1m.", C_RED);
            return;
        }
        if (price <= 0) {
            setStatus("Wpisz cenę przedmiotu (np. 5k) w polu obok, zanim go dodasz.", C_RED);
            return;
        }
        cfg.entries.add(new AutoRynekConfig.Entry(name, price, category));
        AutoRynekConfig.save();
        setStatus("Dodano: " + shortName(name) + " (maks. " + PriceParser.format(price) + ")", C_GREEN);
    }

    private static String shortName(String n) {
        return n.startsWith("id:") ? pretty(n.substring(n.indexOf(':', 3) + 1)) : n;
    }

    private int listSize() {
        switch (tab) {
            case TAB_EVENT:
                return EventItems.ALL.length;
            case TAB_ITEMS:
                return results.size();
            default:
                return cfg.entries.size();
        }
    }

    // ---------- układ ----------

    /** Akcja wywoływana zarówno z przycisku gry, jak i z własnego wykrywania kliknięć - bez podwójnego wykonania. */
    private Runnable guard(Runnable real, Anim anim, int bx, int by) {
        return () -> {
            long now = System.nanoTime();
            if (now - lastAct < 180_000_000L) {
                return;
            }
            lastAct = now;
            anim.press = 1f;
            anim.rx = lastMouseX - bx;
            anim.ry = lastMouseY - by;
            real.run();
        };
    }

    private Anim anim(int x, int y) {
        long key = ((long) x << 32) | (y & 0xFFFFFFFFL);
        return anims.computeIfAbsent(key, k -> new Anim());
    }

    private Skin btn(int x, int y, int w, int h, String label, int color, Runnable action0) {
        Anim an = anim(x, y);
        Runnable action = guard(action0, an, x, y);
        class_4185 b = class_4185.method_46430(class_2561.method_43470(""), bb -> action.run())
                .method_46434(x, y, w, h).method_46431();
        method_37063(b);
        Skin s = new Skin();
        s.x = x;
        s.y = y;
        s.w = w;
        s.h = h;
        s.label = label;
        s.color = color;
        s.action = action;
        s.anim = an;
        skins.add(s);
        return s;
    }

    private Skin lastSkin() {
        return skins.get(skins.size() - 1);
    }

    private void tabBtn(int x, int y, int w, String label, int which) {
        btn(x, y, w, 24, label, which == tab ? C_ACCENT : C_BTN, () -> {
            tab = which;
            page = 0;
            method_41843();
        });
        lastSkin().active = which == tab;
        lastSkin().flat = true;
        if (which == tab) {
            indTargetX = x;
            indTargetW = w;
            if (indX < 0) {
                indX = x;
                indW = w;
            }
        }
    }

    private class_342 field(int x, int y, int w, String hint, String value, java.util.function.Consumer<String> onChange) {
        class_342 f = new class_342(this.field_22793, x, y, w, 18, class_2561.method_43470(hint));
        f.method_1880(60);
        f.method_47404(class_2561.method_43470(hint));
        f.method_1852(value);
        f.method_1863(onChange);
        method_37063(f);
        fieldBoxes.add(new int[]{x, y, w, 18});
        fieldWidgets.add(f);
        return f;
    }

    @Override
    protected void method_25426() {
        skins.clear();
        fieldBoxes.clear();
        fieldWidgets.clear();
        pw = Math.min(700, this.field_22789 - 16);
        px = (this.field_22789 - pw) / 2;
        py = 8;
        ph = this.field_22790 - 16;
        int inner = pw - 32;
        int ix = px + 16;

        // nagłówek: jeden przełącznik (włącza najnowsze + siekierkę + snajpera naraz) i tempo
        int bw = 118;
        btn(px + pw - 16 - bw, py + 9, bw, 24, cfg.enabled ? "\u25CF WŁĄCZONY" : "\u25CF WYŁĄCZONY", cfg.enabled ? C_GREEN : C_RED, () -> {
            cfg.enabled = !cfg.enabled;
            if (cfg.enabled) {
                cfg.sniper = true;
                cfg.axeRefresh = true;
                cfg.ensureNewest = true;
            }
            AutoRynekConfig.save();
            method_41843();
        });
        btn(px + pw - 16 - bw - 6 - 124, py + 9, 124, 24, "TEMPO: " + AutoRynekConfig.SPEED_NAMES[cfg.speed], C_BTN, () -> {
            cfg.speed = (cfg.speed + 1) % AutoRynekConfig.SPEED_NAMES.length;
            AutoRynekConfig.save();
            method_41843();
        });

        // zakładki
        int tw = (inner - 8) / 3;
        int ty = py + 52;
        tabBtn(ix, ty, tw, "Moje wpisy (" + cfg.entries.size() + ")", TAB_MY);
        tabBtn(ix + tw + 4, ty, tw, "Eventówki (" + EventItems.ALL.length + ")", TAB_EVENT);
        tabBtn(ix + 2 * (tw + 4), ty, tw, "Wszystkie itemy", TAB_ITEMS);

        // pasek kontrolek zakładki
        int cy = py + 84;
        if (tab == TAB_MY) {
            // ceny ustawia się przy każdym przedmiocie osobno - brak ceny domyślnej
        } else if (tab == TAB_EVENT) {
            field(ix + 150, cy, 90, "np. 5k", addPriceValue, s -> addPriceValue = s);
        } else {
            field(ix, cy, inner - 130, "Szukaj przedmiotu (np. totem, elytra)...", searchValue, s -> {
                searchValue = s;
                page = 0;
                refilter();
            });
            field(ix + inner - 124, cy, 124, "Maks. cena", addPriceValue, s -> addPriceValue = s);
        }

        // szybkie ceny dodawanych przedmiotów (klik = ustawia cenę w polu)
        if (tab == TAB_EVENT) {
            String[] chipLabels = {"1k", "5k", "10k", "50k", "100k", "500k", "1m"};
            OptionalDouble cur = PriceParser.parseUserInput(addPriceValue);
            for (int k = 0; k < chipLabels.length; k++) {
                final String label = chipLabels[k];
                OptionalDouble lv = PriceParser.parseUserInput(label);
                boolean active = cur.isPresent() && lv.isPresent() && Math.abs(lv.getAsDouble() - cur.getAsDouble()) < 0.5;
                btn(ix + 252 + k * 44, cy, 40, 18, label, active ? C_ACCENT : C_BTN, () -> {
                    addPriceValue = label;
                    method_41843();
                });
            }
        }

        // lista
        listTop = py + 112;
        int listBottom = py + ph - 58;
        perPage = Math.max(1, (listBottom - listTop) / ROW_H);
        int total = listSize();
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int start = page * perPage;

        for (int i = 0; i < perPage; i++) {
            final int row = i;
            int y = listTop + i * ROW_H;
            if (tab == TAB_MY) {
                if (start + i >= total) break;
                final int index = start + i;
                final AutoRynekConfig.Entry e = cfg.entries.get(index);
                field(ix + inner - 54 - 6 - 80, y + 4, 80, "cena", e.maxPrice > 0 ? PriceParser.format(e.maxPrice) : "", s -> {
                    if (s.trim().isEmpty()) {
                        e.maxPrice = 0;
                        AutoRynekConfig.save();
                        return;
                    }
                    OptionalDouble v = PriceParser.parseUserInput(s);
                    if (v.isPresent()) {
                        e.maxPrice = v.getAsDouble();
                        AutoRynekConfig.save();
                    }
                });
                btn(ix + inner - 54, y + 2, 54, BTN_H, "Usuń", C_RED, () -> {
                    if (index < cfg.entries.size()) {
                        AutoRynekConfig.Entry removed = cfg.entries.remove(index);
                        AutoRynekConfig.save();
                        setStatus("Usunięto: " + shortName(removed.name), C_MUTED);
                        method_41843();
                    }
                });
            } else {
                if (start + i >= total) break;
                Skin rs = btn(ix + inner - 92, y + 2, 92, BTN_H, "", C_BTN, () -> onRowClick(row));
                rs.row = row;
                rs.toggled = () -> isAdded(row);
            }
        }

        // stopka
        int fy = py + ph - 32;
        btn(ix, fy, 28, BTN_H, "<", C_ACCENT, () -> {
            if (page > 0) {
                page--;
                method_41843();
            }
        });
        btn(ix + 32, fy, 28, BTN_H, ">", C_ACCENT, () -> {
            if (page < pages - 1) {
                page++;
                method_41843();
            }
        });
        btn(ix + inner - 110, fy, 110, BTN_H, "Gotowe", C_ACCENT, this::method_25419);
    }

    private boolean isAdded(int row) {
        int idx = page * perPage + row;
        if (tab == TAB_EVENT) {
            return idx < EventItems.ALL.length && find(EventItems.ALL[idx][0]) != null;
        }
        if (tab == TAB_ITEMS) {
            return idx < results.size() && find("id:" + results.get(idx).id) != null;
        }
        return false;
    }

    private void onRowClick(int row) {
        int idx = page * perPage + row;
        if (tab == TAB_EVENT) {
            if (idx < EventItems.ALL.length) {
                toggleEntry(EventItems.ALL[idx][0], "EVENT");
            }
        } else if (tab == TAB_ITEMS) {
            if (idx < results.size()) {
                toggleEntry("id:" + results.get(idx).id, "EVENT");
            }
        }
    }

    private void setStatus(String text, int color) {
        statusText = text;
        statusColor = color;
    }

    // ---------- rysowanie ----------

    /** Mnoży kanał alfa koloru przez globalną przezroczystość (animacja pojawiania się menu). */
    private static int ca(int c) {
        if (gAlpha >= 0.999f) return c;
        int a = (int) (((c >>> 24) & 0xFF) * gAlpha);
        return (a << 24) | (c & 0xFFFFFF);
    }

    private static void fillRaw(class_332 c, int x1, int y1, int x2, int y2, int color) {
        if (fillBroken || x2 <= x1 || y2 <= y1) return;
        try {
            c.method_25294(x1, y1, x2, y2, ca(color));
        } catch (Throwable t) {
            fillBroken = true;
            AutoRynekClient.LOGGER.warn("fill niedostepne: {}", t.toString());
        }
    }

    /** Przezroczystość wiersza podczas animacji pojawiania się listy (kolejne wiersze wchodzą z opóźnieniem). */
    private float rowFade(int row, float contentT) {
        if (tab == TAB_MY) {
            return 1f; // w tej zakładce wiersze mają pola tekstowe, których nie da się wygasić
        }
        float t = Math.max(0f, Math.min(1f, (contentT - row * 0.028f) / 0.16f));
        return 1f - (1f - t) * (1f - t);
    }

    private static float lerpTo(float cur, float target, float dt, float speed) {
        return cur + (target - cur) * Math.min(1f, dt * speed);
    }

    private static int mix(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) * (1 - t) + ((c2 >>> 24) & 0xFF) * t);
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int darken(int c, float t) {
        return mix(c, 0xFF000000 | (c & 0xFF000000), t);
    }

    private static int lighten(int c) {
        int a = c & 0xFF000000;
        int r = Math.min(255, ((c >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 28);
        int b = Math.min(255, (c & 0xFF) + 28);
        return a | (r << 16) | (g << 8) | b;
    }

    /** Pionowy gradient z zaokrąglonymi rogami (promień 2 px) - linia po linii. */
    private static void rrGradEx(class_332 c, int x1, int y1, int x2, int y2, int top, int bottom, boolean roundTop, boolean roundBottom) {
        int h = y2 - y1;
        if (h <= 0) return;
        for (int i = 0; i < h; i++) {
            int inset = 0;
            if (roundTop && i == 0) inset = 2;
            else if (roundTop && i == 1) inset = 1;
            if (roundBottom && i == h - 1) inset = 2;
            else if (roundBottom && i == h - 2) inset = 1;
            fillRaw(c, x1 + inset, y1 + i, x2 - inset, y1 + i + 1, mix(top, bottom, i / (float) h));
        }
    }

    private static void rrGrad(class_332 c, int x1, int y1, int x2, int y2, int top, int bottom) {
        rrGradEx(c, x1, y1, x2, y2, top, bottom, true, true);
    }

    /** Jednokolorowy prostokąt z zaokrąglonymi rogami. */
    private static void rr(class_332 c, int x1, int y1, int x2, int y2, int color) {
        rrGradEx(c, x1, y1, x2, y2, color, color, true, true);
    }

    /** Poziomy gradient (segmentami). */
    private static void hgrad(class_332 c, int x1, int y1, int x2, int y2, int left, int right) {
        int w = Math.max(1, x2 - x1);
        int seg = Math.max(2, w / 48);
        for (int x = 0; x < w; x += seg) {
            fillRaw(c, x1 + x, y1, Math.min(x2, x1 + x + seg), y2, mix(left, right, x / (float) w));
        }
    }

    /** Jasny "blask" przesuwający się po linii pod nagłówkiem. */
    private static void shimmer(class_332 c, int x1, int y1, int x2, int y2, float tSec) {
        int w = x2 - x1;
        if (w <= 0) return;
        int len = 90;
        float pos = ((tSec * 0.28f) % 1f) * (w + len) - len / 2f;
        for (int d = 0; d < len; d += 3) {
            int xs = x1 + (int) (pos - len / 2f + d);
            int xe = xs + 3;
            if (xe <= x1 || xs >= x2) continue;
            float k = 1f - Math.abs(d - len / 2f) / (len / 2f);
            int alpha = (int) (150 * k * k);
            fillRaw(c, Math.max(x1, xs), y1, Math.min(x2, xe), y2, (alpha << 24) | 0xF3E8FF);
        }
    }

    private void text(class_332 c, String s, int x, int y, int color) {
        int col = ca(color);
        if (((col >>> 24) & 0xFF) < 6) return; // przy alfa < 4 gra rysuje tekst w pełni widoczny
        c.method_27535(this.field_22793, class_2561.method_43470(s), x, y, col);
    }

    private String trim(String s, int width) {
        class_327 f = this.field_22793;
        return f.method_27523(s, Math.max(10, width));
    }

    private void icon(class_332 c, class_1792 item, int x, int y) {
        if (iconBroken) return;
        try {
            c.method_51427(new class_1799(item), x, y);
        } catch (Throwable t) {
            iconBroken = true;
            AutoRynekClient.LOGGER.warn("ikony przedmiotow niedostepne: {}", t.toString());
        }
    }

    /** Rysuje jeden przycisk z animacjami: płynny hover z poświatą, wciśnięcie i fala po kliknięciu. */
    private void drawSkin(class_332 c, Skin s, int mouseX, int mouseY, float dt) {
        Anim a = s.anim;
        boolean hover = mouseX >= s.x && mouseX < s.x + s.w && mouseY >= s.y && mouseY < s.y + s.h;
        a.hover = lerpTo(a.hover, hover ? 1f : 0f, dt, 14f);
        a.press = Math.max(0f, a.press - dt * 4.5f);

        String label = s.label;
        int color = s.color;
        if (s.toggled != null) {
            boolean on = s.toggled.getAsBoolean();
            label = on ? "Dodano  \u2713" : "+ Dodaj";
            color = on ? C_GREEN : C_ACCENT;
        }
        if (!a.init) {
            a.color = color;
            a.init = true;
        } else {
            a.color = mix(a.color, color, Math.min(1f, dt * 10f));
        }

        int textColor = s.textColor;
        int off = 0;

        if (s.flat) {
            // zakładka: płaska, z podkreśleniem rysowanym osobno (płynnie się przesuwa)
            float act = s.active ? 1f : 0f;
            rr(c, s.x, s.y, s.x + s.w, s.y + s.h, mix(0xFF110726, 0xFF24134A, Math.max(act, a.hover * 0.7f)));
            textColor = s.active ? C_TEXT : mix(C_MUTED, C_ACCENT_L, a.hover);
            if (a.press > 0f) {
                rr(c, s.x, s.y, s.x + s.w, s.y + s.h, ((int) (40 * a.press) << 24) | 0xC4A1FF);
            }
        } else {
            off = a.press > 0.55f ? 1 : 0;
            int x = s.x, y = s.y + off, w = s.w, h = s.h;
            int base = a.color;

            if (a.hover > 0.03f) {
                for (int k = 3; k >= 1; k--) {
                    int ga = (int) (34 * a.hover / k);
                    rr(c, x - k, y - k, x + w + k, y + h + k, (ga << 24) | 0x8B3DFF);
                }
            }
            int border = mix(darken(base, 0.35f), C_ACCENT_L, 0.75f * a.hover + 0.25f * a.press);
            rr(c, x, y, x + w, y + h, border);
            int top = mix(lighten(base), lighten(lighten(base)), a.hover);
            int bot = mix(darken(base, 0.18f), base, a.hover);
            rrGrad(c, x + 1, y + 1, x + w - 1, y + h - 1, top, bot);
            fillRaw(c, x + 3, y + 1, x + w - 3, y + 2, mix(top, 0xFFFFFFFF, 0.22f));

            if (a.press > 0f) {
                rr(c, x + 1, y + 1, x + w - 1, y + h - 1, ((int) (70 * a.press) << 24) | 0xFFFFFF);
                float r = (1f - a.press) * Math.max(w, h) * 0.9f + 3f;
                int cx = s.x + (int) a.rx;
                int cy = s.y + (int) a.ry;
                int rx1 = Math.max(x + 1, (int) (cx - r));
                int rx2 = Math.min(x + w - 1, (int) (cx + r));
                int ry1 = Math.max(y + 1, (int) (cy - r));
                int ry2 = Math.min(y + h - 1, (int) (cy + r));
                fillRaw(c, rx1, ry1, rx2, ry2, ((int) (55 * a.press) << 24) | 0xE9D5FF);
            }
        }

        if (label != null && !label.isEmpty()) {
            int tw = this.field_22793.method_27525(class_2561.method_43470(label));
            text(c, label, s.x + (s.w - tw) / 2, s.y + off + (s.h - 8) / 2, textColor);
        }
    }

    /** Dodatkowe wykrywanie kliknięć (GLFW) - działa nawet gdyby przyciski gry nie reagowały. */
    private void pollMouse(int mouseX, int mouseY) {
        if (pollBroken) return;
        try {
            long handle = class_310.method_1551().method_22683().method_4490();
            boolean down = GLFW.glfwGetMouseButton(handle, 0) == 1;
            if (down && !prevDown) {
                for (int i = skins.size() - 1; i >= 0; i--) {
                    Skin s = skins.get(i);
                    if (mouseX >= s.x && mouseX < s.x + s.w && mouseY >= s.y && mouseY < s.y + s.h) {
                        if (s.action != null) s.action.run();
                        break;
                    }
                }
            }
            prevDown = down;
        } catch (Throwable t) {
            pollBroken = true;
            AutoRynekClient.LOGGER.warn("wykrywanie myszy niedostepne: {}", t.toString());
        }
    }

    @Override
    public void method_25394(class_332 context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        float tSec = (now - openStart) / 1_000_000_000f;
        float openT = Math.min(1f, tSec / 0.28f);
        gAlpha = 1f - (float) Math.pow(1f - openT, 3); // płynne pojawianie się menu
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        try {
            drawAll(context, mouseX, mouseY, delta, dt, tSec);
        } finally {
            gAlpha = 1f;
        }
    }

    private void drawAll(class_332 context, int mouseX, int mouseY, float delta, float dt, float tSec) {
        // tło, poświata i panel
        fillRaw(context, 0, 0, this.field_22789, this.field_22790, C_OVERLAY);
        float pulse = 0.5f + 0.5f * (float) Math.sin(tSec * 2.0f);
        int glowA = 10 + (int) (9 * pulse);
        for (int i = 6; i >= 1; i--) {
            rr(context, px - i * 2, py - i * 2, px + pw + i * 2, py + ph + i * 2, (glowA << 24) | 0x7C3AED);
        }
        rr(context, px - 1, py - 1, px + pw + 1, py + ph + 1, mix(C_BORDER, C_ACCENT, pulse * 0.6f));
        rrGrad(context, px, py, px + pw, py + ph, C_PANEL_TOP, C_PANEL_BOT);
        rrGradEx(context, px + 1, py + 1, px + pw - 1, py + 44, C_HEADER_TOP, C_HEADER, true, false);
        hgrad(context, px + 1, py + 42, px + pw - 1, py + 44, 0xFF5B21B6, 0xFFC084FC);
        shimmer(context, px + 1, py + 42, px + pw - 1, py + 44, tSec);
        text(context, "AutoRynek", px + 16, py + 10, C_TEXT);
        text(context, "ANARCHIA.GG", px + 16, py + 23, C_ACCENT_L);
        float baseAlpha = gAlpha;
        int sigTotal = listSize();
        int sig = tab * 10_000_000 + page * 10_000 + sigTotal;
        if (sig != lastSig) {
            lastSig = sig;
            contentStartNs = System.nanoTime();
        }
        float contentT = (System.nanoTime() - contentStartNs) / 1_000_000_000f;

        int ix = px + 16;
        int inner = pw - 32;
        int cy = py + 84;
        if (tab == TAB_MY) {
            text(context, "Cenę ustawiasz przy każdym przedmiocie (puste pole = nie kupuje).", ix, cy + 5, C_MUTED);
        } else if (tab == TAB_EVENT) {
            text(context, "Maks. cena dodawanych:", ix, cy + 5, C_MUTED);
        }

        // tła wierszy (z płynnym podświetleniem)
        int total = listSize();
        int start = page * perPage;
        for (int i = 0; i < perPage; i++) {
            int y = listTop + i * ROW_H;
            boolean valid = start + i < total;
            boolean rowHoverNow = valid && mouseX >= ix && mouseX < ix + inner && mouseY >= y && mouseY < y + ROW_H - 3;
            int ri = Math.min(i, rowHover.length - 1);
            gAlpha = baseAlpha * rowFade(i, contentT);
            rowHover[ri] = lerpTo(rowHover[ri], rowHoverNow ? 1f : 0f, dt, 16f);
            int base = i % 2 == 0 ? C_ROW : C_ROW_ALT;
            rr(context, ix, y, ix + inner, y + ROW_H - 3, mix(base, C_ROW_HOVER, rowHover[ri]));
            int bar = (int) (3 * rowHover[ri] + 0.5f);
            if (bar > 0) {
                fillRaw(context, ix, y + 3, ix + bar, y + ROW_H - 6, C_ACCENT);
            }
        }
        gAlpha = baseAlpha;

        // pola tekstowe: fioletowa ramka reagująca na najechanie
        for (int[] b : fieldBoxes) {
            Anim fa = anim(b[0], b[1]);
            boolean over = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
            fa.hover = lerpTo(fa.hover, over ? 1f : 0f, dt, 12f);
            rr(context, b[0] - 2, b[1] - 2, b[0] + b[2] + 2, b[1] + b[3] + 2, mix(0xFF35206A, C_ACCENT, fa.hover));
        }
        for (class_342 f : fieldWidgets) {
            f.method_25394(context, mouseX, mouseY, delta);
        }

        // przyciski
        for (Skin s : skins) {
            gAlpha = s.row >= 0 ? baseAlpha * rowFade(s.row, contentT) : baseAlpha;
            drawSkin(context, s, mouseX, mouseY, dt);
        }
        gAlpha = baseAlpha;

        // płynnie przesuwające się podkreślenie aktywnej zakładki
        if (indX >= 0) {
            indX = lerpTo(indX, indTargetX, dt, 14f);
            indW = lerpTo(indW, indTargetW, dt, 14f);
            int iy = py + 52 + 22;
            hgrad(context, (int) indX + 4, iy, (int) (indX + indW) - 4, iy + 2, C_ACCENT, C_ACCENT_L);
        }

        // zawartość wierszy
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            int y = listTop + i * ROW_H;
            gAlpha = baseAlpha * rowFade(i, contentT);
            if (idx >= total) {
                if (i == 0) {
                    text(context, tab == TAB_MY ? "Lista jest pusta - dodaj przedmioty z zakładek powyżej." : "Brak wyników.", ix + 10, y + 8, C_MUTED);
                }
                continue;
            }
            if (tab == TAB_MY) {
                AutoRynekConfig.Entry e = cfg.entries.get(idx);
                boolean byId = e.name.startsWith("id:");
                String badge = byId ? "ITEM" : "EVENT";
                class_1792 entryIcon = iconForEntry(e.name);
                if (entryIcon != null) {
                    icon(context, entryIcon, ix + 6, y + 3);
                }
                int badgeX = ix + 28;
                rr(context, badgeX, y + 4, badgeX + 42, y + ROW_H - 7, byId ? 0x558B3DFF : 0x44FBBF24);
                int bw = this.field_22793.method_27525(class_2561.method_43470(badge));
                text(context, badge, badgeX + (42 - bw) / 2, y + 8, byId ? C_ACCENT_L : C_WARN);
                text(context, trim(shortName(e.name), inner - 10 - 70 - 150), ix + 78, y + 8, C_TEXT);
                if (e.maxPrice <= 0) {
                    String warn = "ustaw cenę \u2192";
                    int ww = this.field_22793.method_27525(class_2561.method_43470(warn));
                    text(context, warn, ix + inner - 140 - 8 - ww, y + 8, C_WARN);
                }
            } else if (tab == TAB_EVENT) {
                String name = EventItems.ALL[idx][0];
                class_1792 evIcon = itemsById.get("minecraft:" + EventItems.ALL[idx][2]);
                if (evIcon != null) {
                    icon(context, evIcon, ix + 6, y + 3);
                }
                text(context, trim(name, 190), ix + 30, y + 3, C_TEXT);
                text(context, trim(EventItems.ALL[idx][1], inner - 150), ix + 30, y + 13, C_MUTED);
            } else {
                ItemRow r = results.get(idx);
                icon(context, r.item, ix + 6, y + 3);
                text(context, trim(r.display, 220), ix + 30, y + 3, C_TEXT);
                text(context, trim(r.id, inner - 140), ix + 30, y + 13, C_MUTED);
            }
        }

        gAlpha = baseAlpha;

        // stopka
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        int fy = py + ph - 32;
        String pager = "Strona " + (Math.min(page, pages - 1) + 1) + "/" + pages + "  \u2022  " + total + " poz.";
        text(context, pager, ix + 68, fy + 7, C_MUTED);
        String dcTag = "dc: _kqcp__";
        int dcw = this.field_22793.method_27525(class_2561.method_43470(dcTag));
        text(context, dcTag, ix + inner - 110 - 12 - dcw, fy + 7, C_MUTED);

        String info = !statusText.isEmpty() ? statusText : MarketScanner.lastInfo;
        int infoColor = !statusText.isEmpty() ? statusColor : C_MUTED;
        long buyAgo = System.currentTimeMillis() - MarketScanner.lastBuyMs;
        if (MarketScanner.lastBuyMs > 0 && buyAgo < 6000) {
            // świeże zdarzenie zakupowe: pulsująca zielona belka zamiast zwykłego statusu
            float k = buyAgo < 250 ? buyAgo / 250f : (buyAgo > 5200 ? (6000 - buyAgo) / 800f : 1f);
            float flash = 0.5f + 0.5f * (float) Math.sin(buyAgo / 120.0);
            gAlpha = baseAlpha * k;
            rr(context, ix - 2, py + ph - 50, ix + inner, py + ph - 36, mix(0xFF0F3D22, 0xFF166534, flash));
            gAlpha = baseAlpha;
            info = MarketScanner.lastBuyInfo;
            infoColor = mix(C_TEXT, 0xFF86EFAC, flash);
        }
        fillRaw(context, ix, py + ph - 52, ix + inner, py + ph - 51, 0xFF2E1A5E);
        int dot = cfg.enabled ? mix(C_GREEN, 0xFF4ADE80, pulse) : C_RED;
        if (cfg.enabled) {
            rr(context, ix - 2, py + ph - 48, ix + 8, py + ph - 38, ((int) (50 * pulse) << 24) | 0x4ADE80);
        }
        rr(context, ix, py + ph - 46, ix + 6, py + ph - 40, dot);
        String counters = "skany: " + MarketScanner.scans + "  \u2022  próby zakupu: " + MarketScanner.totalBuyClicks;
        int cw = this.field_22793.method_27525(class_2561.method_43470(counters));
        text(context, counters, ix + inner - cw, py + ph - 46, C_ACCENT_L);
        text(context, trim(info, inner - cw - 24), ix + 12, py + ph - 46, infoColor);

        pollMouse(mouseX, mouseY);
    }

    @Override
    public void method_25432() {
        AutoRynekConfig.save();
    }
}
