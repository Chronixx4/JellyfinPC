package de.marvin.jellyfin.tv;

import java.util.ArrayList;

/** Einfache Titelliste (Merkliste, Titel einer Person, Wiedergabeliste ...). */
final class ListScreen extends GridScreen {
    static final Pager FAVORITES = (jf, s) -> s == 0 ? jf.favorites() : new ArrayList<>();

    ListScreen(MainActivity a, String title, Pager p) {
        super(a, title);
        pending = p;
    }

    private Pager pending;

    @Override
    android.view.View build() {
        android.view.View v = super.build();
        setPager(pending);
        return v;
    }
}
