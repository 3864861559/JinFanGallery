package com.jinfan.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

/**
 * 全屏播放：极简播放条 + 一条功能栏（发送 / 剪辑 / 收藏 / 删除 / 更多）。
 * 只用系统 MediaPlayer，不引第三方播放器；点视频区域显隐控制条。
 */
public class VideoPlayerActivity extends Activity implements SurfaceHolder.Callback {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";

    private static final String PREFS = "jinfan_video";
    private static final String KEY_FAV = "favorites";
    private static final int AUTO_HIDE_MS = 3200;

    private SurfaceView surfaceView;
    private View controls;
    private ImageButton playBtn;
    private ImageButton muteBtn;
    private SeekBar seekBar;
    private ImageView favIcon;
    private TextView dateView;

    private MediaPlayer player;
    private Uri uri;
    private boolean prepared;
    private boolean muted;
    private boolean userSeeking;
    private boolean controlsVisible = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && !userSeeking) {
                seekBar.setProgress(player.getCurrentPosition());
            }
            handler.postDelayed(this, 400);
        }
    };
    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) setControlsVisible(false, true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String raw = getIntent().getStringExtra(EXTRA_URI);
        if (raw == null) { finish(); return; }
        uri = Uri.parse(raw);

        surfaceView = findViewById(R.id.video_surface);
        controls = findViewById(R.id.video_controls);
        playBtn = findViewById(R.id.btn_video_play);
        muteBtn = findViewById(R.id.btn_video_mute);
        seekBar = findViewById(R.id.seek_video);
        favIcon = findViewById(R.id.ic_video_fav);
        dateView = findViewById(R.id.tv_video_date);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        dateView.setText(title == null ? "" : title);

        findViewById(R.id.btn_video_back).setOnClickListener(v -> finish());
        findViewById(R.id.video_root).setOnClickListener(v -> {
            setControlsVisible(!controlsVisible, true);
        });

        playBtn.setOnClickListener(v -> togglePlay());
        muteBtn.setOnClickListener(v -> toggleMute());
        findViewById(R.id.act_video_send).setOnClickListener(v -> share());
        findViewById(R.id.act_video_clip).setOnClickListener(v ->
                Toast.makeText(this, R.string.video_clip_todo, Toast.LENGTH_SHORT).show());
        findViewById(R.id.act_video_fav).setOnClickListener(v -> toggleFavorite());
        findViewById(R.id.act_video_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.act_video_more).setOnClickListener(this::showMore);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && player != null && prepared) player.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { userSeeking = false; }
        });

        surfaceView.getHolder().addCallback(this);
        refreshFavIcon();
    }

    // ------------------------------------------------------------ 播放器

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openPlayer(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releasePlayer();
    }

    private void openPlayer(SurfaceHolder holder) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setSurface(holder.getSurface());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                seekBar.setMax(Math.max(1, mp.getDuration()));
                fitSurface(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                playBtn.setImageResource(R.drawable.ic_video_pause);
                applyVolume();
                handler.post(tick);
                handler.postDelayed(hideControls, AUTO_HIDE_MS);
            });
            player.setOnCompletionListener(mp -> {
                playBtn.setImageResource(R.drawable.ic_video_play);
                setControlsVisible(true, true);
                handler.removeCallbacks(hideControls);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, R.string.video_open_failed, Toast.LENGTH_SHORT).show();
                finish();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, R.string.video_open_failed, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /** 按视频比例放进屏幕，保持居中、不拉伸 */
    private void fitSurface(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = Math.min(dm.widthPixels / (float) vw, dm.heightPixels / (float) vh);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(vw * scale), Math.round(vh * scale));
        lp.gravity = android.view.Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            playBtn.setImageResource(R.drawable.ic_video_play);
            setControlsVisible(true, true);
            handler.removeCallbacks(hideControls);
        } else {
            if (player.getCurrentPosition() >= player.getDuration() - 300) player.seekTo(0);
            player.start();
            playBtn.setImageResource(R.drawable.ic_video_pause);
            handler.removeCallbacks(hideControls);
            handler.postDelayed(hideControls, AUTO_HIDE_MS);
        }
    }

    private void toggleMute() {
        muted = !muted;
        applyVolume();
        muteBtn.setImageResource(muted ? R.drawable.ic_video_mute : R.drawable.ic_video_volume);
    }

    private void applyVolume() {
        if (player == null) return;
        float v = muted ? 0f : 1f;
        player.setVolume(v, v);
    }

    private void setControlsVisible(boolean visible, boolean animate) {
        controlsVisible = visible;
        float target = visible ? 1f : 0f;
        controls.animate().cancel();
        if (animate) {
            controls.animate().alpha(target).setDuration(180).start();
        } else {
            controls.setAlpha(target);
        }
        controls.setClickable(visible);
        if (visible) {
            handler.removeCallbacks(hideControls);
            if (player != null && player.isPlaying()) {
                handler.postDelayed(hideControls, AUTO_HIDE_MS);
            }
        }
    }

    // ------------------------------------------------------------ 功能

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.video_send)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.video_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private Set<String> favorites() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        return new HashSet<>(sp.getStringSet(KEY_FAV, new HashSet<>()));
    }

    private void toggleFavorite() {
        Set<String> favs = favorites();
        boolean added;
        if (favs.contains(uri.toString())) {
            favs.remove(uri.toString());
            added = false;
        } else {
            favs.add(uri.toString());
            added = true;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_FAV, favs).apply();
        refreshFavIcon();
        Toast.makeText(this, added ? R.string.video_fav_added : R.string.video_fav_removed,
                Toast.LENGTH_SHORT).show();
    }

    private void refreshFavIcon() {
        boolean fav = favorites().contains(uri.toString());
        favIcon.setImageResource(fav
                ? R.drawable.ic_video_heart_filled
                : R.drawable.ic_video_heart);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.video_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.video_delete, (d, w) -> deleteVideo())
                .show();
    }

    private void deleteVideo() {
        ContentResolver cr = getContentResolver();
        try {
            int n = cr.delete(uri, null, null);
            toastAndFinish(n > 0);
        } catch (SecurityException se) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    android.app.RecoverableSecurityException rse = (android.app.RecoverableSecurityException) se;
                    startIntentSenderForResult(
                            rse.getUserAction().getActionIntent().getIntentSender(), 7, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) { }
            }
            toastAndFinish(false);
        } catch (Exception e) {
            toastAndFinish(false);
        }
    }

    private void toastAndFinish(boolean ok) {
        Toast.makeText(this, ok ? R.string.video_delete_done : R.string.video_delete_failed,
                Toast.LENGTH_SHORT).show();
        if (ok) {
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7 && resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private void showMore(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.video_details);
        menu.getMenu().add(0, 2, 1, R.string.video_open_with);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showDetails();
            else openWith();
            return true;
        });
        menu.show();
    }

    private void showDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(uri.getLastPathSegment()).append('\n');
        if (player != null && prepared) {
            sb.append("时长：").append(formatTime(player.getDuration())).append('\n');
            sb.append("画面：").append(player.getVideoWidth()).append('×')
                    .append(player.getVideoHeight());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_details)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openWith() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
            setControlsVisible(true, true);
        } catch (Exception e) {
            Toast.makeText(this, R.string.video_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatTime(int ms) {
        int total = ms / 1000;
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", total / 60, total % 60);
    }

    // ------------------------------------------------------------ 生命周期

    private void releasePlayer() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideControls);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            playBtn.setImageResource(R.drawable.ic_video_play);
        }
        setControlsVisible(true, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    public static Intent intentFor(Activity from, Uri uri, String title) {
        return new Intent(from, VideoPlayerActivity.class)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title);
    }
}
