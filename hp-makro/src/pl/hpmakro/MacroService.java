package pl.hpmakro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Usługa dostępności: robi zrzuty ekranu, rozpoznaje HP i walkę, klika Przerwij / Walcz.
 * Na górze ekranu pokazuje pasek stanu; dotknięcie go uruchamia lub zatrzymuje makro.
 */
public class MacroService extends AccessibilityService {
    static final String PREFS = "hpmakro";
    static final String PREF_DRY_RUN = "dry_run";

    private static final long POLL_MS = 350;           // Android pozwala na zrzut co ~333 ms
    private static final long TAP_DELAY_MS = 800;
    private static final long UI_TIMEOUT_MS = 6000;
    private static final int JITTER = 6;

    private static final String IDLE_TEXT = "⏸ HP Makro: dotknij = start  ·  przytrzymaj = wyłącz";

    static volatile MacroService instance;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private final Executor shotExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private Handler main;
    private TextView overlay;
    private volatile boolean running;
    private Thread worker;
    private float scaleX = 1f, scaleY = 1f;

    // --- Cykl życia ----------------------------------------------------------------

    @Override
    protected void onServiceConnected() {
        instance = this;
        main = new Handler(Looper.getMainLooper());
        addOverlay();
        status(IDLE_TEXT);
        log("usługa włączona");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        stop();
        if (overlay != null) {
            getSystemService(WindowManager.class).removeView(overlay);
            overlay = null;
        }
        instance = null;
        super.onDestroy();
    }

    boolean isRunning() { return running; }

    synchronized void toggle() {
        if (running) stop(); else start();
    }

    synchronized void start() {
        if (running) return;
        if (android.os.Build.VERSION.SDK_INT < 30) {
            log("zrzuty ekranu z usługi wymagają Androida 11+, ten telefon ma API " + android.os.Build.VERSION.SDK_INT);
            status("⏸ HP Makro: wymaga Androida 11+");
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "hp-makro");
        worker.start();
        log(dryRun() ? "start (tryb testowy - bez klikania)" : "start");
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        status(IDLE_TEXT);
        log("stop");
    }

    /** Zatrzymuje makro i wyłącza usługę dostępności (pasek znika, aplikacja przestaje działać). */
    void shutdown() {
        stop();
        log("aplikacja wyłączona");
        disableSelf();
    }

