/*
 * Copyright 2026 Arif Banai (arif-banai)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.lavalink.youtube.track;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replacement for {@code dev.lavalink.youtube.track.YoutubePersistentHttpStream} that reads a
 * YouTube stream in bounded ranges and prefetches the next range in the background.
 *
 * <p><b>Why the ranges are bounded:</b> youtube-source's original implementation asked for a
 * byte range with the legacy {@code range=} <em>query parameter</em> and deliberately disabled
 * the HTTP {@code Range} header. YouTube no longer accepts that on its own: a request without a
 * {@code Range} header is answered with {@code HTTP 403}, and the {@code range=} parameter on its
 * own is not enough either. On top of that, YouTube rejects a range that is larger than
 * {@value #WINDOW_SIZE} bytes, so the stream has to be read in windows.
 *
 * <p><b>Why the prefetching matters:</b> every window is a separate HTTP request, and a request
 * costs a round trip. Without prefetching, the decoder runs dry while the next window is being
 * fetched, the player has no frame to send, and Discord's voice client receives a gap. Gaps of
 * 20 ms are audible as clicks. With prefetching, the next window is already in memory by the time
 * the decoder needs it, so the stream never stalls.
 *
 * <p>The prefetched window is also what makes seeking cheap: the demuxer of a progressive MP4
 * seeks for nearly every chunk, and a seek into the buffered window costs nothing.
 *
 * @author Michaili K. &lt;git@michaili.dev&gt;
 * @author John Grosh (jagrosh)
 * @author Arif Banai (arif-banai)
 */
