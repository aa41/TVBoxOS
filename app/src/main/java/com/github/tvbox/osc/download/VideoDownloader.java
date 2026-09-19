package com.github.tvbox.osc.download;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

final class VideoDownloader {
    interface Progress { void update(long bytes, long total, int items, int itemCount) throws IOException; }
    interface Cancel { boolean stopped(); }

    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build();
    private String url;
    private final File dir;
    private final int threads;
    private final Progress progress;
    private final Cancel cancel;
    private final Map<String, String> headers = new HashMap<>();
    private static final Pattern URI = Pattern.compile("URI=\"([^\"]+)\"");
    private long lastReport;
    private long expectedBytes;
    private int expectedAssets;

    VideoDownloader(String url, JSONObject json, File dir, int threads, Progress progress, Cancel cancel) {
        this.url = url; this.dir = dir; this.threads = Math.max(1, Math.min(8, threads));
        this.progress = progress; this.cancel = cancel;
        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next(); headers.put(key, json.optString(key));
        }
    }

    String run() throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        for (int hop = 0; hop < 3; hop++) {
            check();
            try (Response probe = probe(url)) {
                if (!probe.isSuccessful()) throw new IOException("HTTP " + probe.code());
                String type = probe.header("Content-Type", "").toLowerCase(Locale.ROOT);
                byte[] head = new byte[512];
                int n = probe.body() == null ? -1 : probe.body().byteStream().read(head);
                String preview = n > 0 ? new String(head, 0, n, StandardCharsets.UTF_8) : "";
                String path = probe.request().url().encodedPath().toLowerCase(Locale.ROOT);
                if (type.contains("mpegurl") || preview.replace("\uFEFF", "").trim().startsWith("#EXTM3U")
                        || path.endsWith(".m3u8") && !isWebResponse(type, preview)) {
                    url = probe.request().url().toString();
                    return downloadHls();
                }
                if (isWebResponse(type, preview)) {
                    if (hop == 2) throw new IOException("网页或接口未找到可下载的媒体地址");
                    try (Response page = request(url, null)) {
                        if (!page.isSuccessful() || page.body() == null) throw new IOException("页面 HTTP " + page.code());
                        String pageUrl = page.request().url().toString();
                        MediaLinkResolver.Link link = MediaLinkResolver.find(readPage(page.body().byteStream()),
                                page.header("Content-Type", "").toLowerCase(Locale.ROOT), pageUrl);
                        if (link == null) throw new IOException("网页或接口未找到可下载的媒体地址");
                        for (Map.Entry<String, String> entry : link.headers.entrySet()) headers.put(entry.getKey(), entry.getValue());
                        if (!hasHeader("Referer")) headers.put("Referer", pageUrl);
                        if (!hasHeader("Cookie")) {
                            HttpUrl target = HttpUrl.parse(link.url);
                            List<String> cookies = new ArrayList<>();
                            if (target != null) for (Cookie cookie : Cookie.parseAll(page.request().url(), page.headers()))
                                if (cookie.matches(target)) cookies.add(cookie.name() + "=" + cookie.value());
                            if (!cookies.isEmpty()) {
                                StringBuilder value = new StringBuilder();
                                for (String cookie : cookies) {
                                    if (value.length() > 0) value.append("; ");
                                    value.append(cookie);
                                }
                                headers.put("Cookie", value.toString());
                            }
                        }
                        url = link.url;
                    }
                    continue;
                }
                url = probe.request().url().toString();
                String range = probe.header("Content-Range", "");
                long length = -1;
                int slash = range.lastIndexOf('/');
                if (probe.code() == 206 && slash > 0) {
                    try { length = Long.parseLong(range.substring(slash + 1)); } catch (NumberFormatException ignored) { }
                }
                return downloadFile(length);
            }
        }
        throw new IOException("未找到可下载的媒体地址");
    }

    private static String readPage(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (out.size() < 512 * 1024) {
            int n = stream.read(buffer, 0, Math.min(buffer.length, 512 * 1024 - out.size()));
            if (n < 0) break;
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static boolean isWebResponse(String type, String preview) {
        String start = preview.trim().toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.contains("application/json")
                || type.contains("application/javascript") || type.contains("application/x-www-form-urlencoded")
                || type.contains("application/xml") || type.contains("text/xml")
                || type.startsWith("image/") || start.startsWith("<!doctype")
                || start.startsWith("<html") || start.startsWith("<?xml")
                || start.startsWith("{") || start.startsWith("[");
    }

    private boolean hasHeader(String key) {
        for (String name : headers.keySet()) if (name.equalsIgnoreCase(key)) return true;
        return false;
    }

    private Request.Builder builder(String url) {
        Request.Builder request = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        for (Map.Entry<String, String> entry : headers.entrySet()) request.header(entry.getKey(), entry.getValue());
        if (!hasHeader("User-Agent")) request.header("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        request.header("Accept-Encoding", "identity");
        return request;
    }

    private Response request(String url, String range) throws IOException {
        Request.Builder request = builder(url);
        if (range != null) request.header("Range", range);
        return client.newCall(request.build()).execute();
    }

    private Response probe(String url) throws IOException {
        Response response = request(url, "bytes=0-511");
        if (response.code() == 400 || response.code() == 416) {
            response.close();
            return request(url, null);
        }
        return response;
    }

    private void check() throws IOException {
        if (cancel.stopped() || Thread.currentThread().isInterrupted()) throw new IOException("下载已暂停");
    }

    private String downloadFile(long length) throws Exception {
        expectedBytes = length;
        reportFiles();
        String suffix = ".mp4";
        String path = new URL(url).getPath().toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".mp4", ".mkv", ".webm", ".mov", ".ts"})
            if (path.endsWith(ext)) { suffix = ext; break; }
        File output = new File(dir, "video" + suffix);
        if (length > 0 && threads > 1) {
            final long rangeLength = length;
            int count = (int) Math.min(threads, Math.max(1, (length + 1024 * 1024 - 1) / (1024 * 1024)));
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                final long start = rangeLength * i / count, end = rangeLength * (i + 1) / count - 1;
                jobs.add(() -> {
                    File part = new File(dir, "part_" + index);
                    long size = end - start + 1;
                    if (part.length() == size) return null;
                    for (int attempt = 0; attempt < 3; attempt++) {
                        check();
                        try {
                            long offset = Math.min(part.length(), size);
                            try (Response response = request(url, "bytes=" + (start + offset) + "-" + end)) {
                                if (response.code() != 206 || response.body() == null
                                        || !response.header("Content-Range", "").startsWith("bytes " + (start + offset) + "-"))
                                    throw new IOException("服务器不支持分段下载");
                                copy(validatedStream(response), part, true, size);
                            }
                            if (part.length() == size) return null;
                        } catch (IOException e) { if (attempt == 2) throw e; }
                    }
                    throw new IOException("分段下载失败");
                });
            }
            try {
                runParallel(jobs);
                check();
                try (FileOutputStream out = new FileOutputStream(output)) {
                    byte[] buffer = new byte[65536];
                    for (int i = 0; i < count; i++) {
                        File part = new File(dir, "part_" + i);
                        try (FileInputStream in = new FileInputStream(part)) {
                            int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        }
                    }
                }
                if (output.length() != length) throw new IOException("文件长度不匹配");
                for (int i = 0; i < count; i++) new File(dir, "part_" + i).delete();
            } catch (Exception e) {
                check();
                // Some origins advertise ranges but reject parallel requests.
                for (int i = 0; i < count; i++) new File(dir, "part_" + i).delete();
                return downloadSingle(length, output);
            }
        } else {
            return downloadSingle(length, output);
        }
        report(output.length(), output.length(), 1, 1);
        return output.getAbsolutePath();
    }

    private String downloadSingle(long length, File output) throws Exception {
        File part = new File(dir, "video.tmp");
        long offset = part.length();
        if (length > 0 && offset == length) {
            if (output.exists() && !output.delete()) throw new IOException("无法替换不完整视频");
            if (!part.renameTo(output)) throw new IOException("无法保存视频");
            report(length, length, 1, 1);
            return output.getAbsolutePath();
        }
        try (Response response = request(url, offset > 0 ? "bytes=" + offset + "-" : null)) {
            if (response.body() == null || !response.isSuccessful()) throw new IOException("HTTP " + response.code());
            boolean resume = offset > 0 && response.code() == 206
                    && response.header("Content-Range", "").startsWith("bytes " + offset + "-");
            if (offset > 0 && !resume) offset = 0;
            if (length <= 0 && response.body().contentLength() > 0)
                length = response.body().contentLength() + offset;
            expectedBytes = length;
            copy(validatedStream(response), part, resume, -1);
        }
        check();
        if (length > 0 && part.length() != length) throw new IOException("文件长度不匹配");
        if (part.length() == 0) throw new IOException("视频响应为空");
        if (output.exists() && !output.delete()) throw new IOException("无法替换不完整视频");
        if (!part.renameTo(output)) throw new IOException("无法保存视频");
        report(output.length(), output.length(), 1, 1);
        return output.getAbsolutePath();
    }

    private String downloadHls() throws Exception {
        String url = this.url, playlist = null;
        String audioUrl = null, videoTag = null, audioTag = null;
        for (int depth = 0; depth < 5; depth++) {
            check();
            try (Response response = request(url, null)) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("清单 HTTP " + response.code());
                playlist = response.body().string().replace("\uFEFF", "");
                url = response.request().url().toString();
            }
            if (!playlist.replace("\uFEFF", "").startsWith("#EXTM3U")) throw new IOException("不是有效的 m3u8 清单");
            if (!playlist.contains("#EXT-X-STREAM-INF")) break;
            String[] lines = playlist.split("\\r?\\n");
            long best = -1; String selected = null, selectedTag = null;
            for (int i = 0; i + 1 < lines.length; i++) if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                Matcher matcher = Pattern.compile("BANDWIDTH=(\\d+)").matcher(lines[i]);
                long bandwidth = matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
                if (bandwidth >= best) {
                    int next = i + 1;
                    while (next < lines.length && lines[next].trim().isEmpty()) next++;
                    if (next < lines.length && !lines[next].startsWith("#")) {
                        selected = resolve(url, lines[next].trim()); best = bandwidth; selectedTag = lines[i];
                    }
                }
            }
            if (selected == null) throw new IOException("没有可用的 HLS 视频轨道");
            audioUrl = null; audioTag = null; videoTag = selectedTag;
            String group = attribute(selectedTag, "AUDIO");
            if (group != null) for (String line : lines) {
                if (!line.startsWith("#EXT-X-MEDIA:") || !"AUDIO".equals(attribute(line, "TYPE"))
                        || !group.equals(attribute(line, "GROUP-ID"))) continue;
                String uri = attribute(line, "URI");
                if (uri != null && (audioUrl == null || line.contains("DEFAULT=YES"))) {
                    audioUrl = resolve(url, uri); audioTag = line;
                }
            }
            url = selected;
        }
        if (playlist == null || playlist.contains("#EXT-X-STREAM-INF")) throw new IOException("HLS 清单嵌套过深");
        List<Asset> assets = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        String local = rewriteMedia(url, playlist, assets, names);
        String audioLocal = null;
        if (audioUrl != null) {
            try (Response response = request(audioUrl, null)) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("音轨清单 HTTP " + response.code());
                audioLocal = rewriteMedia(response.request().url().toString(),
                        response.body().string().replace("\uFEFF", ""), assets, names);
            }
        }
        final int count = assets.size();
        expectedAssets = count;
        reportFiles();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (Asset asset : assets) jobs.add(() -> {
            File file = new File(dir, asset.name);
            if (file.length() > 0) return null;
            File temp = new File(dir, asset.name + ".tmp");
            for (int attempt = 0; attempt < 3; attempt++) {
                check();
                String range = asset.length > 0 ? "bytes=" + asset.start + "-" + (asset.start + asset.length - 1) : null;
                try (Response response = request(asset.url, range)) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("分片 HTTP " + response.code());
                    if (range != null && (response.code() != 206 || !response.header("Content-Range", "")
                            .startsWith("bytes " + asset.start + "-" + (asset.start + asset.length - 1) + "/")))
                        throw new IOException("服务器不支持 HLS 范围分片");
                    copy(validatedStream(response), temp, false, asset.length);
                    if (asset.length > 0 && temp.length() != asset.length) throw new IOException("HLS 分片长度不匹配");
                    if (temp.length() == 0 || !temp.renameTo(file)) throw new IOException("无法保存分片");
                    return null;
                } catch (IOException e) { if (attempt == 2) throw e; }
            }
            return null;
        });
        runParallel(jobs);
        check();
        if (audioLocal != null) {
            write(new File(dir, "video_track.m3u8"), local);
            write(new File(dir, "audio_track.m3u8"), audioLocal);
            Matcher matcher = URI.matcher(audioTag);
            if (!matcher.find()) throw new IOException("缺少 HLS 音轨地址");
            audioTag = audioTag.substring(0, matcher.start(1)) + "audio_track.m3u8"
                    + audioTag.substring(matcher.end(1));
            local = "#EXTM3U\n" + audioTag + "\n" + videoTag + "\nvideo_track.m3u8\n";
        }
        File result = new File(dir, "video.m3u8");
        write(result, local);
        long bytes = 0; for (Asset asset : assets) bytes += new File(dir, asset.name).length();
        report(bytes, bytes, count, count);
        return result.getAbsolutePath();
    }

    private String rewriteMedia(String url, String playlist, List<Asset> assets, Map<String, String> names) throws IOException {
        if (!playlist.replace("\uFEFF", "").startsWith("#EXTM3U")) throw new IOException("不是有效的 m3u8 清单");
        if (!playlist.contains("#EXT-X-ENDLIST")) throw new IOException("直播流不能离线下载");
        if (playlist.contains("#EXT-X-DEFINE") || playlist.contains("#EXT-X-PART")
                || playlist.contains("#EXT-X-MEDIA:") || playlist.contains("#EXT-X-SESSION-KEY"))
            throw new IOException("暂不支持此 HLS 清单格式");
        StringBuilder local = new StringBuilder(); int segment = 0;
        long pendingLength = -1, pendingStart = -1, nextOffset = -1;
        String previousRangeUrl = null;
        for (String line : playlist.split("\\r?\\n")) {
            check();
            if (line.startsWith("#EXT-X-BYTERANGE:")) {
                long[] range = parseRange(line.substring("#EXT-X-BYTERANGE:".length()));
                pendingLength = range[0]; pendingStart = range[1];
                continue;
            }
            if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) {
                if (line.contains("METHOD=SAMPLE-AES") || line.contains("KEYFORMAT=\"") && !line.contains("KEYFORMAT=\"identity\""))
                    throw new IOException("DRM 加密流不能下载");
                Matcher matcher = URI.matcher(line);
                if (matcher.find()) {
                    String remote = resolve(url, matcher.group(1));
                    String mapRange = line.startsWith("#EXT-X-MAP") ? attribute(line, "BYTERANGE") : null;
                    long[] range = mapRange == null ? new long[]{-1, -1} : parseRange(mapRange);
                    if (mapRange != null && range[1] < 0) throw new IOException("HLS 初始化分片缺少范围偏移");
                    String name = asset(assets, names, remote, line.startsWith("#EXT-X-KEY") ? ".key" : ".bin",
                            range[1], range[0]);
                    line = line.substring(0, matcher.start(1)) + name + line.substring(matcher.end(1));
                    if (mapRange != null) line = line.replaceAll(",BYTERANGE=\"[^\"]+\"", "");
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String remote = resolve(url, line.trim());
                long start = pendingStart;
                if (pendingLength > 0 && start < 0) {
                    if (!remote.equals(previousRangeUrl) || nextOffset < 0)
                        throw new IOException("HLS 分片缺少范围偏移");
                    start = nextOffset;
                }
                line = asset(assets, names, remote, ".seg", start, pendingLength);
                previousRangeUrl = pendingLength > 0 ? remote : null;
                nextOffset = pendingLength > 0 ? start + pendingLength : -1;
                pendingLength = -1; pendingStart = -1;
                segment++;
            }
            local.append(line).append('\n');
        }
        if (segment == 0) throw new IOException("清单中没有视频分片");
        return local.toString();
    }

    private static String attribute(String line, String key) {
        Matcher matcher = Pattern.compile("(?:^|[:,])" + key + "=(?:\"([^\"]+)\"|([^,]+))").matcher(line);
        return matcher.find() ? matcher.group(1) != null ? matcher.group(1) : matcher.group(2) : null;
    }

    private static void write(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long[] parseRange(String value) throws IOException {
        String[] parts = value.replace("\"", "").trim().split("@", 2);
        try {
            long length = Long.parseLong(parts[0]);
            long start = parts.length > 1 ? Long.parseLong(parts[1]) : -1;
            if (length <= 0 || start < -1 || start > Long.MAX_VALUE - length)
                throw new NumberFormatException();
            return new long[]{length, start};
        } catch (NumberFormatException e) { throw new IOException("HLS 分片范围无效", e); }
    }

    private String asset(List<Asset> assets, Map<String, String> names, String url, String ext,
                         long start, long length) {
        String key = url + "#" + start + ":" + length;
        if (!names.containsKey(key)) {
            String name = String.format(Locale.ROOT, "%05d%s", assets.size(), ext);
            names.put(key, name); assets.add(new Asset(url, name, start, length));
        }
        return names.get(key);
    }

    private static String resolve(String base, String relative) throws IOException { return new URL(new URL(base), relative).toString(); }

    private static InputStream validatedStream(Response response) throws IOException {
        if (response.body() == null) throw new IOException("视频响应为空");
        BufferedInputStream stream = new BufferedInputStream(response.body().byteStream());
        stream.mark(1024);
        byte[] head = new byte[512];
        int count = stream.read(head);
        stream.reset();
        String preview = count > 0 ? new String(head, 0, count, StandardCharsets.UTF_8) : "";
        if (isWebResponse(response.header("Content-Type", "").toLowerCase(Locale.ROOT), preview))
            throw new IOException("链接返回网页或接口数据，不是视频");
        return stream;
    }

    private void runParallel(List<Callable<Void>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = pool.invokeAll(jobs);
            for (Future<Void> future : futures) future.get();
        } finally { pool.shutdownNow(); }
    }

    private void copy(InputStream in, File file, boolean append, long max) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) {
                check(); out.write(buffer, 0, n);
                if (max > 0 && file.length() > max) throw new IOException("分段长度不匹配");
                reportFiles();
            }
        }
    }

    private synchronized void reportFiles() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastReport < 700) return;
        lastReport = now;
        long bytes = 0; int items = 0, count = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().startsWith("part_") || file.getName().endsWith(".seg")
                    || file.getName().endsWith(".key") || file.getName().endsWith(".bin")
                    || file.getName().endsWith(".tmp")) {
                bytes += file.length(); if (!file.getName().endsWith(".tmp")) items++;
            }
        }
        report(bytes, expectedBytes, items, expectedAssets);
    }

    private synchronized void report(long bytes, long total, int items, int count) throws IOException {
        progress.update(bytes, total, items, count);
    }

    private static class Asset {
        final String url, name;
        final long start, length;
        Asset(String url, String name, long start, long length) {
            this.url = url; this.name = name; this.start = start; this.length = length;
        }
    }
}
