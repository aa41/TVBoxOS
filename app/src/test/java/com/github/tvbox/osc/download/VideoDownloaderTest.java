package com.github.tvbox.osc.download;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;

import static org.junit.Assert.*;

public class VideoDownloaderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private TestServer server;
    private String base;

    @Before public void start() throws Exception {
        server = new TestServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        base = "http://127.0.0.1:" + server.getListeningPort();
    }

    @After public void stop() { server.stop(); }

    @Test public void rangedFileUsesMultipleRequestsAndResumes() throws Exception {
        byte[] data = new byte[3 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        AtomicInteger ranges = new AtomicInteger();
        server.on("/video.mp4", request -> {
            String range = request.getHeaders().get("range");
            if (range == null) return send(data);
            ranges.incrementAndGet();
            String[] bounds = range.substring(6).split("-");
            int start = Integer.parseInt(bounds[0]);
            int end = bounds.length > 1 && !bounds[1].isEmpty() ? Integer.parseInt(bounds[1]) : data.length - 1;
            end = Math.min(end, data.length - 1);
            NanoHTTPD.Response response = response(NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                    Arrays.copyOfRange(data, start, end + 1));
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            return response;
        });
        File dir = folder.newFolder("range");
        String path = download("/video.mp4", dir, 4);
        assertArrayEquals(data, Files.readAllBytes(new File(path).toPath()));
        assertTrue("probe plus multiple workers", ranges.get() >= 3);
    }

    @Test public void originWithoutRangesFallsBackToOneStream() throws Exception {
        byte[] data = "progressive video content".getBytes(StandardCharsets.UTF_8);
        server.on("/file.mp4", request -> send(data));
        assertArrayEquals(data, Files.readAllBytes(new File(download("/file.mp4", folder.newFolder(), 4)).toPath()));
    }

    @Test public void misleadingRangeSupportFallsBackToSingleRequest() throws Exception {
        byte[] data = new byte[2 * 1024 * 1024];
        Arrays.fill(data, (byte) 37);
        server.on("/misleading.mp4", request -> {
            String range = request.getHeaders().get("range");
            if ("bytes=0-511".equals(range)) {
                NanoHTTPD.Response response = response(NanoHTTPD.Response.Status.PARTIAL_CONTENT, Arrays.copyOf(data, 512));
                response.addHeader("Content-Range", "bytes 0-511/" + data.length);
                return response;
            }
            return send(data);
        });
        assertArrayEquals(data, Files.readAllBytes(new File(download("/misleading.mp4", folder.newFolder(), 4)).toPath()));
    }

    @Test public void hlsMasterDownloadsSegmentsAndKeyAsLocalFiles() throws Exception {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=200\nhigh.m3u8\n";
        String playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXTINF:5,\na.ts\n#EXTINF:5,\nb.ts\n#EXT-X-ENDLIST\n";
        server.on("/master.m3u8", r -> send(master.getBytes(StandardCharsets.UTF_8)));
        server.on("/high.m3u8", r -> send(playlist.getBytes(StandardCharsets.UTF_8)));
        server.on("/key.bin", r -> send(new byte[16]));
        server.on("/a.ts", r -> send(new byte[]{1, 2, 3}));
        server.on("/b.ts", r -> send(new byte[]{4, 5, 6}));
        File dir = folder.newFolder("hls");
        String path = download("/master.m3u8", dir, 4);
        String local = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        assertFalse(local.contains("http://"));
        assertTrue(local.contains("URI=\"00000.key\""));
        assertTrue(local.contains("00001.seg"));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(new File(dir, "00002.seg").toPath()));
    }

    @Test public void livePlaylistIsRejected() throws Exception {
        server.on("/live.m3u8", r -> send("#EXTM3U\n#EXTINF:5,\na.ts\n".getBytes(StandardCharsets.UTF_8)));
        try {
            download("/live.m3u8", folder.newFolder(), 2);
            fail("Live playlists cannot be cached as finished videos");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("直播"));
        }
    }

    @Test public void separateAudioRenditionRemainsPlayableOffline() throws Exception {
        String master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",NAME=\"main\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=200,AUDIO=\"sound\"\nvideo.m3u8\n";
        String video = "#EXTM3U\n#EXTINF:5,\nvideo.ts\n#EXT-X-ENDLIST\n";
        String audio = "#EXTM3U\n#EXTINF:5,\naudio.ts\n#EXT-X-ENDLIST\n";
        server.on("/master.m3u8", r -> send(master.getBytes(StandardCharsets.UTF_8)));
        server.on("/video.m3u8", r -> send(video.getBytes(StandardCharsets.UTF_8)));
        server.on("/audio.m3u8", r -> send(audio.getBytes(StandardCharsets.UTF_8)));
        server.on("/video.ts", r -> send(new byte[]{1}));
        server.on("/audio.ts", r -> send(new byte[]{2}));
        File dir = folder.newFolder("separate-audio");
        String local = new String(Files.readAllBytes(new File(download("/master.m3u8", dir, 4)).toPath()), StandardCharsets.UTF_8);
        assertTrue(local.contains("URI=\"audio_track.m3u8\""));
        assertTrue(local.contains("video_track.m3u8"));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(new File(dir, "00001.seg").toPath()));
    }

    @Test public void playlistUsesRedirectDestinationForRelativeSegments() throws Exception {
        server.on("/redirect.m3u8", r -> {
            NanoHTTPD.Response response = response(NanoHTTPD.Response.Status.REDIRECT, new byte[0]);
            response.addHeader("Location", "/cdn/video.m3u8");
            return response;
        });
        server.on("/cdn/video.m3u8", r -> send(
                "#EXTM3U\n#EXTINF:5,\nsegment.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8)));
        server.on("/cdn/segment.ts", r -> send(new byte[]{9, 8, 7}));
        File dir = folder.newFolder("redirect");
        download("/redirect.m3u8", dir, 2);
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(new File(dir, "00000.seg").toPath()));
    }

    @Test public void htmlResponseIsNotSavedAsVideo() throws Exception {
        server.on("/watch", r -> NanoHTTPD.newFixedLengthResponse("<html>watch page</html>"));
        try {
            download("/watch", folder.newFolder(), 2);
            fail("Web pages are not videos");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未找到可下载"));
        }
    }

    @Test public void plainTextErrorIsNotSavedAsVideo() throws Exception {
        server.on("/error.mp4", r -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/plain", "expired token"));
        try {
            download("/error.mp4", folder.newFolder(), 2);
            fail("Text error responses are not videos");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未找到可下载"));
        }
    }

    @Test public void emptyResponseIsNotSavedAsVideo() throws Exception {
        server.on("/empty.mp4", r -> send(new byte[0]));
        try {
            download("/empty.mp4", folder.newFolder(), 1);
            fail("Empty responses are not videos");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("为空"));
        }
    }

    @Test public void expiredLinkAfterProbeDoesNotCreateCompletedVideo() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.on("/expiring.mp4", r -> requests.incrementAndGet() == 1
                ? send(new byte[]{1, 2, 3})
                : NanoHTTPD.newFixedLengthResponse("<html>expired</html>"));
        try {
            download("/expiring.mp4", folder.newFolder(), 1);
            fail("The second response must also be validated");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不是视频"));
        }
    }

    @Test public void byteRangePlaylistWritesIndependentOfflineSegments() throws Exception {
        byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        server.on("/range.m3u8", r -> send(("#EXTM3U\n#EXT-X-MAP:URI=\"part.ts\",BYTERANGE=\"2@0\"\n"
                + "#EXTINF:2,\n#EXT-X-BYTERANGE:3@2\npart.ts\n"
                + "#EXTINF:2,\n#EXT-X-BYTERANGE:3\npart.ts\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8)));
        server.on("/part.ts", r -> {
            String[] bounds = r.getHeaders().get("range").substring(6).split("-");
            int start = Integer.parseInt(bounds[0]), end = Integer.parseInt(bounds[1]);
            NanoHTTPD.Response response = response(NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                    Arrays.copyOfRange(data, start, end + 1));
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            return response;
        });
        File dir = folder.newFolder();
        String local = new String(Files.readAllBytes(new File(download("/range.m3u8", dir, 3)).toPath()), StandardCharsets.UTF_8);
        assertFalse(local.contains("BYTERANGE"));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(new File(dir, "00000.bin").toPath()));
        assertArrayEquals(new byte[]{3, 4, 5}, Files.readAllBytes(new File(dir, "00001.seg").toPath()));
        assertArrayEquals(new byte[]{6, 7, 8}, Files.readAllBytes(new File(dir, "00002.seg").toPath()));
    }

    @Test public void htmlVideoTagCarriesPageRefererAndSessionCookie() throws Exception {
        server.on("/watch", r -> {
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse("<video><source src='/clip.mp4'></video>");
            response.addHeader("Set-Cookie", "session=ok; Path=/");
            return response;
        });
        server.on("/clip.mp4", r -> {
            if (!base.concat("/watch").equals(r.getHeaders().get("referer"))
                    || !"session=ok".equals(r.getHeaders().get("cookie")))
                return NanoHTTPD.newFixedLengthResponse("<html>denied</html>");
            return send(new byte[]{1, 2, 3});
        });
        assertArrayEquals(new byte[]{1, 2, 3},
                Files.readAllBytes(new File(download("/watch", folder.newFolder(), 2)).toPath()));
    }

    @Test public void jsonApiCanResolveMediaPlaylist() throws Exception {
        server.on("/api", r -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/json", "{\"data\":{\"url\":\"" + base
                        + "/movie.m3u8\",\"header\":{\"Referer\":\"https://example.com/movie\"}}}"));
        server.on("/movie.m3u8", r -> "https://example.com/movie".equals(r.getHeaders().get("referer"))
                ? send("#EXTM3U\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8))
                : NanoHTTPD.newFixedLengthResponse("<html>denied</html>"));
        server.on("/a.ts", r -> "https://example.com/movie".equals(r.getHeaders().get("referer"))
                ? send(new byte[]{9, 8}) : NanoHTTPD.newFixedLengthResponse("<html>denied</html>"));
        File dir = folder.newFolder();
        download("/api", dir, 2);
        assertArrayEquals(new byte[]{9, 8}, Files.readAllBytes(new File(dir, "00000.seg").toPath()));
    }

    @Test public void parserQueryContainingM3u8IsNotMistakenForPlaylist() throws Exception {
        server.on("/parser", r -> NanoHTTPD.newFixedLengthResponse("<video src='/clip.mp4'></video>"));
        server.on("/clip.mp4", r -> send(new byte[]{5, 4, 3}));
        assertArrayEquals(new byte[]{5, 4, 3}, Files.readAllBytes(new File(
                download("/parser?url=video.m3u8", folder.newFolder(), 1)).toPath()));
    }

    private String download(String path, File dir, int threads) throws Exception {
        return new VideoDownloader(base + path, new JSONObject(), dir, threads,
                (bytes, total, items, count) -> { }, () -> false).run();
    }

    private static NanoHTTPD.Response send(byte[] bytes) {
        return response(NanoHTTPD.Response.Status.OK, bytes);
    }

    private static NanoHTTPD.Response response(NanoHTTPD.Response.Status status, byte[] bytes) {
        return NanoHTTPD.newFixedLengthResponse(status, "application/octet-stream",
                new ByteArrayInputStream(bytes), bytes.length);
    }

    private static class TestServer extends NanoHTTPD {
        interface Handler { Response handle(IHTTPSession request); }
        private final Map<String, Handler> handlers = new HashMap<>();
        TestServer() { super("127.0.0.1", 0); }
        void on(String path, Handler handler) { handlers.put(path, handler); }
        @Override public Response serve(IHTTPSession session) {
            Handler handler = handlers.get(session.getUri());
            return handler == null ? NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "text/plain", "not found") : handler.handle(session);
        }
    }
}