public class YoutubePersistentHttpStream extends PersistentHttpStream
{
    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubePersistentHttpStream.class);

    /**
     * The largest range YouTube accepts in a single request. Anything bigger is answered with
     * {@code HTTP 403}.
     */
    public static final long WINDOW_SIZE = 512 * 1024L;

    /**
     * When fewer than this many bytes are left in the current window, the next window is
     * fetched in the background.
     */
    private static final int PREFETCH_THRESHOLD = 128 * 1024;

    /**
     * The number of HTTP requests this class has opened, over all instances.
     */
    private static final AtomicLong REQUESTS = new AtomicLong();

    /**
     * @return the number of HTTP requests opened by this class
     */
    public static long getRequestCount()
    {
        return REQUESTS.get();
    }

    /**
     * Resets the request counter.
     */
    public static void resetRequestCount()
    {
        REQUESTS.set(0);
    }

    private final ExecutorService prefetcher;
    private final AtomicLong requestCounter = new AtomicLong();

    /**
     * The bytes of the current window that have not been handed out yet.
     */
    private byte[] window = new byte[0];
    private int windowPos;
    private int windowLimit;

    /**
     * The file offset of {@code window[windowPos]}.
     */
    private long windowStart;

    /**
     * The window that is being fetched in the background, if any.
     */
    private Future<Chunk> prefetched;

    /**
     * A window of the stream.
     *
     * @param start the file offset the window starts at
     * @param data the bytes of the window
     */
    private record Chunk(long start, byte[] data)
    {
    }

    /**
     * The total size of the content, if it was known up front. {@code -1} when unknown.
     */
    private final long declaredLength;

    /**
     * The total size of the content, learned from the {@code Content-Range} response header.
     * {@code -1} when still unknown.
     */
    private long totalLength = -1;

    /**
     * @param httpInterface The HTTP interface to use for requests
     * @param contentUrl The URL of the resource
     * @param contentLength The length of the resource in bytes, or {@code -1} if unknown
     */
    public YoutubePersistentHttpStream(HttpInterface httpInterface, URI contentUrl, long contentLength)
    {
        super(httpInterface, contentUrl, contentLength);

        // PersistentHttpStream overwrites contentLength with the Content-Length of the
        // response, which for a partial response is the size of the window rather than the
        // size of the file. Keep the declared size separately.
        this.declaredLength = (contentLength > 0 && contentLength < Long.MAX_VALUE) ? contentLength : -1;

        this.prefetcher = Executors.newSingleThreadExecutor(new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable r)
            {
                Thread thread = new Thread(r, "yt-stream-prefetch");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * The total length of the content.
     *
     * <p>This matters more than it looks. The demuxer of a progressive MP4 builds its root
     * section from the length of the stream:
     *
     * <pre>
     *   this.root = new MpegSectionInfo(0, inputStream.getContentLength(), "root");
     * </pre>
     *
     * and it uses that length to work out where the {@code moov} atom is. The legacy format
     * (itag 18) has no valid content length of its own, so the value would be
     * {@code Long.MAX_VALUE}, the atom boundaries would be nonsense, and the demuxer would
     * read the sample tables from the wrong place. That shows up as a track that plays but
     * breaks up.
     *
     * <p>The real size is learned from the {@code Content-Range} header of the first response,
     * so the first window is fetched here if it has not been fetched yet.
     *
     * @return the total length of the content
     */
    @Override
    public long getContentLength()
    {
        if (totalLength < 0 && declaredLength < 0 && windowLimit == 0)
        {
            Chunk chunk = fetchWindow(0);
            if (chunk != null)
            {
                install(chunk);
            }
        }

        long total = getTotalLength();
        return total > 0 ? total : super.getContentLength();
    }

    /**
     * Reads a single byte.
     *
     * <p>The inherited implementation reads from the parent's own connection, which this class
     * does not use, so it has to be overridden.
     */
    @Override
    public int read() throws IOException
    {
        if (windowPos >= windowLimit && !loadNextWindow())
        {
            return -1;
        }

        int value = window[windowPos] & 0xFF;
        windowPos++;
        position++;
        maybePrefetch();
        return value;
    }

    /**
     * @return the number of bytes that can be read without blocking
     */
    @Override
    public int available() throws IOException
    {
        return windowLimit - windowPos;
    }

    /**
     * Reads from the current window, moving on to the next one when it is used up.
     */
    @Override
    protected int internalRead(byte[] b, int off, int len, boolean attemptReconnect) throws IOException
    {
        while (true)
        {
            if (windowPos < windowLimit)
            {
                int n = Math.min(len, windowLimit - windowPos);
                System.arraycopy(window, windowPos, b, off, n);
                windowPos += n;
                position += n;
                maybePrefetch();
                return n;
            }

            if (!loadNextWindow())
            {
                return -1;
            }
        }
    }

    /**
     * Skips over bytes. HTTP responses do not support skipping, so the bytes are read and
     * discarded, which also continues across window boundaries.
     */
    @Override
    protected long internalSkip(long n, boolean attemptReconnect) throws IOException
    {
        long remaining = n;
        long skipped = 0;
        byte[] discard = new byte[8192];

        while (remaining > 0)
        {
            if (windowPos >= windowLimit && !loadNextWindow())
            {
                break;
            }

            int available = windowLimit - windowPos;
            int chunk = (int) Math.min(remaining, Math.min(available, discard.length));
            System.arraycopy(window, windowPos, discard, 0, chunk);
            windowPos += chunk;
            position += chunk;
            skipped += chunk;
            remaining -= chunk;
            maybePrefetch();
        }

        return skipped;
    }

    /**
     * This stream can always be re-opened at an arbitrary position, because every window is
     * requested with its own {@code Range} header.
     */
    @Override
    public boolean canSeekHard()
    {
        return true;
    }

    /**
     * Re-opens the stream at the given position.
     *
     * <p>When the position is inside the window that is already buffered, the cursor is simply
     * moved and nothing is fetched again. The demuxer of a progressive MP4 seeks for nearly
     * every chunk, and those seeks usually land inside the buffered window, so this saves a
     * request each time.
     */
    @Override
    protected void seekHard(long newPosition) throws IOException
    {
        if (newPosition >= windowStart && newPosition < windowStart + windowLimit)
        {
            windowPos = (int) (newPosition - windowStart);
            position = newPosition;
            return;
        }

        cancelPrefetch();
        window = new byte[0];
        windowPos = 0;
        windowLimit = 0;
        windowStart = newPosition;
        position = newPosition;
    }

    /**
     * Starts fetching the next window when the current one is nearly used up.
     */
    private void maybePrefetch()
    {
        if (prefetched != null || windowLimit - windowPos >= PREFETCH_THRESHOLD)
        {
            return;
        }

        final long nextStart = windowStart + windowLimit;
        if (!hasMoreContent())
        {
            return;
        }

        prefetched = prefetcher.submit(() -> fetchWindow(nextStart));
    }

    /**
     * Loads the next window into memory, using the prefetched one when it is ready.
     *
     * @return true if a window was loaded
     */
    private boolean loadNextWindow() throws IOException
    {
        Chunk chunk = null;

        Future<Chunk> pending = prefetched;
        prefetched = null;
        if (pending != null)
        {
            try
            {
                chunk = pending.get(30, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                chunk = null;
            }
        }

        if (chunk == null)
        {
            long start = windowStart + windowLimit;
            if (!hasMoreContent(start))
            {
                return false;
            }
            chunk = fetchWindow(start);
            if (chunk == null || chunk.data().length == 0)
            {
                return false;
            }
        }

        window = chunk.data();
        windowPos = 0;
        windowLimit = window.length;
        windowStart = chunk.start();
        position = windowStart;

        maybePrefetch();
        return true;
    }

    /**
     * Makes a window the current one.
     */
    private void install(Chunk chunk)
    {
        window = chunk.data();
        windowPos = 0;
        windowLimit = window.length;
        windowStart = chunk.start();
        position = windowStart;
    }

    /**
     * Fetches a single window.
     *
     * @param start the file offset to start at
     * @return the window, or null when the request failed
     */
    private Chunk fetchWindow(long start)
    {
        long end = start + WINDOW_SIZE - 1;
        long total = getTotalLength();
        if (total > 0 && end > total - 1)
        {
            end = total - 1;
        }

        long started = System.nanoTime();
        try
        {
            HttpGet request = new HttpGet(contentUrl);
            request.setHeader(HttpHeaders.RANGE, "bytes=" + start + "-" + end);

            REQUESTS.incrementAndGet();
            requestCounter.incrementAndGet();

            try (CloseableHttpResponse response = httpInterface.execute(request))
            {
                int status = response.getStatusLine().getStatusCode();
                if (status != 200 && status != 206)
                {
                    LOGGER.debug("Stream window {}..{} rejected with status {}", start, end, status);
                    return null;
                }

                byte[] data = response.getEntity() == null
                        ? new byte[0]
                        : response.getEntity().getContent().readAllBytes();

                learnTotalLength(response);
                LOGGER.debug("Stream window {}..{} fetched in {} ms ({} bytes)",
                        start, end, (System.nanoTime() - started) / 1_000_000, data.length);
                return new Chunk(start, data);
            }
        }
        catch (Exception e)
        {
            LOGGER.debug("Stream window {}..{} failed: {}", start, end, e.toString());
            return null;
        }
    }

    /**
     * Reads the total size of the content from the {@code Content-Range} response header.
     */
    private void learnTotalLength(CloseableHttpResponse response)
    {
        org.apache.http.Header header = response.getFirstHeader("Content-Range");
        if (header == null)
        {
            return;
        }
        String value = header.getValue();
        int slash = value.lastIndexOf('/');
        if (slash > 0 && slash < value.length() - 1)
        {
            try
            {
                long parsed = Long.parseLong(value.substring(slash + 1).trim());
                if (parsed > 0)
                {
                    totalLength = parsed;
                }
            }
            catch (NumberFormatException ignored)
            {
                // Leave the total unknown.
            }
        }
    }

    /**
     * @return the total size of the content, or {@code -1} if it is not known
     */
    private long getTotalLength()
    {
        return declaredLength > 0 ? declaredLength : totalLength;
    }

    /**
     * @return true if there is still content left to read
     */
    private boolean hasMoreContent()
    {
        return hasMoreContent(windowStart);
    }

    /**
     * @param start the file offset to check
     * @return true if there is still content left to read from that offset
     */
    private boolean hasMoreContent(long start)
    {
        long total = getTotalLength();
        return total <= 0 || start < total;
    }

    /**
     * Cancels a prefetch that is no longer needed.
     */
    private void cancelPrefetch()
    {
        Future<Chunk> pending = prefetched;
        prefetched = null;
        if (pending != null)
        {
            pending.cancel(true);
        }
    }

    /**
     * Checks the status code of the stream by opening the first window.
     *
     * <p>The inherited implementation uses the parent's own connection handling, which this
     * class does not use, so the request is made here instead.
     */
    @Override
    public int checkStatusCode() throws IOException
    {
        Chunk chunk = fetchWindow(0);
        if (chunk == null)
        {
            return 0;
        }
        window = chunk.data();
        windowPos = 0;
        windowLimit = window.length;
        windowStart = chunk.start();
        position = windowStart;
        maybePrefetch();
        return 200;
    }

    @Override
    public void close() throws IOException
    {
        cancelPrefetch();
        prefetcher.shutdownNow();
        super.close();
    }
}
