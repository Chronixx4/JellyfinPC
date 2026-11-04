package de.marvin.jellyfin.pc;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Controller-Unterstuetzung (Xbox und kompatible) ueber XInput. Wandelt Tasten in Tastendruecke um. */
final class Gamepad {
    public static final class State extends Structure {
        public int packet;
        public short buttons;
        public byte lt, rt;
        public short lx, ly, rx, ry;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("packet", "buttons", "lt", "rt", "lx", "ly", "rx", "ry");
        }
    }

    public interface XInput extends Library {
        int XInputGetState(int index, State state);
    }

    private static final int UP = 0x1, DOWN = 0x2, LEFT = 0x4, RIGHT = 0x8, START = 0x10, BACK = 0x20,
            LB = 0x100, RB = 0x200, A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;

    private final Consumer<KeyCode> onKey;
    private XInput api;
    private volatile boolean running = true;
    private int lastMask;
    private long[] nextRepeat = new long[16];
    private boolean connected;

    Gamepad(Consumer<KeyCode> onKey) {
        this.onKey = onKey;
    }

    void start() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        for (String lib : new String[]{"xinput1_4", "xinput1_3", "xinput9_1_0"}) {
            try { api = Native.load(lib, XInput.class); break; } catch (Throwable ignored) { }
        }
        if (api == null) { Log.i("XInput nicht verfuegbar, Controller aus"); return; }
        Thread t = new Thread(this::loop, "gamepad");
        t.setDaemon(true);
        t.start();
    }

    void stop() { running = false; }

    private void loop() {
        State st = new State();
        int pad = -1;
        long lastScan = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (pad < 0) {
                    if (now - lastScan > 1500) { // nur gelegentlich nach Controllern suchen
                        lastScan = now;
                        for (int i = 0; i < 4; i++) if (api.XInputGetState(i, st) == 0) { pad = i; break; }
                        if (pad >= 0 && !connected) { connected = true; Log.i("Controller " + pad + " verbunden"); }
                    }
                    Thread.sleep(250);
                    continue;
                }
                if (api.XInputGetState(pad, st) != 0) { pad = -1; connected = false; Log.i("Controller getrennt"); continue; }
                int m = st.buttons & 0xFFFF;
                int dead = 14000;
                if (st.ly > dead) m |= UP;
                if (st.ly < -dead) m |= DOWN;
                if (st.lx < -dead) m |= LEFT;
                if (st.lx > dead) m |= RIGHT;
                int ltv = st.lt & 0xFF, rtv = st.rt & 0xFF;
                int[] bits = {UP, DOWN, LEFT, RIGHT, A, B, X, Y, LB, RB, START, BACK};
                KeyCode[] keys = {KeyCode.UP, KeyCode.DOWN, KeyCode.LEFT, KeyCode.RIGHT, KeyCode.ENTER, KeyCode.ESCAPE,
                        KeyCode.SPACE, KeyCode.F, KeyCode.LEFT, KeyCode.RIGHT, KeyCode.SPACE, KeyCode.ESCAPE};
                boolean[] repeat = {true, true, true, true, false, false, false, false, true, true, false, false};
                for (int i = 0; i < bits.length; i++) {
                    boolean down = (m & bits[i]) != 0, was = (lastMask & bits[i]) != 0;
                    if (down && (!was || (repeat[i] && now >= nextRepeat[i]))) {
                        nextRepeat[i] = now + (was ? 110 : 380);
                        KeyCode k = keys[i];
                        Platform.runLater(() -> onKey.accept(k));
                    }
                }
                // Schultertasten: Lautstaerke
                if (ltv > 120 && now >= nextRepeat[14]) { nextRepeat[14] = now + 140; Platform.runLater(() -> onKey.accept(KeyCode.DOWN)); }
                if (rtv > 120 && now >= nextRepeat[15]) { nextRepeat[15] = now + 140; Platform.runLater(() -> onKey.accept(KeyCode.UP)); }
                lastMask = m;
                Thread.sleep(16);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.e("Controller", t);
                running = false;
            }
        }
    }
}
