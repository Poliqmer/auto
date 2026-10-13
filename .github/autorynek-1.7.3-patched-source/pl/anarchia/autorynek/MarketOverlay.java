package pl.anarchia.autorynek;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.class_1707;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_437;
import net.minecraft.class_476;
import org.lwjgl.glfw.GLFW;

/**
 * Mały panel w oknie rynku (po lewej stronie okna): włącznik moda i tempo - bez wychodzenia do /autorynek.
 * Podpina się przez Fabric API (ScreenEvents); jeśli API ma inną sygnaturę, panel po prostu się nie pokazuje.
 */
final class MarketOverlay {
    private static final int C_GREEN = 0xFF16A34A;
    private static final int C_RED = 0xFFDC2626;
    private static final int C_BTN = 0xFF2E1A5E;
    private static final int C_ACCENT = 0xFF8B3DFF;
    private static final int C_ACCENT_L = 0xFFC4A1FF;
    private static final int C_TEXT = 0xFFF5F0FF;

    private static boolean registered = false;
    private static boolean broken = false;
    private static final Set<class_437> HOOKED = Collections.newSetFromMap(new WeakHashMap<>());
    private static long lastNs = System.nanoTime();
    private static boolean prevDown = false;
    private static long lastClickMs = 0;
    private static final float[] hover = new float[2];
    private static final float[] press = new float[2];
    private static final int[] color = new int[2];
    private static final boolean[] colorInit = new boolean[2];

    private MarketOverlay() {
    }

