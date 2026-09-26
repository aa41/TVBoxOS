package com.github.tvbox.osc.download;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DownloadStore {
    public static final String QUEUED = "queued", RUNNING = "running", PAUSED = "paused";
    public static final String DONE = "done", FAILED = "failed";
    private static DownloadStore instance;
    private final Context context;
    private final List<Task> tasks = new ArrayList<>();

    public static synchronized DownloadStore get(Context context) {
        if (instance == null) instance = new DownloadStore(context.getApplicationContext());
        return instance;
    }

    private DownloadStore(Context context) {
        this.context = context;
        try {
            File file = new File(context.getFilesDir(), "video_downloads.json");
            if (!file.exists()) return;
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int offset = 0, count;
                while (offset < data.length && (count = in.read(data, offset, data.length - offset)) > 0) offset += count;
                JSONArray array = new JSONArray(new String(data, StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    Task task = Task.from(array.getJSONObject(i));
                    if (RUNNING.equals(task.state)) task.state = QUEUED;
                    tasks.add(task);
                }
            }
        } catch (Exception ignored) {
            // A damaged index must not prevent the application from opening.
        }
    }

    public synchronized Task add(String title, String url, Map<String, String> headers) {
        return add(title, url, headers, "");
    }

    public synchronized Task add(String title, String url, Map<String, String> headers, String cover) {
        if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        for (Task task : tasks) if (url.equals(task.url) && !FAILED.equals(task.state)) {
            if (TextUtils.isEmpty(task.cover) && !TextUtils.isEmpty(cover)) {
                task.cover = cover;
                save();
            }
            return task.copy();
        }
        Task task = new Task();
        task.id = UUID.randomUUID().toString();
        task.title = TextUtils.isEmpty(title) ? "视频" : title.trim();
        task.cover = cover == null ? "" : cover;
        task.url = url;
        task.headers = new JSONObject(headers == null ? java.util.Collections.emptyMap() : headers);
        task.state = QUEUED;
        tasks.add(0, task);
        save();
        return task.copy();
    }

    public synchronized AddResult addEpisodes(List<Episode> episodes) {
        Set<String> existing = new HashSet<>();
        for (Task task : tasks) if (!TextUtils.isEmpty(task.episodeUrl)) {
            existing.add(episodeKey(task.sourceKey, task.playFlag, task.episodeUrl));
        }
        List<Task> added = new ArrayList<>();
        int skipped = 0;
        boolean updatedCover = false;
        for (Episode episode : episodes) {
            if (episode == null || TextUtils.isEmpty(episode.sourceKey)
                    || TextUtils.isEmpty(episode.url)) { skipped++; continue; }
            String key = episodeKey(episode.sourceKey, episode.playFlag, episode.url);
            if (!existing.add(key)) {
                if (!TextUtils.isEmpty(episode.cover)) {
                    for (Task task : tasks) {
                        if (key.equals(episodeKey(task.sourceKey, task.playFlag, task.episodeUrl))
                                && TextUtils.isEmpty(task.cover)) {
                            task.cover = episode.cover;
                            updatedCover = true;
                            break;
                        }
                    }
                }
                skipped++;
                continue;
            }
            Task task = new Task();
            task.id = UUID.randomUUID().toString();
            task.collection = episode.collection;
            task.episode = episode.name;
            task.title = episode.collection + " · " + episode.name;
            task.cover = episode.cover;
            task.sourceKey = episode.sourceKey;
            task.playFlag = episode.playFlag;
            task.episodeUrl = episode.url;
            task.url = "";
            task.state = QUEUED;
            added.add(task);
        }
        if (!added.isEmpty() || updatedCover) {
            if (!added.isEmpty()) tasks.addAll(0, added);
            save();
        }
        return new AddResult(added.size(), skipped);
    }

    private static String episodeKey(String sourceKey, String playFlag, String url) {
        return sourceKey + "\u0000" + playFlag + "\u0000" + url;
    }

    public synchronized List<Task> list() {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks) result.add(task.copy());
        return result;
    }

    public synchronized Task getTask(String id) {
        for (Task task : tasks) if (task.id.equals(id)) return task.copy();
        return null;
    }

    public synchronized Task startIfQueued(String id) {
        for (Task task : tasks) if (task.id.equals(id) && QUEUED.equals(task.state)) {
            task.state = RUNNING; task.error = "";
            save();
            return task.copy();
        }
        return null;
    }

    public synchronized void update(Task value) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id.equals(value.id)) {
            tasks.set(i, value.copy());
            save();
            return;
        }
    }

    public synchronized void remove(String id) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id.equals(id)) {
            tasks.remove(i);
            save();
            break;
        }
    }

    public synchronized void removeAll(Set<String> ids) {
        boolean changed = false;
        for (int i = tasks.size() - 1; i >= 0; i--) if (ids.contains(tasks.get(i).id)) {
            tasks.remove(i);
            changed = true;
        }
        if (changed) save();
    }

    public File directory(Task task) {
        File root = context.getExternalFilesDir("downloads");
        if (root == null) root = new File(context.getFilesDir(), "downloads");
        return new File(root, task.id);
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Task task : tasks) array.put(task.toJson());
            File file = new File(context.getFilesDir(), "video_downloads.json");
            File temp = new File(context.getFilesDir(), "video_downloads.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new IllegalStateException("Cannot save downloads");
        } catch (Exception e) {
            android.util.Log.e("DownloadStore", "Cannot save download index", e);
        }
    }

    public static class Task {
        public String id, title, url, state, path, error;
        public String collection, episode, sourceKey, playFlag, episodeUrl, cover;
        public JSONObject headers = new JSONObject();
        public long bytes, total;
        public int items, itemCount;

        Task copy() { return from(toJson()); }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id).put("title", title).put("url", url).put("state", state)
                        .put("path", path).put("error", error).put("headers", headers)
                        .put("bytes", bytes).put("total", total).put("items", items).put("itemCount", itemCount)
                        .put("collection", collection).put("episode", episode)
                        .put("sourceKey", sourceKey).put("playFlag", playFlag)
                        .put("episodeUrl", episodeUrl).put("cover", cover);
            } catch (Exception ignored) { }
            return json;
        }

        static Task from(JSONObject json) {
            Task task = new Task();
            task.id = json.optString("id"); task.title = json.optString("title");
            task.url = json.optString("url"); task.state = json.optString("state");
            task.path = json.optString("path"); task.error = json.optString("error");
            task.headers = json.optJSONObject("headers");
            if (task.headers == null) task.headers = new JSONObject();
            task.bytes = json.optLong("bytes"); task.total = json.optLong("total");
            task.items = json.optInt("items"); task.itemCount = json.optInt("itemCount");
            task.collection = json.optString("collection"); task.episode = json.optString("episode");
            task.sourceKey = json.optString("sourceKey"); task.playFlag = json.optString("playFlag");
            task.episodeUrl = json.optString("episodeUrl");
            task.cover = json.optString("cover");
            return task;
        }
    }

    public static class Episode {
        public final String collection, name, sourceKey, playFlag, url, cover;

        public Episode(String collection, String name, String sourceKey, String playFlag, String url) {
            this(collection, name, sourceKey, playFlag, url, "");
        }

        public Episode(String collection, String name, String sourceKey, String playFlag,
                       String url, String cover) {
            this.collection = collection == null ? "视频" : collection;
            this.name = name == null ? "剧集" : name;
            this.sourceKey = sourceKey;
            this.playFlag = playFlag == null ? "" : playFlag;
            this.url = url;
            this.cover = cover == null ? "" : cover;
        }
    }

    public static class AddResult {
        public final int added, skipped;
        AddResult(int added, int skipped) { this.added = added; this.skipped = skipped; }
    }
}
