package com.github.tvbox.osc.ui.activity;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.download.DownloadStore;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.util.PlayerHelper;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * Plays downloaded videos with the same controller and interaction model as online VOD.
 * Online-only actions are removed by {@link VodController#setOfflineMode(boolean, boolean)}.
 */
public class DownloadPlayerActivity extends BaseActivity {
    private static final String PREFS = "local_playback";
    private static final String PLAYER_CONFIG = "player_config";
    private static final String STATE_TASK_ID = "download_player_task_id";

    private final List<DownloadStore.Task> episodes = new ArrayList<>();
    private MyVideoView videoView;
    private VodController controller;
    private ProgressBar loading;
    private View loadError;
    private TextView loadTip;
    private SharedPreferences preferences;
    private JSONObject playerConfig;
    private DownloadStore.Task currentTask;
    private File video;
    private int currentIndex = -1;
    private String requestedTaskId;
    private String fallbackPath;
    private String fallbackTitle;

    @Override
    protected void onCreate(Bundle state) {
        requestedTaskId = state == null ? getIntent().getStringExtra("taskId")
                : state.getString(STATE_TASK_ID, getIntent().getStringExtra("taskId"));
        fallbackPath = getIntent().getStringExtra("path");
        fallbackTitle = getIntent().getStringExtra("title");
        super.onCreate(state);
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        videoView = findViewById(R.id.mVideoView);
        loading = findViewById(R.id.play_loading);
        loadError = findViewById(R.id.play_load_error);
        loadTip = findViewById(R.id.play_load_tip);

        currentTask = findRequestedTask();
        String path = currentTask == null ? fallbackPath : currentTask.path;
        if (TextUtils.isEmpty(path) || !(video = new File(path)).isFile()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        loadEpisodes();
        playerConfig = readPlayerConfig();
        controller = new VodController(this);
        controller.setCanChangePosition(true);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setHasDanmu(false);
        controller.setPlayerConfig(playerConfig);
        controller.setListener(createControlListener());

        View.OnTouchListener passThroughTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                return controller != null && controller.onTouchEvent(event);
            }
        };
        loadTip.setOnTouchListener(passThroughTouch);
        loadError.setOnTouchListener(passThroughTouch);

        videoView.setProgressManager(new ProgressManager() {
            @Override
            public void saveProgress(String key, long progress) {
                if (!TextUtils.isEmpty(key)) preferences.edit().putLong(key, progress).apply();
            }

            @Override
            public long getSavedProgress(String key) {
                return TextUtils.isEmpty(key) ? 0 : preferences.getLong(key, 0);
            }
        });
        videoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PREPARING) {
                    showStatus("正在打开离线视频…", true, false);
                } else if (playState == VideoView.STATE_PREPARED
                        || playState == VideoView.STATE_PLAYING
                        || playState == VideoView.STATE_BUFFERED) {
                    hideStatus();
                    if (playState == VideoView.STATE_PLAYING) videoView.clearArtwork();
                } else if (playState == VideoView.STATE_ERROR) {
                    showStatus("视频文件无法播放", false, true);
                }
            }
        });
        videoView.setVideoController(controller);
        startCurrentVideo();
    }

    private DownloadStore.Task findRequestedTask() {
        DownloadStore store = DownloadStore.get(this);
        DownloadStore.Task task = TextUtils.isEmpty(requestedTaskId) ? null : store.getTask(requestedTaskId);
        if (task != null) return task;
        if (!TextUtils.isEmpty(fallbackPath)) {
            for (DownloadStore.Task item : store.list()) {
                if (fallbackPath.equals(item.path)) return item;
            }
        }
        return null;
    }

    private void loadEpisodes() {
        episodes.clear();
        if (currentTask == null || TextUtils.isEmpty(currentTask.collection)) return;
        for (DownloadStore.Task item : DownloadStore.get(this).list()) {
            if (!DownloadStore.DONE.equals(item.state)
                    || !currentTask.collection.equals(item.collection)
                    || !sameValue(currentTask.sourceKey, item.sourceKey)
                    || !sameValue(currentTask.playFlag, item.playFlag)
                    || TextUtils.isEmpty(item.path)
                    || !new File(item.path).isFile()) {
                continue;
            }
            if (currentTask.id.equals(item.id)) currentIndex = episodes.size();
            episodes.add(item);
        }
    }

    private boolean sameValue(String left, String right) {
        return TextUtils.equals(left == null ? "" : left, right == null ? "" : right);
    }

    private JSONObject readPlayerConfig() {
        JSONObject config;
        try {
            config = new JSONObject(preferences.getString(PLAYER_CONFIG, "{}"));
        } catch (Exception ignored) {
            config = new JSONObject();
        }
        try {
            config.put("pl", 2);
            if (!config.has("pr")) config.put("pr", 0);
            if (!config.has("ijk")) config.put("ijk", "硬解码");
            if (!config.has("sc")) config.put("sc", 0);
            if (!config.has("sp")) config.put("sp", 1.0f);
            config.put("st", 0);
            config.put("et", 0);
        } catch (Exception ignored) {
        }
        return config;
    }

    private VodController.VodControlListener createControlListener() {
        return new VodController.VodControlListener() {
            @Override
            public void downloadCurrent() {
            }

            @Override
            public void playNext(boolean clearProgress) {
                if (clearProgress) clearCurrentProgress();
                if (currentIndex >= 0 && currentIndex + 1 < episodes.size()) {
                    playEpisode(currentIndex + 1);
                } else if (clearProgress) {
                    showStatus("本集已播放完毕", false, false);
                }
            }

            @Override
            public void playPre() {
                if (currentIndex > 0) playEpisode(currentIndex - 1);
            }

            @Override
            public void showEpisodeDialog() {
                DownloadPlayerActivity.this.showEpisodeDialog();
            }

            @Override
            public void prepared() {
                videoView.prepared();
                hideStatus();
            }

            @Override
            public void changeParse(ParseBean pb) {
            }

            @Override
            public void updatePlayerCfg() {
                preferences.edit().putString(PLAYER_CONFIG, playerConfig.toString()).apply();
            }

            @Override
            public void replay(boolean resetPosition) {
                if (video == null || !video.isFile()) {
                    showStatus("下载文件不存在", false, true);
                    return;
                }
                hideStatus();
                if (resetPosition) clearCurrentProgress();
                videoView.replay(resetPosition);
                controller.resetSpeed();
            }

            @Override
            public void errReplay() {
                showStatus("视频文件无法播放", false, true);
            }

            @Override
            public void selectSubtitle() {
            }

            @Override
            public void selectAudioTrack() {
            }

            @Override
            public void selectVideoTrack() {
            }

            @Override
            public void showDanmuSetting() {
            }

            @Override
            public boolean toggleDanmu() {
                return false;
            }

            @Override
            public void searchDanmuUi(boolean longClick) {
            }

            @Override
            public void startPlayUrl(String url, HashMap<String, String> headers) {
            }

            @Override
            public void onM3u8ProxyUrl(String proxyUrl, String sourceUrl) {
            }

            @Override
            public void clickCast() {
            }

            @Override
            public void setAllowSwitchPlayer(boolean isAllow) {
            }
        };
    }

    private void startCurrentVideo() {
        PlayerHelper.updateCfg(videoView, playerConfig, 2);
        if (currentTask != null && !TextUtils.isEmpty(currentTask.cover)) {
            videoView.setArtwork(currentTask.cover);
        } else {
            videoView.clearArtwork();
        }
        videoView.setProgressKey(video.getAbsolutePath());
        videoView.setUrl(Uri.fromFile(video).toString());
        updateControllerInfo();
        showStatus("正在打开离线视频…", true, false);
        videoView.start();
        controller.resetSpeed();
    }

    private void playEpisode(int index) {
        if (index < 0 || index >= episodes.size()) return;
        DownloadStore.Task target = episodes.get(index);
        File targetFile = TextUtils.isEmpty(target.path) ? null : new File(target.path);
        if (targetFile == null || !targetFile.isFile()) {
            Toast.makeText(this, "该集下载文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        videoView.release();
        currentIndex = index;
        currentTask = target;
        requestedTaskId = target.id;
        video = targetFile;
        startCurrentVideo();
    }

    private void updateControllerInfo() {
        String title = currentTask == null ? fallbackTitle : currentTask.title;
        if (TextUtils.isEmpty(title)) title = video.getName();
        controller.setTitle(title);
        controller.setOfflineMode(currentIndex > 0,
                currentIndex >= 0 && currentIndex + 1 < episodes.size());
        controller.initLandscapePortraitBtnInfo();
    }

    private void showEpisodeDialog() {
        if (episodes.size() < 2) {
            Toast.makeText(this, "当前没有其他已下载剧集", Toast.LENGTH_SHORT).show();
            return;
        }
        SelectDialog<DownloadStore.Task> dialog = new SelectDialog<>(this);
        dialog.setTip("选择已下载剧集");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<DownloadStore.Task>() {
            @Override
            public void click(DownloadStore.Task value, int position) {
                dialog.dismiss();
                if (position != currentIndex) playEpisode(position);
            }

            @Override
            public String getDisplay(DownloadStore.Task value) {
                return TextUtils.isEmpty(value.episode) ? value.title : value.episode;
            }
        }, new DiffUtil.ItemCallback<DownloadStore.Task>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull DownloadStore.Task oldItem,
                                           @NonNull @NotNull DownloadStore.Task newItem) {
                return TextUtils.equals(oldItem.id, newItem.id);
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull DownloadStore.Task oldItem,
                                              @NonNull @NotNull DownloadStore.Task newItem) {
                return TextUtils.equals(oldItem.id, newItem.id)
                        && TextUtils.equals(oldItem.title, newItem.title)
                        && TextUtils.equals(oldItem.path, newItem.path);
            }
        }, episodes, Math.max(currentIndex, 0));
        dialog.show();
    }

    private void clearCurrentProgress() {
        if (video != null) preferences.edit().remove(video.getAbsolutePath()).apply();
    }

    private void showStatus(String text, boolean showLoading, boolean showError) {
        if (loadTip == null) return;
        loadTip.setText(text);
        loadTip.setVisibility(View.VISIBLE);
        loading.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        loadError.setVisibility(showError ? View.VISIBLE : View.GONE);
    }

    private void hideStatus() {
        if (loadTip == null) return;
        loadTip.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);
        loadError.setVisibility(View.GONE);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (currentTask != null) state.putString(STATE_TASK_ID, currentTask.id);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        if (videoView != null) videoView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null) videoView.resume();
    }

    @Override
    protected void onDestroy() {
        if (videoView != null) {
            videoView.release();
            videoView = null;
        }
        if (controller != null) controller.stopOther();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (controller != null) controller.initLandscapePortraitBtnInfo();
    }

    @Override
    public void onBackPressed() {
        if (controller != null && controller.onBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && controller != null && controller.onKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event != null && controller != null && controller.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event != null && controller != null && controller.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }
}
