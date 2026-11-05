package de.marvin.jellyfin.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import de.marvin.jellyfin.core.Jf;
import de.marvin.jellyfin.core.JfItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vollbild-Player mit Touch-Bedienung: Tippen = Bedienelemente, Doppeltippen links/rechts = -/+ 10 s,
 * Doppeltippen Mitte = Pause, Wischen waagerecht = Spulen, Wischen senkrecht links = Helligkeit, rechts = Lautstaerke.
 */
public class PlayerActivity extends Activity {
    private static final long TICKS_PER_MS = 10000L;

    private Jf jf;
    private ExoPlayer player;
    private PlayerView pv;
    private FrameLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private JfItem item, nextItem;
    private long introEndMs, outroStartMs, outroEndMs;
    private String localFile;
    private boolean transcoding, started, lockedControls, reportedStop, switching;
    private long lastReport;
    static PlayerActivity current;
    private boolean groupStart;     // Start auf Befehl der Gruppe: pausiert warten, bis alle bereit sind
    private long syncStartMs;

    // Oberflaeche
    private View controls;
    private TextView titleView, posView, durView, feedbackText;
    private LinearLayout feedback;
    private ImageView feedbackIcon, playPause, lockBtn;
    private View skipIntro, nextBtn, outroBtn;
    private SeekBar seek;
    private boolean controlsVisible, scrubbing;

