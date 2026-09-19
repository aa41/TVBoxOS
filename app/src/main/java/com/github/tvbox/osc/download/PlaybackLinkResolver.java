package com.github.tvbox.osc.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.VideoParseRuler;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class PlaybackLinkResolver {
    interface Cancel { boolean stopped(); }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    static EpisodeResolver.Video resolve(Context context, SourceBean source, JSONObject result, Cancel cancel) throws Exception {
        if (result == null) throw new IOException("源站未返回播放地址");
        if (!result.optString("msg", "").isEmpty()) throw new IOException(result.optString("msg"));
        String raw = EpisodeResolver.firstUrl(result.opt("url"));
        String prefix = result.optString("playUrl", "");
        String flag = result.optString("flag", "");
        boolean parse = "1".equals(result.optString("parse", "1"))
                || "1".equals(result.optString("jx", "0"));
        if (!parse) return EpisodeResolver.fromResult(result);
        if (raw.isEmpty()) throw new IOException("源站未返回播放地址");

        boolean useDefault = "1".equals(result.optString("jx", "0"))
                || prefix.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag);
        ParseBean parser = chooseParser(prefix, useDefault, ApiConfig.get().getDefaultParse(),
                ApiConfig.get().getParseBeanList());
        Map<String, String> headers = headers(result);
        if (parser == null) {
            if (isMedia(raw)) return video(raw, headers);
            throw new IOException("未配置可用的播放解析器");
        }
        if (cancel.stopped()) throw new IOException("下载已暂停");
        switch (parser.getType()) {
            case 0:
                headers.putAll(parserHeaders(parser));
                return sniff(context, source, parser.getUrl() + raw, raw, headers, cancel);
            case 1:
                return resolveParserResult(context, source, jsonRequest(parser, raw, headers), raw, headers, cancel);
            case 2: {
                LinkedHashMap<String, String> parsers = new LinkedHashMap<>();
                for (ParseBean item : ApiConfig.get().getParseBeanList()) if (item.getType() == 1)
                    parsers.put(item.getName(), item.mixUrl());
                return resolveParserResult(context, source, ApiConfig.get().jsonExt(parser.getUrl(), parsers, raw),
                        raw, headers, cancel);
            }
            case 3: {
                LinkedHashMap<String, HashMap<String, String>> parsers = mixParsers();
                return resolveParserResult(context, source,
                        ApiConfig.get().jsonExtMix(flag + "111", parser.getUrl(), parser.getName(), parsers, raw),
                        raw, headers, cancel);
            }
            case 4:
                // The player races JSON and WebView parsers. Downloads are serial to avoid orphan WebViews.
                for (ParseBean item : ApiConfig.get().getParseBeanList()) {
                    if (cancel.stopped()) throw new IOException("下载已暂停");
                    if (item.getType() != 1) continue;
                    try { return resolveParserResult(context, source, jsonRequest(item, raw, headers), raw, headers, cancel); }
                    catch (IOException ignored) { }
                }
                for (ParseBean item : ApiConfig.get().getParseBeanList()) {
                    if (cancel.stopped()) throw new IOException("下载已暂停");
                    if (item.getType() != 0) continue;
                    try { return sniff(context, source, item.getUrl() + raw, raw, headers, cancel); }
                    catch (IOException ignored) { }
                }
                throw new IOException("聚合解析未找到视频地址");
            default:
                throw new IOException("不支持的播放解析器类型");
        }
    }

    static ParseBean chooseParser(String prefix, boolean useDefault, ParseBean defaultParser, List<ParseBean> parsers) {
        if (useDefault) return defaultParser;
        if (prefix.startsWith("json:")) return parser(1, prefix.substring(5));
        if (prefix.startsWith("parse:")) {
            String name = prefix.substring(6);
            for (ParseBean item : parsers) if (name.equals(item.getName())) return item;
        }
        return prefix.isEmpty() ? null : parser(0, prefix);
    }

    private static ParseBean parser(int type, String url) {
        ParseBean parser = new ParseBean();
        parser.setType(type);
        parser.setUrl(url);
        return parser;
    }

    private static LinkedHashMap<String, HashMap<String, String>> mixParsers() {
        LinkedHashMap<String, HashMap<String, String>> parsers = new LinkedHashMap<>();
        for (ParseBean item : ApiConfig.get().getParseBeanList()) {
            HashMap<String, String> entry = new HashMap<>();
            entry.put("url", item.getUrl());
            entry.put("type", String.valueOf(item.getType()));
            entry.put("ext", item.getExt());
            parsers.put(item.getName(), entry);
        }
        return parsers;
    }

    static JSONObject jsonRequest(ParseBean parser, String raw, Map<String, String> inherited) throws IOException {
        String url;
        try { url = parser.getUrl() + URLEncoder.encode(raw, "UTF-8"); }
        catch (Exception e) { throw new IOException("解析地址无效", e); }
        Request.Builder request = new Request.Builder().url(url);
        Map<String, String> headers = new HashMap<>(inherited);
        headers.putAll(parserHeaders(parser));
        for (Map.Entry<String, String> entry : headers.entrySet()) request.header(entry.getKey(), entry.getValue().trim());
        try (Response response = CLIENT.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("解析接口 HTTP " + response.code());
            return new JSONObject(response.body().string());
        } catch (org.json.JSONException e) { throw new IOException("解析接口未返回 JSON 视频地址", e); }
    }

    static EpisodeResolver.Video resolveParserResult(Context context, SourceBean source, JSONObject result,
                                                      String raw, Map<String, String> inherited, Cancel cancel) throws Exception {
        if (result == null) throw new IOException("解析接口未返回视频地址");
        JSONObject data = result.optJSONObject("data");
        if (data == null) data = result;
        String url = data.optString("url", result.optString("url", "")).trim();
        if (url.startsWith("//")) url = "http:" + url;
        boolean sniff = url.startsWith("video://") || data.optInt("parse", result.optInt("parse", 0)) == 1;
        if (url.startsWith("video://")) url = url.substring(8);
        url = DefaultConfig.checkReplaceProxy(url);
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IOException("解析接口未返回视频地址");
        Map<String, String> headers = new HashMap<>(inherited);
        headers.putAll(headers(result));
        headers.putAll(headers(data));
        if (sniff) return sniff(context, source, url, raw, headers, cancel);
        return video(url, headers);
    }

    private static Map<String, String> parserHeaders(ParseBean parser) {
        try { return headers(new JSONObject(parser.getExt())); }
        catch (Exception ignored) { return new HashMap<>(); }
    }

    private static Map<String, String> headers(JSONObject object) {
        Map<String, String> headers = new HashMap<>();
        if (object == null) return headers;
        for (String key : new String[]{"header", "headers"}) {
            Object raw = object.opt(key);
            try {
                JSONObject json = raw instanceof JSONObject ? (JSONObject) raw
                        : raw instanceof String ? new JSONObject((String) raw) : null;
                if (json != null) for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                    String name = it.next(); headers.put(name, json.optString(name, "").trim());
                }
            } catch (Exception ignored) { }
        }
        if (!object.optString("user-agent", "").isEmpty()) headers.put("User-Agent", object.optString("user-agent").trim());
        if (!object.optString("referer", "").isEmpty()) headers.put("Referer", object.optString("referer").trim());
        return headers;
    }

    private static boolean isMedia(String url) {
        return DefaultConfig.isVideoFormat(url);
    }

    private static EpisodeResolver.Video video(String url, Map<String, String> headers) throws IOException {
        JSONObject result = new JSONObject();
        try { result.put("parse", 0).put("url", url).put("header", new JSONObject(headers)); }
        catch (Exception e) { throw new IOException("视频地址无效", e); }
        return EpisodeResolver.fromResult(result);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static EpisodeResolver.Video sniff(Context context, SourceBean source, String pageUrl, String raw,
                                                Map<String, String> inherited, Cancel cancel) throws Exception {
        CountDownLatch found = new CountDownLatch(1);
        AtomicReference<EpisodeResolver.Video> media = new AtomicReference<>();
        AtomicReference<WebView> view = new AtomicReference<>();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (cancel.stopped()) { found.countDown(); return; }
            try {
                WebView web = new WebView(context);
                view.set(web);
                WebSettings settings = web.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setBlockNetworkImage(true);
                if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                String ua = header(inherited, "User-Agent");
                if (ua != null) settings.setUserAgentString(ua);
                String cookie = header(inherited, "Cookie");
                if (cookie != null) CookieManager.getInstance().setCookie(pageUrl, cookie);
                web.setWebViewClient(new WebViewClient() {
                    private void capture(String url, Map<String, String> requestHeaders) {
                        if (media.get() != null || url.contains("url=http") || url.contains(".html")
                                || VideoParseRuler.isFilter(raw, url)
                                || !VideoParseRuler.checkIsVideoForParse(raw, url)) return;
                        Map<String, String> headers = new HashMap<>(inherited);
                        for (String name : new String[]{"User-Agent", "Referer", "Origin"}) {
                            String value = header(requestHeaders, name);
                            if (value != null) headers.put(name, value);
                        }
                        if (header(headers, "Referer") == null) headers.put("Referer", pageUrl);
                        try {
                            String cookie = CookieManager.getInstance().getCookie(url);
                            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
                            EpisodeResolver.Video result = video(url, headers);
                            if (media.compareAndSet(null, result)) found.countDown();
                        } catch (Exception ignored) { }
                    }

                    @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                        capture(request.getUrl().toString(), request.getRequestHeaders());
                        return null;
                    }

                    @Override public WebResourceResponse shouldInterceptRequest(WebView web, String url) {
                        capture(url, new HashMap<>());
                        return null;
                    }

                    @Override public void onPageFinished(WebView web, String url) {
                        String script = source.getClickSelector();
                        if (TextUtils.isEmpty(script)) script = VideoParseRuler.getHostScript(url);
                        if (TextUtils.isEmpty(script)) return;
                        if (script.contains(";") && !script.endsWith(";")) {
                            String[] parts = script.split(";", 2);
                            if (!url.contains(parts[0])) return;
                            script = parts[1].trim();
                        }
                        web.evaluateJavascript(script, null);
                    }
                });
                web.loadUrl(pageUrl, inherited);
            } catch (Exception ignored) { found.countDown(); }
        });
        try {
            long deadline = System.currentTimeMillis() + 25000;
            while (!found.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel.stopped()) throw new IOException("下载已暂停");
                if (System.currentTimeMillis() >= deadline) throw new IOException("网页解析未发现视频请求");
            }
            if (cancel.stopped()) throw new IOException("下载已暂停");
            if (media.get() == null) throw new IOException("网页解析无法启动");
            return media.get();
        } finally {
            main.post(() -> {
                WebView web = view.get();
                if (web != null) { web.stopLoading(); web.destroy(); }
            });
        }
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet())
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }
}