    private boolean dryRun() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_DRY_RUN, false);
    }

    // --- Pasek stanu i log ---------------------------------------------------------------

    private void addOverlay() {
        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(13);
        overlay.setBackgroundColor(0xC0000000);
        overlay.setPadding(24, 10, 24, 10);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        overlay.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                shutdown();
                return true;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 20;
        getSystemService(WindowManager.class).addView(overlay, lp);
    }

    private void status(final String text) {
        if (main == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (overlay != null) overlay.setText(text);
            }
        });
    }

    static void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg;
        synchronized (LOG) {
            LOG.addFirst(line);
            while (LOG.size() > 60) LOG.removeLast();
        }
    }

    static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            for (String s : LOG) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    // --- Zrzuty i kliknięcia ----------------------------------------------------------

    /** Robi zrzut ekranu; null, jeśli się nie udało. */
    private Analyzer shot() throws InterruptedException {
        final Bitmap[] out = new Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                if (hw != null) {
                    out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                }
                buffer.close();
                latch.countDown();
            }

            @Override public void onFailure(int errorCode) { latch.countDown(); }
        });
        latch.await(3, TimeUnit.SECONDS);
        Bitmap bmp = out[0];
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();
        Analyzer a = new Analyzer(px, w, h);
        scaleX = a.sx;
        scaleY = a.sy;
        return a;
    }

    private void tap(int[] ref, String name) throws InterruptedException {
        float x = ref[0] * scaleX + random.nextInt(2 * JITTER + 1) - JITTER;
        float y = ref[1] * scaleY + random.nextInt(2 * JITTER + 1) - JITTER;
        if (dryRun()) {
            log("[test] klik: " + name);
        } else {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            final CountDownLatch done = new CountDownLatch(1);
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { done.countDown(); }
                @Override public void onCancelled(GestureDescription g) { done.countDown(); }
            }, main);
            done.await(2, TimeUnit.SECONDS);
        }
        Thread.sleep(TAP_DELAY_MS);
    }

    private static final int WAIT_VIEW = 0, WAIT_WORLD = 1, WAIT_PAUSED = 2;

    /** Czeka, aż ekran będzie w danym stanie. Zwraca ostatni zrzut albo null po przekroczeniu czasu. */
    private Analyzer waitFor(int what) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            Analyzer a = shot();
            if (a != null) {
                Analyzer.Screen s = a.screen();
                if (what == WAIT_VIEW && s == Analyzer.Screen.VIEW) return a;
                if (what == WAIT_WORLD && s == Analyzer.Screen.WORLD) return a;
                if (what == WAIT_PAUSED && s == Analyzer.Screen.WORLD && Boolean.FALSE.equals(a.huntingActive())) return a;
            }
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    private void backToView() throws InterruptedException {
        tap(Analyzer.EYE_TAP, "oko");
        waitFor(WAIT_VIEW);
    }

    /** Włącza/wyłącza polowanie. Zwraca stan po akcji albo null, jeśli coś poszło nie tak. */
    private Boolean setHunting(boolean on) throws InterruptedException {
        if (dryRun()) {
            log("[test] globus -> " + (on ? "Walcz" : "Przerwij -> oko"));
            return on;
        }
        tap(Analyzer.GLOBE_TAP, "globus");
        Analyzer world = waitFor(WAIT_WORLD);
        if (world == null) {
            log("nie otworzył się ekran globusa");
            backToView();
            return null;
        }
        Boolean active = world.huntingActive();
        if (active == null) {
            log("nie rozpoznano przycisków Przerwij/Walcz");
            backToView();
            return null;
        }
        if (on) {
            if (active) {
                log("polowanie już trwa");
                backToView();
                return true;
            }
            tap(Analyzer.FIGHT_TAP, "Walcz");
            if (waitFor(WAIT_VIEW) == null) backToView();   // Walcz zwykle sam wraca na podgląd
            return true;
        }
        if (!active) {
            log("polowanie już wstrzymane");
            backToView();
            return false;
        }
        tap(Analyzer.STOP_TAP, "Przerwij");
        // W trakcie walki gra najpierw ją kończy, więc "Walcz" może pojawić się dopiero później.
        if (waitFor(WAIT_PAUSED) == null) log("Przerwij kliknięte, gra kończy jeszcze walkę");
        backToView();
        return false;
    }

    // --- Główna pętla ------------------------------------------------------------------

    private void loop() {
        Boolean hunting = null;       // nieznany na starcie; ustali się przy pierwszej akcji
        int[] prevEdge = null;
        int walkFrames = 0;           // ile klatek z rzędu tło się przesuwa
        int lowReadings = 0;          // ile odczytów z rzędu HP jest poniżej progu
        try {
            while (running) {
                Analyzer a = shot();
                if (a == null) {
                    Thread.sleep(500);
                    continue;
                }
                if (a.screen() != Analyzer.Screen.VIEW) {
                    status("▶ Wejdź na podgląd (oko)  ·  dotknij = stop");
                    prevEdge = null;
                    walkFrames = 0;
                    Thread.sleep(POLL_MS);
                    continue;
                }

                // Tło przesuwa się tylko w marszu między walkami; w walce i na odpoczynku stoi.
                int[] edge = a.edgeGray();
                boolean hadPrev = prevEdge != null;
                float bgMotion = Analyzer.motion(prevEdge, edge);
                prevEdge = edge;
                // Dwie klatki z rzędu, żeby pojedyncze szarpnięcie obrazu w walce nie udawało marszu.
                walkFrames = bgMotion >= Analyzer.WALK_MOTION ? walkFrames + 1 : 0;
                boolean walking = walkFrames >= 2;
                float hp = a.hp();
                boolean full = hp >= Analyzer.HP_FULL;
                // W marszu wychodzimy już poniżej 90%; w walce dopiero poniżej 30%, bo lifesteal leczy.
                float threshold = walking ? Analyzer.HP_PAUSE_WALKING : Analyzer.HP_PAUSE_FIGHTING;

                status(String.format(Locale.ROOT, "▶ HP %d%%  ·  tło %d%% (%s)  ·  polowanie: %s%s  ·  dotknij = stop",
                        Math.round(hp * 100), Math.round(bgMotion * 100), walking ? "marsz" : "stoi",
                        hunting == null ? "?" : hunting ? "tak" : "nie", dryRun() ? "  ·  TEST" : ""));

                // Dwa niskie odczyty z rzędu chronią przed pojedynczym błędnym zrzutem.
                lowReadings = hp < threshold ? lowReadings + 1 : 0;
                Boolean result = null;
                boolean acted = false;
                if (lowReadings >= 2 && !Boolean.FALSE.equals(hunting)) {
                    log(String.format(Locale.ROOT, "%s, HP %d%% < %d%% -> Przerwij", walking ? "marsz" : "walka",
                            Math.round(hp * 100), Math.round(threshold * 100)));
                    result = setHunting(false);
                    acted = true;
                } else if (full && !Boolean.TRUE.equals(hunting)) {
                    // Także raz na starcie, gdy stan polowania jest jeszcze nieznany.
                    log(hunting == null ? "start: sprawdzam polowanie" : "HP pełne -> Walcz");
                    result = setHunting(true);
                    acted = true;
                }
                if (acted) {
                    if (result != null) hunting = result;
                    prevEdge = null;
                    walkFrames = 0;
                    lowReadings = 0;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException ignored) {
            // stop()
        } catch (RuntimeException e) {
            log("błąd: " + e);
            running = false;
            status("⏸ HP Makro: błąd  ·  dotknij = start  ·  przytrzymaj = wyłącz");
        }
    }
}