    // Gesten
    private AudioManager audio;
    private float brightness = -1;
    private int mode; // 0 offen, 1 spulen, 2 helligkeit, 3 lautstaerke, -1 ignorieren
    private long scrubTarget;
    private float volAcc;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Icons.init(this);
        jf = Jf.get(this);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        current = this;
        groupStart = getIntent().getBooleanExtra("sync", false);
        SyncPlay.cmd = this::syncCommand;
        localFile = getIntent().getStringExtra("file");
        load(getIntent().getStringExtra("id"), getIntent().getLongExtra("start", 0));
    }

    // ------------------------------------------------------------ Aufbau

    /** Symbol-Knopf fuer die Bedienleiste (mindestens 52dp gross, gut mit dem Daumen zu treffen). */
    private ImageView sym(String icon, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(Icons.drawable(icon, Color.WHITE));
        int pad = Ui.dp(this, 14);
        v.setPadding(pad, pad, pad, pad);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        v.setClickable(true);
        return v;
    }

    private TextView txtBtn(String s) {
        TextView t = Ui.text(this, s, 16, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 52));
        t.setMinHeight(Ui.dp(this, 52));
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        // Gestenflaeche
        View touch = new View(this);
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (lockedControls) { showControls(); return true; }
                if (controlsVisible) hideControls(); else showControls();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (player == null || lockedControls) return true;
                float x = e.getX() / root.getWidth();
                if (x < 0.35f) { seekBy(-10000); flash("rewind", "10 s"); }
                else if (x > 0.65f) { seekBy(10000); flash("forward", "10 s"); }
                else togglePlay();
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (player == null || lockedControls || e1 == null) return false;
                if (mode == 0) {
                    float ax = Math.abs(e2.getX() - e1.getX()), ay = Math.abs(e2.getY() - e1.getY());
                    if (Math.max(ax, ay) < Ui.dp(PlayerActivity.this, 14)) return true;
                    if (ax > ay) { mode = 1; scrubTarget = player.getCurrentPosition(); scrubbing = true; }
                    else mode = e1.getX() < root.getWidth() / 2f ? 2 : 3;
                    volAcc = 0;
                }
                if (mode == 1) {
                    scrubTarget = clamp(scrubTarget - (long) (dx * 120), 0, duration());
                    flash(scrubTarget >= player.getCurrentPosition() ? "forward" : "rewind", fmt(scrubTarget) + " / " + fmt(duration()));
                    seek.setProgress((int) (scrubTarget / 1000));
                } else if (mode == 2) {
                    adjustBrightness(dy / root.getHeight() * 1.4f);
                } else if (mode == 3) {
                    adjustVolume(dy / root.getHeight() * 1.4f);
                }
                return true;
            }
        });
        touch.setOnTouchListener((v, e) -> {
            boolean r = gd.onTouchEvent(e);
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (mode == 1 && player != null) { userSeek(scrubTarget); }
                scrubbing = false;
                mode = 0;
                h.postDelayed(() -> feedback.setVisibility(View.GONE), 500);
            }
            return r;
        });
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        // Hinweis-Text in der Mitte (Spulen, Lautstaerke ...)
        feedback = new LinearLayout(this);
        feedback.setGravity(Gravity.CENTER_VERTICAL);
        feedback.setBackground(Ui.outlined(this, 0xCC0A0D12, Ui.LINE, 22));
        feedback.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        feedbackIcon = new ImageView(this);
        feedbackIcon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        feedbackText = Ui.text(this, "", 20, Color.WHITE, true);
        feedbackText.setPadding(Ui.dp(this, 10), 0, 0, 0);
        feedback.addView(feedbackIcon);
        feedback.addView(feedbackText);
        feedback.setVisibility(View.GONE);
        root.addView(feedback, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        ((FrameLayout.LayoutParams) feedback.getLayoutParams()).topMargin = Ui.dp(this, 48);

        // Bedienelemente
        FrameLayout c = new FrameLayout(this);
        c.setBackgroundColor(0x66000000);
        controls = c;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), 0);
        ImageView back = sym("back", 52);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        titleView = Ui.text(this, "", 17, Color.WHITE, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView speed = txtBtn("1×");
        speed.setOnClickListener(v -> speedDialog(speed));
        top.addView(speed);
        ImageView tracks = sym("subtitles", 52);
        tracks.setOnClickListener(v -> trackDialog());
        top.addView(tracks);
        ImageView sleep = sym("sleep", 52);
        sleep.setOnClickListener(v -> sleepDialog());
        top.addView(sleep);
        ImageView chap = sym("list", 52);
        chap.setOnClickListener(v -> chapterDialog());
        top.addView(chap);
        ImageView aspect = sym("fit", 52);
        aspect.setOnClickListener(v -> {
            boolean fit = pv.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FIT;
            pv.setResizeMode(fit ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            flash("fit", fit ? "Bild füllen" : "Bild einpassen");
        });
        top.addView(aspect);
        lockBtn = sym("lockOpen", 52);
        lockBtn.setOnClickListener(v -> {
            lockedControls = true;
            hideControls();
            flash("lock", "Gesperrt – zum Entsperren tippen");
        });
        top.addView(lockBtn);
        c.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Mitte: -10 / Pause / +10
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        ImageView rew = sym("rewind", 72);
        rew.setOnClickListener(v -> { seekBy(-10000); showControls(); });
        playPause = sym("pause", 88);
        playPause.setBackground(Ui.gradient(this, 44));
        int pp = Ui.dp(this, 24);
        playPause.setPadding(pp, pp, pp, pp);
        playPause.setOnClickListener(v -> togglePlay());
        ImageView fwd = sym("forward", 72);
        fwd.setOnClickListener(v -> { seekBy(10000); showControls(); });
        for (ImageView t : new ImageView[]{rew, playPause, fwd}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(t.getLayoutParams().width, t.getLayoutParams().height);
            lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            mid.addView(t, lp);
        }
        c.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Unten: Zeit + Leiste
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        posView = Ui.text(this, "0:00", 13, Color.WHITE, false);
        durView = Ui.text(this, "0:00", 13, Color.WHITE, false);
        seek = new SeekBar(this);
        seek.setMinimumHeight(Ui.dp(this, 48));
        seek.getProgressDrawable().setTint(Ui.ACCENT2);
        seek.getThumb().setTint(Color.WHITE);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) { posView.setText(fmt(p * 1000L)); h.removeCallbacks(hideRun); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                scrubbing = false;
                if (player != null) userSeek(s.getProgress() * 1000L);
                showControls();
            }
        });
        bottom.addView(posView);
        bottom.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(durView);
        c.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(c, new FrameLayout.LayoutParams(-1, -1));
        // damit Tippen auf leere Stellen der Bedienelemente weiter die Gesten bedient
        c.setOnTouchListener((v, e) -> touch.dispatchTouchEvent(e));
        controlsVisible = true;

        skipIntro = Ui.button(this, "next", "Vorspann überspringen", false);
        skipIntro.setBackground(Ui.outlined(this, 0xE60A0D12, 0x55FFFFFF, 26));
        skipIntro.setVisibility(View.GONE);
        skipIntro.setOnClickListener(v -> { if (player != null && introEndMs > 0) userSeek(introEndMs); });
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sl.setMargins(0, 0, Ui.dp(this, 24), Ui.dp(this, 80));
        root.addView(skipIntro, sl);

        outroBtn = Ui.button(this, "next", "Abspann überspringen", false);
        outroBtn.setBackground(Ui.outlined(this, 0xE60A0D12, 0x55FFFFFF, 26));
        outroBtn.setVisibility(View.GONE);
        outroBtn.setOnClickListener(v -> {
            if (nextItem != null) playNext();
            else if (player != null && outroEndMs > 0) userSeek(Math.min(outroEndMs, Math.max(duration() - 500, 0)));
        });
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        ol.setMargins(0, 0, Ui.dp(this, 24), Ui.dp(this, 80));
        root.addView(outroBtn, ol);

        nextBtn = Ui.button(this, "next", "Nächste Folge", true);
        nextBtn.setVisibility(View.GONE);
        nextBtn.setOnClickListener(v -> playNext());
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        nl.setMargins(0, 0, Ui.dp(this, 24), Ui.dp(this, 80));
        root.addView(nextBtn, nl);

        immersive();
        scheduleHide();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    // ------------------------------------------------------------ Laden und Abspielen

    private void load(String id, long startTicks) {
        io.execute(() -> {
            try {
                final boolean offline = localFile != null;
                JfItem full = offline ? JfItem.from(new JSONObject(getIntent().getStringExtra("json"))) : jf.item(id);
                long intro = offline ? 0 : jf.introEnd(full);
                long[] outro = offline ? null : jf.outro(full);
                JfItem nx = null;
                if (!offline && "Episode".equals(full.type)) {
                    try { nx = jf.nextEpisode(full); } catch (Exception ignored) { }
                }
                final JfItem n = nx;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    item = full;
                    nextItem = n;
                    introEndMs = intro / TICKS_PER_MS;
                    outroStartMs = outro != null ? outro[0] / TICKS_PER_MS : 0;
                    outroEndMs = outro != null ? outro[1] / TICKS_PER_MS : 0;
                    transcoding = getSharedPreferences("app", MODE_PRIVATE).getBoolean("alwaysTranscode", false);
                    titleView.setText(full.title().equals(full.name) ? full.name
                            : (full.seriesName.isEmpty() ? "" : full.seriesName + " – ") + full.title());
                    startPlayback(startTicks / TICKS_PER_MS);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "Konnte nicht laden: " + e.getMessage(), Toast.LENGTH_LONG).show(); finish(); });
            }
        });
    }

    private void startPlayback(long startMs) {
        if (player == null) {
            DefaultRenderersFactory rf = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
            player = new ExoPlayer.Builder(this, rf).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA).build(), true);
            player.setHandleAudioBecomingNoisy(true);
            pv.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int s) {
                    updatePlayIcon();
                    if (s == Player.STATE_ENDED) onEnded();
                }
                @Override public void onIsPlayingChanged(boolean p) { updatePlayIcon(); scheduleHide(); }
                @Override public void onPlayerError(PlaybackException e) { onError(e); }
            });
            h.post(tick);
        }
        reportedStop = false;
        MediaItem.Builder mb = new MediaItem.Builder().setUri(localFile != null ? Uri.fromFile(new java.io.File(localFile))
                : Uri.parse(transcoding ? jf.playUrl(item) : jf.directUrl(item)));
        if (!transcoding && localFile == null) mb.setSubtitleConfigurations(externalSubs(item));
        player.setMediaItem(mb.build(), startMs);
        player.prepare();
        syncStartMs = startMs;
        player.setPlayWhenReady(!groupStart);
        started = false;
    }

    private java.util.List<MediaItem.SubtitleConfiguration> externalSubs(JfItem it) {
        ArrayList<MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        try {
            JSONArray sources = it.raw.optJSONArray("MediaSources");
            JSONArray streams = sources != null && sources.length() > 0 ? sources.getJSONObject(0).optJSONArray("MediaStreams")
                    : it.raw.optJSONArray("MediaStreams");
            for (int i = 0; streams != null && i < streams.length(); i++) {
                JSONObject s = streams.getJSONObject(i);
                if (!"Subtitle".equals(s.optString("Type")) || !s.optBoolean("IsExternal", false)) continue;
                String codec = s.optString("Codec", "").toLowerCase(Locale.ROOT);
                String mime = codec.equals("srt") || codec.equals("subrip") ? MimeTypes.APPLICATION_SUBRIP
                        : codec.equals("vtt") || codec.equals("webvtt") ? MimeTypes.TEXT_VTT
                        : codec.equals("ass") || codec.equals("ssa") ? MimeTypes.TEXT_SSA : null;
                if (mime == null) continue;
                String url = jf.server + "/Videos/" + it.id + "/" + it.id + "/Subtitles/" + s.optInt("Index") + "/Stream."
                        + (mime.equals(MimeTypes.APPLICATION_SUBRIP) ? "srt" : mime.equals(MimeTypes.TEXT_VTT) ? "vtt" : "ass")
                        + "?api_key=" + jf.token;
                String lang = s.optString("Language", null);
                out.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime)
                        .setLanguage(lang).setLabel(s.optString("DisplayTitle", lang)).build());
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void onError(PlaybackException e) {
        if (!transcoding && item != null && localFile == null) {
            // Direktwiedergabe geht nicht (z. B. Ton wie DTS/TrueHD): Server wandelt um
            long pos = player.getCurrentPosition();
            transcoding = true;
            Toast.makeText(this, "Wird vom Server umgewandelt …", Toast.LENGTH_SHORT).show();
            startPlayback(pos);
        } else {
            new AlertDialog.Builder(this).setTitle("Wiedergabefehler")
                    .setMessage(e.getErrorCodeName() + "\n" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()))
                    .setPositiveButton("Schließen", (d, w) -> finish()).show();
        }
    }

    private void onEnded() {
        report(2);
        if (nextItem != null) playNext(); else finish();
    }

    private void playNext() {
        if (nextItem == null || switching) return;
        switching = true;
        report(2);
        String id = nextItem.id;
        nextBtn.setVisibility(View.GONE);
        skipIntro.setVisibility(View.GONE);
        player.pause();
        io.execute(() -> {
            try {
                JfItem full = jf.item(id);
                long intro = jf.introEnd(full);
                JfItem nx = null;
                try { nx = jf.nextEpisode(full); } catch (Exception ignored) { }
                final JfItem n = nx;
                runOnUiThread(() -> {
                    item = full;
                    nextItem = n;
                    introEndMs = intro / TICKS_PER_MS;
                    titleView.setText((full.seriesName.isEmpty() ? "" : full.seriesName + " – ") + full.title());
                    switching = false;
                    startPlayback(0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { switching = false; finish(); });
            }
        });
    }

    // ------------------------------------------------------------ Takt (Anzeige, Meldungen)

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long dur = duration();
                long pos = player.getCurrentPosition();
                if (!scrubbing) {
                    seek.setMax((int) (dur / 1000));
                    seek.setProgress((int) (pos / 1000));
                    seek.setSecondaryProgress((int) (player.getBufferedPosition() / 1000));
                    posView.setText(fmt(pos));
                }
                durView.setText(fmt(dur));
                boolean inIntro = introEndMs > 0 && pos < introEndMs && pos > 2000 && introEndMs < dur;
                skipIntro.setVisibility(inIntro && !switching ? View.VISIBLE : View.GONE);
                boolean inOutro = outroEndMs > outroStartMs && pos >= outroStartMs && pos < outroEndMs && !switching;
                outroBtn.setVisibility(inOutro ? View.VISIBLE : View.GONE);
                boolean nearEnd = nextItem != null && dur > 0 && dur - pos < 25000 && pos > 60000 && !inOutro;
                nextBtn.setVisibility(nearEnd ? View.VISIBLE : View.GONE);
                if (!started && player.getPlaybackState() == Player.STATE_READY) {
                    started = true;
                    report(0);
                    if (groupStart) {
                        groupStart = false;
                        long sm = syncStartMs;
                        player.pause();
                        player.seekTo(sm);
                        h.postDelayed(() -> SyncPlay.get(PlayerActivity.this).ready(sm, false), 600);
                    }
                }
                if (player.isPlaying() && System.currentTimeMillis() - lastReport > 10000) report(1);
            }
            h.postDelayed(this, 500);
        }
    };

    /** 0 = Start, 1 = Fortschritt, 2 = Ende. Laeuft im Hintergrund. */
    private void report(int kind) {
        if (item == null || player == null) return;
        if (kind == 2) { if (reportedStop) return; reportedStop = true; }
        lastReport = System.currentTimeMillis();
        final JfItem it = item;
        final long ticks = player.getCurrentPosition() * TICKS_PER_MS;
        final boolean paused = !player.isPlaying();
        io.execute(() -> {
            if (kind == 0) jf.reportStart(it, ticks);
            else if (kind == 1) jf.reportProgress(it, ticks, paused);
            else jf.reportStop(it, ticks);
        });
    }

    // ------------------------------------------------------------ Bedienung

    private long duration() {
        long d = player != null ? player.getDuration() : C.TIME_UNSET;
        if (d == C.TIME_UNSET || d <= 0) d = item != null ? item.runTicks / TICKS_PER_MS : 0;
        return Math.max(d, 0);
    }

    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }

    private void seekBy(long ms) {
        if (player == null) return;
        userSeek(clamp(player.getCurrentPosition() + ms, 0, Math.max(duration() - 1000, 0)));
    }

    // ---- Gemeinsam schauen: Eingaben gehen an die Gruppe, ausgefuehrt wird, was der Server befiehlt

    private boolean grouped() { return SyncPlay.get(this).inGroup(); }

    private void userSeek(long ms) {
        if (player == null) return;
        if (grouped()) SyncPlay.get(this).seek(ms); else player.seekTo(ms);
    }

    /** Befehl der Gruppe (Pause, Unpause, Seek, Stop). */
    private void syncCommand(String c, long posMs, long delayMs) {
        if (player == null || isFinishing()) return;
        switch (c) {
            case "Pause":
                player.pause();
                player.seekTo(posMs);
                break;
            case "Unpause":
                player.seekTo(posMs);
                h.postDelayed(() -> { if (player != null) player.play(); }, Math.max(1, delayMs));
                break;
            case "Seek":
                player.pause();
                player.seekTo(posMs);
                h.postDelayed(() -> { if (grouped()) SyncPlay.get(this).ready(posMs, false); }, 500);
                break;
            case "Stop":
                finish();
                break;
            default:
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (grouped()) {
            if (player.isPlaying()) SyncPlay.get(this).pause(); else SyncPlay.get(this).unpause();
        } else if (player.isPlaying()) player.pause(); else player.play();
        showControls();
    }

    private void updatePlayIcon() {
        if (player != null) playPause.setImageDrawable(Icons.drawable(player.isPlaying() || player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED ? "pause" : "play", Color.WHITE));
    }

    private final Runnable hideRun = this::hideControls;

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        controlsVisible = true;
        scheduleHide();
    }

    private void hideControls() {
        controls.setVisibility(View.GONE);
        controlsVisible = false;
        immersive();
    }

    private void scheduleHide() {
        h.removeCallbacks(hideRun);
        if (player != null && player.isPlaying() && controlsVisible) h.postDelayed(hideRun, 3500);
    }

    private void flash(String icon, String s) {
        feedbackIcon.setImageDrawable(Icons.drawable(icon, Color.WHITE));
        feedbackText.setText(s);
        feedback.setVisibility(View.VISIBLE);
    }

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (brightness < 0) brightness = lp.screenBrightness >= 0 ? lp.screenBrightness : 0.5f;
        brightness = Math.max(0.02f, Math.min(1f, brightness - delta));
        lp.screenBrightness = brightness;
        getWindow().setAttributes(lp);
        flash("brightness", Math.round(brightness * 100) + " %");
    }

    private void adjustVolume(float delta) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volAcc -= delta * max;
        int step = (int) volAcc;
        if (step != 0) {
            volAcc -= step;
            int v = Math.max(0, Math.min(max, audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        }
        flash("volume", Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max) + " %");
    }

    private static String fmt(long ms) {
        long s = Math.max(ms, 0) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------ Dialoge

    private void speedDialog(TextView label) {
        final float[] v = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] n = new String[v.length];
        for (int i = 0; i < v.length; i++) n[i] = v[i] + "×";
        new AlertDialog.Builder(this).setTitle("Geschwindigkeit").setItems(n, (d, i) -> {
            player.setPlaybackParameters(new PlaybackParameters(v[i]));
            label.setText(v[i] == 1f ? "1×" : v[i] + "×");
        }).show();
    }

    private final Runnable sleepRun = () -> {
        if (player != null) player.pause();
        Toast.makeText(this, "Schlaf-Timer: Wiedergabe angehalten", Toast.LENGTH_LONG).show();
    };

    private void sleepDialog() {
        final int[] min = {0, 15, 30, 45, 60, 90};
        String[] n = new String[min.length];
        for (int i = 0; i < min.length; i++) n[i] = min[i] == 0 ? "Aus" : min[i] + " Minuten";
        new AlertDialog.Builder(this).setTitle("Schlaf-Timer").setItems(n, (d, i) -> {
            h.removeCallbacks(sleepRun);
            if (min[i] > 0) {
                h.postDelayed(sleepRun, min[i] * 60000L);
                flash("sleep", "Pause in " + min[i] + " Min");
            } else flash("sleep", "Timer aus");
        }).show();
    }

    private void chapterDialog() {
        if (item == null || item.chapters.isEmpty()) { Toast.makeText(this, "Keine Kapitel vorhanden", Toast.LENGTH_SHORT).show(); return; }
        String[] n = new String[item.chapters.size()];
        for (int i = 0; i < n.length; i++) {
            JfItem.Chapter c = item.chapters.get(i);
            n[i] = fmt(c.start / TICKS_PER_MS) + "   " + (c.name.isEmpty() ? "Kapitel " + (i + 1) : c.name);
        }
        new AlertDialog.Builder(this).setTitle("Kapitel")
                .setItems(n, (d, i) -> userSeek(item.chapters.get(i).start / TICKS_PER_MS)).show();
    }

    private String trackName(Format f, int i) {
        String lang = f.language != null && !f.language.equals("und") ? new Locale(f.language).getDisplayLanguage(Locale.GERMAN) : "";
        String s = f.label != null ? f.label : lang;
        if (s.isEmpty()) s = "Spur " + (i + 1);
        if (f.channelCount > 0 && f.sampleMimeType != null && f.sampleMimeType.startsWith("audio"))
            s += " (" + (f.channelCount > 2 ? f.channelCount - 1 + ".1" : f.channelCount == 2 ? "Stereo" : "Mono") + ")";
        return s;
    }

    /** Ton- und Untertitelwahl als Dialog mit Auswahlknoepfen (je ein Abschnitt). */
    private void trackDialog() {
        if (player == null) return;
        if (transcoding) {
            Toast.makeText(this, "Bei Umwandlung durch den Server ist keine Spurwahl möglich", Toast.LENGTH_LONG).show();
            return;
        }
        Tracks tr = player.getCurrentTracks();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final AlertDialog[] dlg = new AlertDialog[1];
        boolean any = false;

        android.widget.RadioGroup audioGroup = new android.widget.RadioGroup(this);
        int ai = 0;
        for (Tracks.Group g : tr.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0) continue;
            final TrackGroup tg = g.getMediaTrackGroup();
            android.widget.RadioButton rb = radio(trackName(g.getTrackFormat(0), ai++), g.isSelected());
            rb.setOnClickListener(v -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(tg, 0)).build());
                dlg[0].dismiss();
            });
            audioGroup.addView(rb);
        }
        if (audioGroup.getChildCount() > 0) {
            any = true;
            box.addView(sectionTitle("Ton"));
            box.addView(audioGroup);
        }

        android.widget.RadioGroup subGroup = new android.widget.RadioGroup(this);
        boolean textOn = false;
        int si = 0;
        for (Tracks.Group g : tr.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT || g.length == 0) continue;
            textOn |= g.isSelected();
            final TrackGroup tg = g.getMediaTrackGroup();
            android.widget.RadioButton rb = radio(trackName(g.getTrackFormat(0), si++), g.isSelected());
            rb.setOnClickListener(v -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(tg, 0)).build());
                dlg[0].dismiss();
            });
            subGroup.addView(rb);
        }
        if (subGroup.getChildCount() > 0) {
            any = true;
            android.widget.RadioButton off = radio("Aus", !textOn);
            off.setOnClickListener(v -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                dlg[0].dismiss();
            });
            subGroup.addView(off, 0);
            box.addView(sectionTitle("Untertitel"));
            box.addView(subGroup);
        }
        if (!any) { Toast.makeText(this, "Keine Ton-/Untertitelspuren", Toast.LENGTH_SHORT).show(); return; }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Ton & Untertitel").setView(sv).show();
    }

    private TextView sectionTitle(String s) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, true);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        return t;
    }

    private android.widget.RadioButton radio(String label, boolean checked) {
        android.widget.RadioButton rb = new android.widget.RadioButton(this);
        rb.setText(label);
        rb.setChecked(checked);
        rb.setMinHeight(Ui.dp(this, 48));
        rb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        return rb;
    }

    // ------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && player != null && player.isPlaying()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        if (in) hideControls(); else showControls();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && !(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode())) {
            player.pause();
            report(1);
        }
    }

    // ------------------------------------------------------------ Controller

    private long stickNext;

    /** A/Start = Pause, links/rechts oder L1/R1 = 10 s spulen, oben/unten = Lautstaerke, X = Ton/Untertitel, Y = Kapitel. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) togglePlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BUTTON_L1:
                seekBy(-10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_R1:
                seekBy(10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (e.getRepeatCount() == 0) trackDialog();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (e.getRepeatCount() == 0 && item != null && !item.chapters.isEmpty()) chapterDialog();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                onBackPressed();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            long now = System.currentTimeMillis();
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            if (now >= stickNext) {
                if (x > 0.6f) { seekBy(10000); stickNext = now + 400; }
                else if (x < -0.6f) { seekBy(-10000); stickNext = now + 400; }
                else if (y < -0.6f) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); stickNext = now + 250; }
                else if (y > 0.6f) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); stickNext = now + 250; }
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (lockedControls) { lockedControls = false; showControls(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;
        if (SyncPlay.cmd != null) SyncPlay.cmd = null;
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            report(2);
            pv.setPlayer(null);
            player.release();
            player = null;
        }
        io.shutdown();
    }
}
