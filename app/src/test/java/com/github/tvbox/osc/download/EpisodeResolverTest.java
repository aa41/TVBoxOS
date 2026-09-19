package com.github.tvbox.osc.download;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class EpisodeResolverTest {
    @Test public void directArrayUrlPreservesRequestHeaders() throws Exception {
        JSONObject result = new JSONObject();
        result.put("parse", 1);
        result.put("url", new JSONArray().put("bad").put("https://cdn.example/video.m3u8?token=1"));
        result.put("headers", new JSONObject().put("Cookie", "session=1").put("Referer", "https://example.com"));
        result.put("user-agent", "TVBox");
        EpisodeResolver.Video video = EpisodeResolver.fromResult(result);
        assertEquals("https://cdn.example/video.m3u8?token=1", video.url);
        assertEquals("session=1", video.headers.get("Cookie"));
        assertEquals("https://example.com", video.headers.get("Referer"));
        assertEquals("TVBox", video.headers.get("User-Agent"));
    }

    @Test public void webParserPageCanBeInspectedForStaticMedia() throws Exception {
        JSONObject result = new JSONObject().put("parse", 1)
                .put("url", "https://example.com/watch?id=1")
                .put("headers", new JSONObject().put("Cookie", "session=1"));
        assertEquals("https://example.com/watch?id=1", EpisodeResolver.fromResult(result).url);
    }

    @Test public void localProxyAndDashAreRejected() throws Exception {
        assertError(new JSONObject().put("parse", 0).put("url", "http://127.0.0.1:9978/proxy.m3u8"), "本地代理");
        assertError(new JSONObject().put("parse", 0).put("url", "http://[::1]:9978/proxy.m3u8"), "本地代理");
        assertError(new JSONObject().put("parse", 0).put("url", "https://cdn.example/manifest.mpd"), "DASH");
        assertError(new JSONObject().put("parse", 0).put("url", "not-a-url"), "不是可下载");
    }

    @Test public void missingResultReportsResolutionFailure() throws Exception {
        try {
            EpisodeResolver.fromResult(null);
            fail("Missing play result must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未返回"));
        }
    }

    private static void assertError(JSONObject result, String message) throws Exception {
        try {
            EpisodeResolver.fromResult(result);
            fail("Expected: " + message);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
