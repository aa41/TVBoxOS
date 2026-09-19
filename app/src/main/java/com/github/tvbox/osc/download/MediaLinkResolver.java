package com.github.tvbox.osc.download;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MediaLinkResolver {
    static final class Link {
        final String url;
        final Map<String, String> headers;
        Link(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }

    static Link find(String body, String type, String pageUrl) {
        String trimmed = body.trim();
        List<Link> links = new ArrayList<>();
        if (type.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try { collectJson(new JSONTokener(trimmed).nextValue(), pageUrl, new HashMap<>(), links, 0); }
            catch (Exception ignored) { }
        } else if (type.contains("html") || trimmed.startsWith("<")) {
            Document page = Jsoup.parse(body, pageUrl);
            for (Element element : page.select("video[src], video[data-src], video source[src], source[src]")) {
                add(links, pageUrl, element.hasAttr("src") ? element.attr("src") : element.attr("data-src"), new HashMap<>());
            }
            for (Element element : page.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]"))
                add(links, pageUrl, element.attr("content"), new HashMap<>());
            for (Element element : page.select("a[href]")) {
                String href = element.attr("abs:href");
                if (isMediaPath(href)) add(links, pageUrl, href, new HashMap<>());
            }
            for (Element element : page.select("script[type=application/ld+json]")) {
                try { collectJson(new JSONTokener(element.data()).nextValue(), pageUrl, new HashMap<>(), links, 0); }
                catch (Exception ignored) { }
            }
        }
        for (Link link : links) if (isMediaPath(link.url)) return link;
        return links.isEmpty() ? null : links.get(0);
    }

    private static void collectJson(Object value, String base, Map<String, String> inherited,
                                    List<Link> links, int depth) {
        if (depth > 5 || links.size() >= 20) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < Math.min(array.length(), 30); i++) {
                Object item = array.opt(i);
                if (item instanceof String && ((String) item).startsWith("http"))
                    add(links, base, (String) item, inherited);
                else collectJson(item, base, inherited, links, depth + 1);
            }
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, String> headers = new HashMap<>(inherited);
            readHeaders(headers, object.opt("header"));
            readHeaders(headers, object.opt("headers"));
            for (String key : new String[]{"url", "src", "file", "m3u8", "contentUrl", "video", "playUrl"}) {
                Object candidate = object.opt(key);
                if (candidate instanceof String) add(links, base, (String) candidate, headers);
                else if (candidate instanceof JSONArray) collectJson(candidate, base, headers, links, depth + 1);
            }
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if ("header".equals(key) || "headers".equals(key)) continue;
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray)
                    collectJson(child, base, headers, links, depth + 1);
                else if ("data".equals(key) && child instanceof String) {
                    String raw = ((String) child).trim();
                    if (raw.startsWith("{") || raw.startsWith("[")) try {
                        collectJson(new JSONTokener(raw).nextValue(), base, headers, links, depth + 1);
                    } catch (Exception ignored) { }
                    else add(links, base, raw, headers);
                }
            }
        }
    }

    private static void readHeaders(Map<String, String> headers, Object raw) {
        try {
            JSONObject json = raw instanceof JSONObject ? (JSONObject) raw
                    : raw instanceof String ? new JSONObject((String) raw) : null;
            if (json == null) return;
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String key = keys.next();
                headers.put(key, json.optString(key));
            }
        } catch (Exception ignored) { }
    }

    private static void add(List<Link> links, String base, String value, Map<String, String> headers) {
        if (value == null || value.isEmpty() || links.size() >= 20) return;
        try {
            String url = new URL(new URL(base), value.trim()).toString();
            if (!url.startsWith("http://") && !url.startsWith("https://")) return;
            for (Link link : links) if (link.url.equals(url)) return;
            links.add(new Link(url, new HashMap<>(headers)));
        } catch (Exception ignored) { }
    }

    private static boolean isMediaPath(String url) {
        try {
            String path = new URL(url).getPath().toLowerCase(Locale.ROOT);
            return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv")
                    || path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".ts")
                    || path.endsWith(".m4v");
        } catch (Exception ignored) { return false; }
    }
}