    static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            // sprawdzamy, czy sygnatury z Fabric API zgadzają się z oczekiwanymi - inaczej nie rejestrujemy nic
            boolean okInit = hasMethod(ScreenEvents.AfterInit.class, "afterInit",
                    class_310.class, class_437.class, int.class, int.class);
            boolean okRender = hasMethod(ScreenEvents.AfterRender.class, "afterRender",
                    class_437.class, class_332.class, int.class, int.class, float.class);
            if (!okInit || !okRender) {
                AutoRynekClient.LOGGER.warn("Panel w rynku wyłączony: inna wersja Fabric API (ScreenEvents).");
                return;
            }
            ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
                if (screen instanceof class_476 && HOOKED.add(screen)) {
                    ScreenEvents.afterRender(screen).register(MarketOverlay::render);
                }
            });
        } catch (Throwable t) {
            AutoRynekClient.LOGGER.warn("Panel w rynku niedostępny: {}", t.toString());
        }
    }

    private static boolean hasMethod(Class<?> c, String name, Class<?>... params) {
        try {
            c.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void render(class_437 screen, class_332 ctx, int mx, int my, float tickDelta) {
        if (broken || !(screen instanceof class_476 gcs)) {
            return;
        }
        try {
            draw(gcs, ctx, mx, my);
        } catch (Throwable t) {
            broken = true;
            AutoRynekClient.LOGGER.warn("Panel w rynku przestał działać: {}", t.toString());
        }
    }

    private static void draw(class_476 gcs, class_332 ctx, int mx, int my) {
        AutoRynekConfig cfg = AutoRynekConfig.get();
        String title = MarketScanner.norm(gcs.method_25440().getString());
        if (!title.contains(MarketScanner.norm(cfg.marketTitleContains))) {
            return;
        }
        class_1707 handler = gcs.method_17577();
        int guiW = 176;
        int guiH = 114 + handler.method_17388() * 18;
        int sw = gcs.field_22789;
        int sh = gcs.field_22790;
        int x0 = (sw - guiW) / 2;
        int y0 = (sh - guiH) / 2;
        int bw = 122;
        int bh = 20;
        int px;
        if (x0 - bw - 14 >= 4) {
            px = x0 - bw - 14;
        } else if (x0 + guiW + 14 + bw <= sw - 4) {
            px = x0 + guiW + 14;
        } else {
            return; // brak miejsca obok okna - nie zasłaniamy slotów
        }
        int py = y0 + 4;

        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;
        class_327 font = gcs.field_22793;

        // tło panelu z fioletową poświatą
        rr(ctx, px - 8, py - 8, px + bw + 8, py + 14 + 2 * bh + 6 + 8, 0x22A855F7);
        rr(ctx, px - 6, py - 6, px + bw + 6, py + 14 + 2 * bh + 6 + 6, 0xFF5B21B6);
        rrGrad(ctx, px - 5, py - 5, px + bw + 5, py + 14 + 2 * bh + 6 + 5, 0xFF1A0B33, 0xFF0A0415);
        text(ctx, font, "AutoRynek", px, py, C_ACCENT_L);

        String[] labels = {
                cfg.enabled ? "\u25CF WŁĄCZONY" : "\u25CF WYŁĄCZONY",
                "TEMPO: " + AutoRynekConfig.SPEED_NAMES[cfg.speed]
        };
        int[] targets = {cfg.enabled ? C_GREEN : C_RED, C_BTN};
        int[] ys = {py + 14, py + 14 + bh + 6};

        // kliknięcia (wykrywane przez GLFW - bez ingerowania w obsługę myszy okna)
        boolean down = false;
        try {
            long handle = class_310.method_1551().method_22683().method_4490();
            down = GLFW.glfwGetMouseButton(handle, 0) == 1;
        } catch (Throwable t) {
            down = false;
        }
        boolean clicked = down && !prevDown;
        prevDown = down;

        for (int i = 0; i < 2; i++) {
            boolean over = mx >= px && mx < px + bw && my >= ys[i] && my < ys[i] + bh;
            hover[i] += ((over ? 1f : 0f) - hover[i]) * Math.min(1f, dt * 14f);
            press[i] = Math.max(0f, press[i] - dt * 4.5f);
            if (!colorInit[i]) {
                color[i] = targets[i];
                colorInit[i] = true;
            } else {
                color[i] = mix(color[i], targets[i], Math.min(1f, dt * 10f));
            }
            if (clicked && over && System.currentTimeMillis() - lastClickMs > 180) {
                lastClickMs = System.currentTimeMillis();
                press[i] = 1f;
                if (i == 0) {
                    cfg.enabled = !cfg.enabled;
                    if (cfg.enabled) {
                        cfg.sniper = true;
                        cfg.axeRefresh = true;
                        cfg.ensureNewest = true;
                    }
                } else {
                    cfg.speed = (cfg.speed + 1) % AutoRynekConfig.SPEED_NAMES.length;
                }
                AutoRynekConfig.save();
            }
            drawButton(ctx, font, px, ys[i], bw, bh, labels[i], color[i], hover[i], press[i]);
        }
    }

    private static void drawButton(class_332 c, class_327 font, int x, int y0, int w, int h, String label,
                                   int base, float hov, float prs) {
        int off = prs > 0.55f ? 1 : 0;
        int y = y0 + off;
        if (hov > 0.03f) {
            for (int k = 3; k >= 1; k--) {
                rr(c, x - k, y - k, x + w + k, y + h + k, ((int) (34 * hov / k) << 24) | 0x8B3DFF);
            }
        }
        rr(c, x, y, x + w, y + h, mix(mix(base, 0xFF000000, 0.35f), C_ACCENT_L, 0.75f * hov + 0.25f * prs));
        int top = mix(lighten(base), lighten(lighten(base)), hov);
        int bot = mix(mix(base, 0xFF000000, 0.18f), base, hov);
        rrGrad(c, x + 1, y + 1, x + w - 1, y + h - 1, top, bot);
        fill(c, x + 3, y + 1, x + w - 3, y + 2, mix(top, 0xFFFFFFFF, 0.22f));
        if (prs > 0f) {
            rr(c, x + 1, y + 1, x + w - 1, y + h - 1, ((int) (70 * prs) << 24) | 0xFFFFFF);
        }
        int tw = font.method_27525(class_2561.method_43470(label));
        text(c, font, label, x + (w - tw) / 2, y + (h - 8) / 2, C_TEXT);
    }

    private static void text(class_332 c, class_327 font, String s, int x, int y, int col) {
        c.method_27535(font, class_2561.method_43470(s), x, y, col);
    }

    private static void fill(class_332 c, int x1, int y1, int x2, int y2, int col) {
        if (x2 > x1 && y2 > y1) {
            c.method_25294(x1, y1, x2, y2, col);
        }
    }

    private static void rrGrad(class_332 c, int x1, int y1, int x2, int y2, int top, int bottom) {
        int h = y2 - y1;
        for (int i = 0; i < h; i++) {
            int inset = (i == 0 || i == h - 1) ? 2 : ((i == 1 || i == h - 2) ? 1 : 0);
            fill(c, x1 + inset, y1 + i, x2 - inset, y1 + i + 1, mix(top, bottom, i / (float) h));
        }
    }

    private static void rr(class_332 c, int x1, int y1, int x2, int y2, int col) {
        rrGrad(c, x1, y1, x2, y2, col, col);
    }

    private static int mix(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) * (1 - t) + ((c2 >>> 24) & 0xFF) * t);
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int c) {
        int a = c & 0xFF000000;
        int r = Math.min(255, ((c >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 28);
        int b = Math.min(255, (c & 0xFF) + 28);
        return a | (r << 16) | (g << 8) | b;
    }
}
