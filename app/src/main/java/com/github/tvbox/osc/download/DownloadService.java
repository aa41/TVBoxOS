package com.github.tvbox.osc.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService extends Service {
    private static final String CHANNEL = "video_downloads";
    private static final int NOTIFICATION = 3701;
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private volatile boolean working;
    private volatile int latestStartId;
    private static volatile String activeId;
    public static boolean isActive(String id) { return id != null && id.equals(activeId); }

    public static void deleteFiles(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        dir.delete();
    }
    private DownloadStore store;

    public static void wake(Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = DownloadStore.get(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "视频下载", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION, notification("等待下载", 0, 0));
        synchronized (this) {
            latestStartId = startId;
            if (!working) {
                working = true;
                queue.execute(this::process);
            }
        }
        return START_STICKY;
    }

    private void process() {
        try {
            while (true) {
                DownloadStore.Task next = null;
                List<DownloadStore.Task> tasks = store.list();
                for (DownloadStore.Task task : tasks) if (DownloadStore.QUEUED.equals(task.state)) {
                    next = task; break;
                }
                if (next == null) break;
                if (Hawk.get(HawkConfig.DOWNLOAD_WIFI_ONLY, false) && !onWifi()) {
                    notifyProgress("等待 Wi-Fi 连接", 0, 0);
                    Thread.sleep(10000);
                    continue;
                }
                final DownloadStore.Task current = store.startIfQueued(next.id);
                if (current == null) continue;
                activeId = current.id;
                try {
                    if (TextUtils.isEmpty(current.url) && !TextUtils.isEmpty(current.episodeUrl)) {
                        notifyProgress("解析 · " + current.title, 0, 0);
                        EpisodeResolver.Video video = EpisodeResolver.resolve(this, current, () -> {
                            DownloadStore.Task latest = store.getTask(current.id);
                            return latest == null || !DownloadStore.RUNNING.equals(latest.state);
                        });
                        DownloadStore.Task resolved = store.getTask(current.id);
                        if (resolved == null || !DownloadStore.RUNNING.equals(resolved.state))
                            throw new IOException("下载已暂停");
                        resolved.url = video.url;
                        resolved.headers = new org.json.JSONObject(video.headers);
                        store.update(resolved);
                        current.url = resolved.url;
                        current.headers = resolved.headers;
                    }
                    String path = new VideoDownloader(current.url, current.headers, store.directory(current),
                            Hawk.get(HawkConfig.DOWNLOAD_THREADS, 4), (bytes, total, items, count) -> {
                        DownloadStore.Task latest = store.getTask(current.id);
                        if (latest == null || !DownloadStore.RUNNING.equals(latest.state)) throw new IOException("下载已暂停");
                        latest.bytes = bytes;
                        if (total > 0) latest.total = total;
                        latest.items = items;
                        if (count > 0) latest.itemCount = count;
                        store.update(latest);
                        notifyProgress(latest.title, latest.bytes, latest.total);
                    }, () -> {
                        DownloadStore.Task latest = store.getTask(current.id);
                        return latest == null || !DownloadStore.RUNNING.equals(latest.state)
                                || Hawk.get(HawkConfig.DOWNLOAD_WIFI_ONLY, false) && !onWifi();
                    }).run();
                    DownloadStore.Task latest = store.getTask(current.id);
                    if (latest != null && DownloadStore.RUNNING.equals(latest.state)) {
                        latest.path = path; latest.state = DownloadStore.DONE;
                        store.update(latest);
                    }
                } catch (Exception e) {
                    DownloadStore.Task latest = store.getTask(current.id);
                    if (latest != null && DownloadStore.RUNNING.equals(latest.state)) {
                        latest.state = Hawk.get(HawkConfig.DOWNLOAD_WIFI_ONLY, false) && !onWifi()
                                ? DownloadStore.QUEUED : DownloadStore.FAILED;
                        latest.error = errorMessage(e);
                        store.update(latest);
                    }
                } finally {
                    if (store.getTask(current.id) == null) deleteFiles(store.directory(current));
                }
                activeId = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                working = false;
                boolean pending = false;
                for (DownloadStore.Task task : store.list()) if (DownloadStore.QUEUED.equals(task.state)) pending = true;
                if (pending && !queue.isShutdown()) {
                    working = true;
                    queue.execute(this::process);
                } else {
                    stopForeground(true);
                    stopSelf(latestStartId);
                }
            }
        }
    }

    private boolean onWifi() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        return network != null && network.isConnected() && network.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private static String errorMessage(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof UnknownHostException) return "无法连接服务器，请检查链接";
        if (cause instanceof ConnectException) return "连接失败，请检查网络或链接";
        if (cause instanceof SocketTimeoutException) return "连接超时，请稍后重试";
        String message = error.getMessage();
        if (TextUtils.isEmpty(message)) message = cause.getMessage();
        return TextUtils.isEmpty(message) ? "下载失败，请重试" : message;
    }

    private Notification notification(String title, long bytes, long total) {
        Intent intent = new Intent(this, DownloadActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.app_icon).setContentTitle(title).setContentIntent(pending)
                .setOngoing(true).setOnlyAlertOnce(true);
        if (total > 0) builder.setProgress(100, (int) Math.min(100, bytes * 100 / total), false)
                .setContentText(bytes * 100 / total + "%");
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void notifyProgress(String title, long bytes, long total) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(title, bytes, total));
    }

    @Override public void onDestroy() {
        queue.shutdownNow();
        activeId = null;
        for (DownloadStore.Task task : store.list()) if (DownloadStore.RUNNING.equals(task.state)) {
            task.state = DownloadStore.QUEUED; store.update(task);
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
