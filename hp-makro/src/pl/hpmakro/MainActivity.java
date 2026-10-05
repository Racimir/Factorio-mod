package pl.hpmakro;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Prosty ekran: włączenie usługi, start/stop, tryb testowy i log. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serviceStatus;
    private Button settingsButton;
    private Button startButton;
    private TextView logView;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("HP Makro");
        title.setTextSize(26);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Poza walką: HP poniżej max → globus → Przerwij → oko.\n"
                + "HP pełne → globus → Walcz.\n\n"
                + "1. Włącz usługę HP Makro w ustawieniach dostępności (jednorazowo).\n"
                + "2. Naciśnij START albo dotknij paska u góry ekranu.\n"
                + "3. Przejdź do gry na podgląd (oko). Pasek u góry pokazuje stan, "
                + "dotknięcie go zatrzymuje makro.");
        help.setPadding(0, pad / 2, 0, pad);
        root.addView(help);

        serviceStatus = new TextView(this);
        serviceStatus.setTextSize(16);
        root.addView(serviceStatus);

        settingsButton = new Button(this);
        settingsButton.setText("Otwórz ustawienia dostępności");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(settingsButton);

        final SharedPreferences prefs = getSharedPreferences(MacroService.PREFS, MODE_PRIVATE);
        CheckBox dryRun = new CheckBox(this);
        dryRun.setText("Tryb testowy (nie klika, tylko pokazuje co by zrobił)");
        dryRun.setChecked(prefs.getBoolean(MacroService.PREF_DRY_RUN, false));
        dryRun.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(MacroService.PREF_DRY_RUN, checked).apply();
            }
        });
        root.addView(dryRun);

        startButton = new Button(this);
        startButton.setTextSize(20);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MacroService s = MacroService.instance;
                if (s != null) s.toggle();
                refresh();
            }
        });
        root.addView(startButton);

        TextView logTitle = new TextView(this);
        logTitle.setText("Log:");
        logTitle.setPadding(0, pad, 0, 0);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void refresh() {
        MacroService s = MacroService.instance;
        boolean enabled = s != null;
        serviceStatus.setText((enabled ? "Usługa dostępności: włączona" : "Usługa dostępności: WYŁĄCZONA")
                + "\nAndroid " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        serviceStatus.setTextColor(enabled ? Color.rgb(80, 200, 120) : Color.rgb(230, 80, 80));
        settingsButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        startButton.setEnabled(enabled);
        startButton.setText(enabled && s.isRunning() ? "STOP" : "START");
        logView.setText(MacroService.logText());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
