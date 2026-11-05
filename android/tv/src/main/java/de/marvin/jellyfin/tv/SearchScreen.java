package de.marvin.jellyfin.tv;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;

/** Suche nach Filmen und Serien mit Live-Ergebnissen. */
final class SearchScreen extends GridScreen {
    private EditText box;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    SearchScreen(MainActivity a) { super(a, "Suche"); }

    @Override
    View header() {
        box = new EditText(a);
        box.setHint("Film oder Serie suchen …");
        box.setHintTextColor(Ui.MUTED);
        box.setTextColor(Ui.TEXT);
        box.setSingleLine(true);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.setMinHeight(Ui.dp(a, 48));
        box.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        box.setBackground(Ui.outlined(a, 0x14FFFFFF, Ui.LINE, 24));
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (pendingSearch != null) h.removeCallbacks(pendingSearch);
                final String q = e.toString().trim();
                pendingSearch = () -> run(q);
                h.postDelayed(pendingSearch, 350);
            }
        });
        LinearLayout w = new LinearLayout(a);
        w.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 8));
        w.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return w;
    }

    private void run(String q) {
        if (q.length() < 2) { setPager((jf, s) -> new ArrayList<>()); return; }
        setPager((jf, s) -> s == 0 ? jf.search(q) : new ArrayList<>());
    }

    @Override
    View build() {
        View v = super.build();
        box.requestFocus();
        box.post(() -> {
            InputMethodManager im = (InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(box, 0);
        });
        return v;
    }
}
