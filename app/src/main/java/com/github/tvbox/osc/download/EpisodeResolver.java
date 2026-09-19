package com.github.tvbox.osc.download;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;

import androidx.lifecycle.Observer;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.viewmodel.SourceViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class EpisodeResolver {
    interface Cancel { boolean stopped(); }

    static Video resolve(android.content.Context context, DownloadStore.Task task, Cancel cancel) throws Exception {
        ensureSource(task.sourceKey, cancel);
        SourceBean source = ApiConfig.get().getSource(task.sourceKey);
        if (source == null) throw new IOException("下载来源已失效");
        SourceViewModel model = new SourceViewModel();
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Observer<JSONObject>> observerRef = new AtomicReference<>();
        AtomicReference<JSONObject> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        main.post(() -> {
            if (cancel.stopped()) { latch.countDown(); return; }
            Observer<JSONObject> observer = result -> {
                resultRef.set(result);
                latch.countDown();
            };
            observerRef.set(observer);
            model.playResult.observeForever(observer);
            try {
                model.getPlay(task.sourceKey, task.playFlag, task.id, task.episodeUrl, task.id);
            } catch (Throwable e) {
                errorRef.set(e);
                latch.countDown();
            }
        });
        try {
            long deadline = System.currentTimeMillis() + Math.max(45, source.getPlayTimeoutSeconds() + 15) * 1000L;
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                if (cancel.stopped()) throw new IOException("下载已暂停");
                if (System.currentTimeMillis() >= deadline) throw new IOException("播放地址解析超时");
            }
            if (cancel.stopped()) throw new IOException("下载已暂停");
            if (errorRef.get() != null) throw new IOException("播放地址解析失败", errorRef.get());
            return PlaybackLinkResolver.resolve(context, source, resultRef.get(), cancel::stopped);
        } finally {
            main.post(() -> {
                model.cancelPlayRequest();
                Observer<JSONObject> observer = observerRef.get();
                if (observer != null) model.playResult.removeObserver(observer);
            });
        }
    }

    private static void ensureSource(String key, Cancel cancel) throws Exception {
        if (ApiConfig.get().getSource(key) != null) return;
        CountDownLatch config = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        ApiConfig.get().loadConfig(true, new ApiConfig.LoadConfigCallback() {
            @Override public void success() { config.countDown(); }
            @Override public void error(String message) { error.set(message); config.countDown(); }
            @Override public void notice(String message) { }
        }, null);
        await(config, cancel, "站点配置加载超时");
        if (error.get() != null) throw new IOException("站点配置加载失败: " + error.get());
        String spider = ApiConfig.get().getSpider();
        if (!TextUtils.isEmpty(spider)) {
            CountDownLatch jar = new CountDownLatch(1);
            ApiConfig.get().loadJar(true, spider, new ApiConfig.LoadConfigCallback() {
                @Override public void success() { jar.countDown(); }
                @Override public void error(String message) { error.set(message); jar.countDown(); }
                @Override public void notice(String message) { }
            });
            await(jar, cancel, "爬虫组件加载超时");
            if (error.get() != null) throw new IOException("爬虫组件加载失败: " + error.get());
        }
    }

    private static void await(CountDownLatch latch, Cancel cancel, String timeout) throws Exception {
        long deadline = System.currentTimeMillis() + 90000;
        while (!latch.await(500, TimeUnit.MILLISECONDS)) {
            if (cancel.stopped()) throw new IOException("下载已暂停");
            if (System.currentTimeMillis() >= deadline) throw new IOException(timeout);
        }
        if (cancel.stopped()) throw new IOException("下载已暂停");
    }

    static Video fromResult(JSONObject result) throws IOException {
        if (result == null) throw new IOException("源站未返回播放地址");
        String message = result.optString("msg", "");
        if (!message.isEmpty()) throw new IOException(message);
        String url = firstUrl(result.opt("url"));
        String prefix = result.optString("playUrl", "");
        boolean needsParse = "1".equals(result.optString("parse", "1"))
                || "1".equals(result.optString("jx", "0"));
        if (!prefix.isEmpty() && (!needsParse || !isDirectVideo(url))) url = prefix + url;
        if (!(url.startsWith("http://") || url.startsWith("https://")))
            throw new IOException("不是可下载的视频链接");
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host))
                throw new IOException("本地代理流无法后台下载");
            if (parsed.getPath().toLowerCase(Locale.ROOT).endsWith(".mpd"))
                throw new IOException("暂不支持 DASH 下载");
        } catch (java.net.MalformedURLException e) {
            throw new IOException("播放地址无效", e);
        }
        Map<String, String> headers = new HashMap<>();
        appendHeaders(headers, result.opt("headers"));
        appendHeaders(headers, result.opt("header"));
        if (!result.optString("user-agent", "").isEmpty())
            headers.put("User-Agent", result.optString("user-agent").trim());
        if (!result.optString("referer", "").isEmpty())
            headers.put("Referer", result.optString("referer").trim());
        boolean hasCookie = false;
        for (String key : headers.keySet()) if ("cookie".equalsIgnoreCase(key)) hasCookie = true;
        if (!hasCookie) try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        } catch (Exception ignored) { }
        return new Video(url, headers);
    }

    static String firstUrl(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return "";
        JSONArray array = raw instanceof JSONArray ? (JSONArray) raw : null;
        if (array == null && String.valueOf(raw).startsWith("[")) try {
            array = new JSONArray(String.valueOf(raw));
        } catch (Exception ignored) { }
        if (array == null) return String.valueOf(raw);
        for (int i = 0; i < array.length(); i++) {
            String item = array.optString(i, "");
            if (item.startsWith("http://") || item.startsWith("https://")) return item;
        }
        return "";
    }

    private static boolean isDirectVideo(String url) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        try {
            String path = new URL(url).getPath().toLowerCase(Locale.ROOT);
            return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv")
                    || path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".ts");
        } catch (Exception ignored) { return false; }
    }

    private static void appendHeaders(Map<String, String> headers, Object raw) {
        try {
            JSONObject json = raw instanceof JSONObject ? (JSONObject) raw
                    : raw instanceof String ? new JSONObject((String) raw) : null;
            if (json == null) return;
            for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                String key = it.next();
                headers.put(key, json.optString(key, ""));
            }
        } catch (Exception ignored) { }
    }

    static final class Video {
        final String url;
        final Map<String, String> headers;
        Video(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }
}
