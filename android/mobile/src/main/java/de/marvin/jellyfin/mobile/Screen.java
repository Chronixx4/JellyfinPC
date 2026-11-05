package de.marvin.jellyfin.mobile;

import android.view.View;

/** Eine Seite der App. MainActivity haelt sie in einem eigenen Zurueck-Stapel. */
abstract class Screen {
    final MainActivity a;
    View view;

    Screen(MainActivity a) { this.a = a; }

    abstract View build();

    /** Beim Zurueckkehren (z. B. nach dem Abspielen) Daten neu laden. */
    void refresh() { }

    /** Die Seite ist nicht mehr sichtbar (andere Seite darueber oder App im Hintergrund): Laufendes anhalten. */
    void onHidden() { }

    /** true, wenn die Seite den Zurueck-Knopf selbst behandelt hat. */
    boolean onBack() { return false; }
}
