package pl.hpmakro;

/**
 * Rozpoznawanie stanu gry na podstawie pikseli zrzutu ekranu (ARGB).
 * Wszystkie współrzędne są podane dla ekranu 1080x2400 i przeliczane na rozdzielczość zrzutu.
 * Prostokąty mają format {x, y, szerokość, wysokość}.
 */
final class Analyzer {
    static final int REF_W = 1080, REF_H = 2400;

    enum Screen { VIEW, WORLD, OTHER }

    // Pasek HP (czerwone wypełnienie przy pełnym zdrowiu)
    static final int HP_X0 = 35, HP_X1 = 521, HP_Y0 = 314, HP_Y1 = 369;
    static final float HP_FULL = 0.99f;          // wznowienie polowania
    static final float HP_PAUSE_WALKING = 0.90f;   // wstrzymanie polowania w marszu
    static final float HP_PAUSE_FIGHTING = 0.30f;  // wstrzymanie polowania w walce

    // Zakładki na dole ekranu: zaznaczona ma ciemniejsze tło (~35 zamiast ~75)
    static final int[] EYE_SAMPLE = {470, 2230, 20, 20};
    static final int[] GLOBE_SAMPLE = {888, 2230, 20, 20};
    static final int[] EYE_TAP = {538, 2292};
    static final int[] GLOBE_TAP = {960, 2292};
    static final int TAB_SELECTED_MAX = 50;

    // Ekran świata: podświetlony wiersz "Tereny łowieckie" = polowanie trwa, "Obozowisko" = wstrzymane
    static final int[] HUNT_ROW_SAMPLE = {600, 750, 40, 20};
    static final int[] CAMP_ROW_SAMPLE = {600, 1150, 40, 20};
    static final int PANEL_MIN = 45;
    static final int[] STOP_TAP = {870, 805};
    static final int[] FIGHT_TAP = {875, 867};

    // Marsz vs walka: podczas marszu między walkami tło się przesuwa, w walce i podczas odpoczynku stoi.
    // Patrzymy tylko na lewy skraj sceny, gdzie nie ma postaci ani cyfr obrażeń.
    static final int[] LEFT_EDGE = {40, 430, 160, 1700};
    static final int MOTION_PIXEL_DIFF = 10;      // o ile musi się zmienić piksel; piasek ma mały kontrast
    static final float WALK_MOTION = 0.05f;       // udział zmienionych pikseli skraju = tło się przesuwa
    private static final int STEP = 2;

    final int[] px;
    final int w, h;
    final float sx, sy;

    Analyzer(int[] px, int w, int h) {
        this.px = px;
        this.w = w;
        this.h = h;
        this.sx = w / (float) REF_W;
        this.sy = h / (float) REF_H;
    }

    int x(int rx) { return clamp(Math.round(rx * sx), 0, w - 1); }

    int y(int ry) { return clamp(Math.round(ry * sy), 0, h - 1); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    float brightness(int[] r) {
        int x0 = x(r[0]), y0 = y(r[1]), x1 = x(r[0] + r[2]), y1 = y(r[1] + r[3]);
        long sum = 0;
        int n = 0;
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                int c = px[yy * w + xx];
                sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
                n++;
            }
        }
        return n == 0 ? 255f : sum / (3f * n);
    }

    Screen screen() {
        if (brightness(EYE_SAMPLE) < TAB_SELECTED_MAX) return Screen.VIEW;
        if (brightness(GLOBE_SAMPLE) < TAB_SELECTED_MAX) return Screen.WORLD;
        return Screen.OTHER;
    }

    /** Wypełnienie paska HP, 0..1. */
    float hp() {
        int x0 = x(HP_X0), x1 = x(HP_X1), y0 = y(HP_Y0), y1 = y(HP_Y1);
        int rows = y1 - y0 + 1;
        int last = -1;
        for (int xx = x0; xx <= x1; xx++) {
            int red = 0;
            for (int yy = y0; yy <= y1; yy++) {
                if (isRed(px[yy * w + xx])) red++;
            }
            // Liczby na pasku zasłaniają część kolumny, więc wystarczy trochę czerwieni.
            if (red >= rows * 0.15f) last = xx;
        }
        return last < 0 ? 0f : (last - x0 + 1) / (float) (x1 - x0 + 1);
    }

    static boolean isRed(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max < 80 || max != r || max == min) return false;
        if ((max - min) * 255 < 120 * max) return false;           // nasycenie < 120/255
        float hue = 60f * (g - b) / (max - min);                    // -60..60 stopni wokół czerwieni
        return hue <= 20f && hue >= -20f;
    }

    /** Pomniejszona szara kopia lewego skraju sceny do porównywania kolejnych klatek. */
    int[] edgeGray() {
        int x0 = x(LEFT_EDGE[0]), y0 = y(LEFT_EDGE[1]);
        int x1 = x(LEFT_EDGE[0] + LEFT_EDGE[2]), y1 = y(LEFT_EDGE[1] + LEFT_EDGE[3]);
        int[] out = new int[((y1 - y0 + STEP - 1) / STEP) * ((x1 - x0 + STEP - 1) / STEP)];
        int i = 0;
        for (int yy = y0; yy < y1; yy += STEP) {
            for (int xx = x0; xx < x1; xx += STEP) {
                int c = px[yy * w + xx];
                out[i++] = (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
            }
        }
        return out;
    }

    /** Udział pikseli, które zmieniły się między klatkami. */
    static float motion(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        int changed = 0;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > MOTION_PIXEL_DIFF) changed++;
        }
        return changed / (float) a.length;
    }

    /** Na ekranie świata: TRUE = polowanie trwa, FALSE = wstrzymane, null = nie rozpoznano. */
    Boolean huntingActive() {
        if (brightness(HUNT_ROW_SAMPLE) >= PANEL_MIN) return Boolean.TRUE;
        if (brightness(CAMP_ROW_SAMPLE) >= PANEL_MIN) return Boolean.FALSE;
        return null;
    }
}
