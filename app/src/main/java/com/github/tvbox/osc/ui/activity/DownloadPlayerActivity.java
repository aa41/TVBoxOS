package com.github.tvbox.osc.ui.activity;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.tvbox.osc.R;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import java.io.File;

public class DownloadPlayerActivity extends AppCompatActivity {
    private StyledPlayerView playerView;
    private SimpleExoPlayer player;
    private File video;
    private long resumePosition;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra("path");
        if (path == null || !(video = new File(path)).isFile()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_download_player);
        playerView = findViewById(R.id.local_player);
        playerView.setControllerAutoShow(true);
        playerView.setControllerShowTimeoutMs(4000);
        View toolbar = findViewById(R.id.local_player_toolbar);
        playerView.setControllerVisibilityListener((StyledPlayerView.ControllerVisibilityListener)
                visibility -> toolbar.setVisibility(visibility));
        findViewById(R.id.local_player_back).setOnClickListener(v -> finish());
        String title = getIntent().getStringExtra("title");
        ((TextView) findViewById(R.id.local_player_title)).setText(
                title == null || title.isEmpty() ? video.getName() : title);
        resumePosition = state != null ? state.getLong("playback_position", 0)
                : getPreferences().getLong(video.getAbsolutePath(), 0);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("playback_position", player != null ? player.getCurrentPosition() : resumePosition);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        if (video == null) return;
        player = new SimpleExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(DownloadPlayerActivity.this, "无法播放此视频", Toast.LENGTH_SHORT).show();
            }
        });
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)));
        player.prepare();
        if (resumePosition > 0) player.seekTo(resumePosition);
        player.play();
    }

    @Override protected void onStop() {
        if (player != null) {
            resumePosition = player.getCurrentPosition();
            long duration = player.getDuration();
            if (duration > 0 && duration - resumePosition < 10_000) resumePosition = 0;
            getPreferences().edit().putLong(video.getAbsolutePath(), resumePosition).apply();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences("local_playback", MODE_PRIVATE);
    }
}
