package com.jagrosh.jmusicbot.unit.audio;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lavalink.youtube.track.YoutubePersistentHttpStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the windowed YouTube stream from a local server that answers range requests.
 *
 * <p>The important case is a window the server refuses. That used to end the stream quietly,
 * so a track YouTube stopped serving looked like a track that had finished.
 */
@DisplayName("YouTube stream windows")
class YoutubeStreamWindowTest
{
    private static final int WINDOW = (int) YoutubePersistentHttpStream.WINDOW_SIZE;

    private final byte[] content = new byte[WINDOW * 2 + 300_000];
    private final AtomicInteger requests = new AtomicInteger();

    /** Maps the start of a requested range to a status to answer with, or null to serve it. */
    private volatile LongFunction<Integer> failure = start -> null;
    private volatile boolean announceTotal = true;

    private HttpServer server;
    private HttpInterfaceManager manager;
    private HttpInterface httpInterface;

    @BeforeEach
    void setUp() throws IOException
    {
        new Random(7).nextBytes(content);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", this::serve);
        server.start();
        manager = HttpClientTools.createDefaultThreadLocalManager();
        httpInterface = manager.getInterface();
    }

    @AfterEach
    void tearDown() throws IOException
    {
        httpInterface.close();
        manager.close();
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException
    {
        requests.incrementAndGet();
        String range = exchange.getRequestHeaders().getFirst("Range");
        String[] bounds = range.substring("bytes=".length()).split("-");
        long start = Long.parseLong(bounds[0]);
        long end = Math.min(Long.parseLong(bounds[1]), content.length - 1);

        Integer status = failure.apply(start);
        if (status == null && start >= content.length)
            status = 416;
        if (status != null)
        {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }

        int length = (int) (end - start + 1);
        exchange.getResponseHeaders().set("Content-Range",
                "bytes " + start + "-" + end + "/" + (announceTotal ? String.valueOf(content.length) : "*"));
        exchange.sendResponseHeaders(206, length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(content, (int) start, length);
        }
    }

    private YoutubePersistentHttpStream open()
    {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
        return new YoutubePersistentHttpStream(httpInterface, uri, Long.MAX_VALUE);
    }

    private static byte[] readAll(YoutubePersistentHttpStream stream, ByteArrayOutputStream sink) throws IOException
    {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = stream.read(buffer, 0, buffer.length)) != -1)
            sink.write(buffer, 0, read);
        return sink.toByteArray();
    }

    @Test
    @DisplayName("the whole content is read across windows and its length is learned")
    void readsEverythingAcrossWindows() throws IOException
    {
        try (YoutubePersistentHttpStream stream = open())
        {
            assertEquals(content.length, stream.getContentLength());
            assertArrayEquals(content, readAll(stream, new ByteArrayOutputStream()));
        }
        assertEquals(3, requests.get(), "one request per window, none past the end");
    }

    @Test
    @DisplayName("a refused window is an error, not the end of the track")
    void refusedWindowIsAnError() throws IOException
    {
        failure = start -> start >= WINDOW ? 403 : null;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        try (YoutubePersistentHttpStream stream = open())
        {
            IOException error = assertThrows(IOException.class, () -> readAll(stream, sink));
            assertTrue(error.getMessage().contains("403"), error.getMessage());
        }
        assertArrayEquals(Arrays.copyOf(content, WINDOW), sink.toByteArray(),
                "everything before the refused window was still delivered");
    }

    @Test
    @DisplayName("a temporary failure is retried")
    void temporaryFailureIsRetried() throws IOException
    {
        // The background fetch and the first direct attempt fail; the next attempt works.
        AtomicInteger failures = new AtomicInteger(2);
        failure = start -> start == WINDOW && failures.getAndDecrement() > 0 ? 503 : null;

        try (YoutubePersistentHttpStream stream = open())
        {
            assertArrayEquals(content, readAll(stream, new ByteArrayOutputStream()));
        }
    }

    @Test
    @DisplayName("a window that keeps failing is reported after a few attempts")
    void persistentFailureIsReported() throws IOException
    {
        failure = start -> start == WINDOW ? 500 : null;

        try (YoutubePersistentHttpStream stream = open())
        {
            IOException error = assertThrows(IOException.class,
                    () -> readAll(stream, new ByteArrayOutputStream()));
            assertTrue(error.getMessage().contains("500"), error.getMessage());
        }
        // The first window, the background fetch of the second one and three direct attempts.
        assertEquals(5, requests.get());
    }

    @Test
    @DisplayName("a stream of unknown length ends cleanly")
    void unknownLengthEndsCleanly() throws IOException
    {
        announceTotal = false;

        try (YoutubePersistentHttpStream stream = open())
        {
            assertArrayEquals(content, readAll(stream, new ByteArrayOutputStream()));
        }
    }

    @Test
    @DisplayName("a seek inside the buffered window costs no request")
    void seekInsideTheWindowIsFree() throws IOException
    {
        try (YoutubePersistentHttpStream stream = open())
        {
            byte[] first = new byte[1000];
            assertEquals(1000, stream.read(first, 0, 1000));
            int before = requests.get();

            stream.seek(200_000);
            byte[] later = new byte[1000];
            assertEquals(1000, stream.read(later, 0, 1000));

            assertEquals(before, requests.get());
            assertArrayEquals(Arrays.copyOfRange(content, 200_000, 201_000), later);
        }
    }
}
