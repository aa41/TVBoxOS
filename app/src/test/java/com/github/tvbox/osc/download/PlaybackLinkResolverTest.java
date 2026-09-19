package com.github.tvbox.osc.download;

import com.github.tvbox.osc.bean.ParseBean;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

import static org.junit.Assert.*;

public class PlaybackLinkResolverTest {
    @Test public void playbackParserSelectionMatchesDefaultJsonAndNamedModes() {
        ParseBean selected = parser(3, "https://parser.example/mix", "mix");
        ParseBean defaultParser = parser(1, "https://parser.example/default?url=", "default");
        assertSame(defaultParser, PlaybackLinkResolver.chooseParser("", true, defaultParser, Arrays.asList(selected)));
        assertSame(selected, PlaybackLinkResolver.chooseParser("parse:mix", false, defaultParser, Arrays.asList(selected)));
        ParseBean json = PlaybackLinkResolver.chooseParser("json:https://parser.example/api?url=", false,
                defaultParser, Collections.emptyList());
        assertEquals(1, json.getType());
        assertEquals("https://parser.example/api?url=", json.getUrl());
    }

    @Test public void jsonParserEncodesEpisodeUrlAndCarriesHeadersIntoMedia() throws Exception {
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                assertEquals("token=ok", session.getHeaders().get("cookie"));
                assertEquals("https://site.example/watch?a=1&b=2", session.getParms().get("url"));
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"data\":{\"url\":\"https://cdn.example/movie.m3u8\","
                                + "\"header\":{\"Referer\":\"https://site.example/\"}}}");
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        try {
            ParseBean parser = parser(1, "http://127.0.0.1:" + server.getListeningPort() + "/api?url=", "json");
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", "token=ok");
            JSONObject result = PlaybackLinkResolver.jsonRequest(parser, "https://site.example/watch?a=1&b=2", headers);
            EpisodeResolver.Video video = PlaybackLinkResolver.resolveParserResult(null, null, result, "", headers, () -> false);
            assertEquals("https://cdn.example/movie.m3u8", video.url);
            assertEquals("token=ok", video.headers.get("Cookie"));
            assertEquals("https://site.example/", video.headers.get("Referer"));
        } finally { server.stop(); }
    }

    @Test public void parserWithoutMediaDoesNotFallThroughAsVideo() throws Exception {
        try {
            PlaybackLinkResolver.resolveParserResult(null, null, new JSONObject().put("data", new JSONObject()
                    .put("url", "not a video")), "", Collections.emptyMap(), () -> false);
            fail("Parser errors must not become download URLs");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未返回视频"));
        }
    }

    private static ParseBean parser(int type, String url, String name) {
        ParseBean parser = new ParseBean();
        parser.setType(type); parser.setUrl(url); parser.setName(name);
        return parser;
    }
}
