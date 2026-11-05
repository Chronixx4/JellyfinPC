package de.marvin.jellyfin.mobile;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;

import de.marvin.jellyfin.core.Jf;
import de.marvin.jellyfin.core.JfItem;

/** Stummes Vorschau-Video im Banner der Startseite (wie HeroVideo der PC-App). Blendet erst ein, wenn Bilder kommen. */
final class HeroPreview {
    private final Context ctx;
    private final FrameLayout holder;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean dead;

    /** Legt die Videoflaeche (zunaechst unsichtbar) in {@code parent} und startet nach {@code delayMs}. */
    HeroPreview(Context ctx, FrameLayout parent, int index, Jf jf, JfItem it, long delayMs) {
        this.ctx = ctx;
        holder = new FrameLayout(ctx);
        holder.setAlpha(0f);
        parent.addView(holder, index, new FrameLayout.LayoutParams(-1, -1));
        long startMs = it.resumeTicks > 0 ? it.resumeTicks / 10000 : (long) (it.runTicks / 10000 * 0.2);
        h.postDelayed(() -> start(jf.directUrl(it), startMs), delayMs);
    }

    private void start(String url, long startMs) {
        if (dead) return;
        try {
            AspectRatioFrameLayout arf = new AspectRatioFrameLayout(ctx);
            arf.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            TextureView tv = new TextureView(ctx);
            arf.addView(tv, new FrameLayout.LayoutParams(-1, -1));
            holder.addView(arf, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            player = new ExoPlayer.Builder(ctx).build();
            player.setVolume(0f);
            player.setVideoTextureView(tv);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize s) {
                    if (s.height > 0) arf.setAspectRatio(s.width * s.pixelWidthHeightRatio / s.height);
                }
                @Override public void onRenderedFirstFrame() {
                    if (!dead) holder.animate().alpha(1f).setDuration(900).start();
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e) { stop(); }
            });
            player.setMediaItem(new MediaItem.Builder().setUri(Uri.parse(url)).build(), startMs);
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.prepare();
            player.play();
        } catch (Throwable t) {
            stop();
        }
    }

    /** Video ausblenden und Player freigeben. */
    void stop() {
        dead = true;
        h.removeCallbacksAndMessages(null);
        holder.animate().alpha(0f).setDuration(200).start();
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
    }

    View view() { return holder; }
}
