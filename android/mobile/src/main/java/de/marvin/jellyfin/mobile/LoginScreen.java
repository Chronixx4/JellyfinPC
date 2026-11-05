package de.marvin.jellyfin.mobile;

import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import de.marvin.jellyfin.core.Jf;

import java.util.List;

/** Anmeldung per Benutzername/Passwort oder Quick Connect; gespeicherte Profile zum schnellen Wechsel. */
final class LoginScreen extends Screen {
    private EditText server, user, pass;
    private TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean polling;

    LoginScreen(MainActivity a) { super(a); }

    private EditText field(String hint, String val, int type) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(val);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setMinHeight(Ui.dp(a, 52));
        e.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        e.setBackground(Ui.outlined(a, 0x12FFFFFF, Ui.LINE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(a, 12);
        e.setLayoutParams(lp);
        return e;
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(a, 28);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col, new ScrollView.LayoutParams(-1, -1));

        TextView title = Ui.brand(a, "Jellyfin", 40);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = Ui.text(a, "Mit deinem Server verbinden", 15, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        server = field("Server (z. B. 192.168.178.80:8097)", a.jf.server, InputType.TYPE_TEXT_VARIATION_URI);
        user = field("Benutzername", a.jf.userName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pass = field("Passwort", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImeOptions(EditorInfo.IME_ACTION_GO);
        pass.setOnEditorActionListener((v, id, ev) -> { login(); return true; });
        col.addView(server);
        col.addView(user);
        col.addView(pass);

        Ui.Btn go = Ui.button(a, "Anmelden", true);
        go.setOnClickListener(v -> login());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(a, 20);
        col.addView(go, lp);

        Ui.Btn qc = Ui.button(a, "Quick Connect", false);
        qc.setOnClickListener(v -> quickConnect());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = Ui.dp(a, 10);
        col.addView(qc, lp2);

        status = Ui.text(a, "", 14, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(a, 16), 0, 0);
        col.addView(status, new LinearLayout.LayoutParams(-1, -2));

        List<Jf.Profile> profiles = a.jf.profiles();
        if (!profiles.isEmpty()) {
            TextView t = Ui.text(a, "Gespeicherte Profile", 14, Ui.MUTED, true);
            t.setPadding(0, Ui.dp(a, 20), 0, Ui.dp(a, 6));
            col.addView(t);
            for (Jf.Profile p : profiles) {
                Ui.Btn b = Ui.button(a, p.pinHash.isEmpty() ? "person" : "lock", p.name, false);
                b.setOnClickListener(v -> {
                    if (p.pinHash.isEmpty()) { a.jf.useProfile(p); a.reset(new HomeScreen(a)); return; }
                    EditText e = new EditText(a);
                    e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                    e.setHint("PIN");
                    new android.app.AlertDialog.Builder(a).setTitle("PIN für " + p.name).setView(e)
                            .setPositiveButton("OK", (d, w) -> {
                                if (a.jf.checkPin(p, e.getText().toString().trim())) { a.jf.useProfile(p); a.reset(new HomeScreen(a)); }
                                else a.toast("Falsche PIN");
                            }).setNegativeButton("Abbrechen", null).show();
                });
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
                l.topMargin = Ui.dp(a, 8);
                col.addView(b, l);
            }
        }
        return sv;
    }

    private void login() {
        status.setText("Anmelden …");
        a.jf.setServer(server.getText().toString());
        String u = user.getText().toString().trim(), p = pass.getText().toString();
        a.bg(() -> { a.jf.loginPassword(u, p); return true; }, ok -> {
            if (ok == null) { status.setText("Anmeldung fehlgeschlagen – Server, Name und Passwort prüfen."); return; }
            a.reset(new HomeScreen(a));
        });
    }

    private void quickConnect() {
        a.jf.setServer(server.getText().toString());
        status.setText("Quick Connect …");
        a.bg(() -> a.jf.quickConnectStart(), codeSecret -> {
            if (codeSecret == null) { status.setText("Quick Connect am Server nicht verfügbar."); return; }
            status.setText("Gib in Jellyfin (Web oder andere App) unter „Quick Connect“ diesen Code ein:\n\n" + codeSecret[0]);
            status.setTextColor(Ui.TEXT);
            status.setTextSize(18);
            polling = true;
            poll(codeSecret[1], 0);
        });
    }

    private void poll(String secret, int n) {
        if (!polling || n > 150) return;
        h.postDelayed(() -> {
            if (!polling) return;
            a.bg(() -> a.jf.quickConnectPoll(secret), ok -> {
                if (ok != null && ok) { polling = false; a.reset(new HomeScreen(a)); }
                else poll(secret, n + 1);
            }, true);
        }, 2000);
    }
}
