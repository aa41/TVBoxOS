package com.github.tvbox.osc.ui.activity;

import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.File;

public class DownloadPlayerActivity extends AppCompatActivity {
    private SimpleExoPlayer player;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PlayerView view = new PlayerView(this);
        view.setUseController(true);
        setContentView(view);
        String path = getIntent().getStringExtra("path");
        if (path == null || !new File(path).isFile()) { finish(); return; }
        player = new SimpleExoPlayer.Builder(this).build();
        view.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));
        player.prepare(); player.play();
    }
    @Override protected void onDestroy() {
        if (player != null) player.release();
        super.onDestroy();
    }
}
