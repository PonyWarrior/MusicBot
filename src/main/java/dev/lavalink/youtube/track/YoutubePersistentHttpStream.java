package dev.lavalink.youtube.track;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
     * How often a window is requested before the stream gives up, and the pause before the
     * second attempt (the third waits twice as long).
     */
    private static final int FETCH_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 300;

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
    private volatile long totalLength = -1;

    /**
     * The HTTP interface is not thread safe, and both the playback thread and the prefetch
     * thread fetch windows, so only one of them may be inside a request at a time.
     */
    private final Object fetchLock = new Object();

    /**
     * The request that is on the wire, kept so it can be aborted. Interrupting a thread does
     * not interrupt blocking socket IO.
     */
    private volatile HttpGet inFlight;

    /**
     * A window that could not be fetched.
     */
    private static final class WindowException extends IOException
    {
        private final int status;

        private WindowException(String message, int status, Throwable cause)
        {
            super(message, cause);
            this.status = status;
        }
    }

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
            try
            {
                Chunk chunk = fetchWindow(0);
                // Only keep the window when the stream is still at the start; after a seek it
                // would move the stream back to offset zero.
                if (windowStart == 0)
                {
                    install(chunk);
                }
            }
            catch (IOException e)
            {
                // The length stays unknown; the read that follows reports the failure.
                LOGGER.debug("Could not learn the stream length: {}", e.getMessage());
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
        if (!hasMoreContent(nextStart))
        {
            return;
        }

        prefetched = prefetcher.submit(() -> fetchWindow(nextStart));
    }

    /**
     * Loads the next window into memory, using the prefetched one when it is ready.
     *
     * <p>A window that cannot be fetched is an error, not the end of the stream. Reporting it
     * as the end made a track that YouTube stopped serving look like it had simply finished.
     *
     * @return true if a window was loaded, false at the end of the content
     * @throws IOException when the window could not be fetched
     */
    private boolean loadNextWindow() throws IOException
    {
        long start = windowStart + windowLimit;
        Chunk chunk = awaitPrefetch();

        if (chunk == null || chunk.start() != start)
        {
            if (!hasMoreContent(start))
            {
                return false;
            }
            try
            {
                chunk = fetchWindowWithRetry(start);
            }
            catch (WindowException e)
            {
                // Without a known size the end shows up as "range not satisfiable".
                if (e.status == 416 && getTotalLength() <= 0)
                {
                    return false;
                }
                throw e;
            }
        }

        if (chunk.data().length == 0)
        {
            // With a known size an empty window in the middle is a failure. Installing it would
            // ask for the same window again and again.
            if (getTotalLength() > 0 && start < getTotalLength())
            {
                throw new IOException("YouTube returned no data for the stream window at " + start);
            }
            return false;
        }

        install(chunk);
        maybePrefetch();
        return true;
    }

    /**
     * Waits for the window that is being fetched in the background.
     *
     * @return the window, or null when there is none or it failed
     * @throws IOException when the thread was interrupted while waiting
     */
    private Chunk awaitPrefetch() throws IOException
    {
        Future<Chunk> pending = prefetched;
        prefetched = null;
        if (pending == null)
        {
            return null;
        }

        try
        {
            return pending.get(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            // The player interrupts this thread to seek or stop. Swallowing that would turn a
            // stop into one more network request.
            pending.cancel(true);
            abortRequest();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for stream data");
        }
        catch (TimeoutException e)
        {
            // Give up on the background request before fetching the window here; fetchWindow
            // waits for it to let go of the connection.
            pending.cancel(true);
            abortRequest();
            return null;
        }
        catch (ExecutionException | CancellationException e)
        {
            return null;
        }
    }

    /**
     * Fetches a window, trying again when the failure looks temporary.
     */
    private Chunk fetchWindowWithRetry(long start) throws IOException
    {
        WindowException last = null;
        for (int attempt = 0; attempt < FETCH_ATTEMPTS; attempt++)
        {
            if (attempt > 0)
            {
                try
                {
                    Thread.sleep(RETRY_DELAY_MILLIS * attempt);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while retrying a stream window");
                }
            }
            try
            {
                return fetchWindow(start);
            }
            catch (WindowException e)
            {
                last = e;
                // A refusal (403: the stream url expired, 404, 416) will not change on a retry.
                if (e.status >= 400 && e.status < 500 && e.status != 429)
                {
                    break;
                }
                LOGGER.debug("Stream window at {} failed (attempt {}): {}", start, attempt + 1, e.getMessage());
            }
        }
        LOGGER.warn("Giving up on the stream window at {}: {}", start, last.getMessage());
        throw last;
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
     * @return the window
     * @throws IOException when the request failed or was refused
     */
    private Chunk fetchWindow(long start) throws IOException
    {
        long end = start + WINDOW_SIZE - 1;
        long total = getTotalLength();
        if (total > 0 && end > total - 1)
        {
            end = total - 1;
        }

        HttpGet request = new HttpGet(contentUrl);
        request.setHeader(HttpHeaders.RANGE, "bytes=" + start + "-" + end);

        synchronized (fetchLock)
        {
            inFlight = request;
            // A cancelled prefetch must not start a request nobody is waiting for. This is
            // checked after the request became abortable, so a cancellation cannot slip between.
            if (Thread.currentThread().isInterrupted())
            {
                inFlight = null;
                throw new InterruptedIOException("Interrupted before fetching a stream window");
            }

            long started = System.nanoTime();
            try
            {
                REQUESTS.incrementAndGet();
                requestCounter.incrementAndGet();

                try (CloseableHttpResponse response = httpInterface.execute(request))
                {
                    int status = response.getStatusLine().getStatusCode();
                    // 200 means the server ignored the range and is sending the whole file, which
                    // is only the requested window when the window starts at zero.
                    if (status != 206 && !(status == 200 && start == 0))
                    {
                        throw new WindowException("YouTube answered the stream window " + start + ".." + end
                                + " with HTTP " + status, status, null);
                    }

                    byte[] data = response.getEntity() == null
                            ? new byte[0]
                            : response.getEntity().getContent().readNBytes((int) WINDOW_SIZE);

                    learnTotalLength(response);
                    if (status == 200 && totalLength < 0 && response.getEntity() != null
                            && response.getEntity().getContentLength() > 0)
                    {
                        // The whole file was sent, so its length is the total.
                        totalLength = response.getEntity().getContentLength();
                    }
                    LOGGER.debug("Stream window {}..{} fetched in {} ms ({} bytes)",
                            start, end, (System.nanoTime() - started) / 1_000_000, data.length);
                    return new Chunk(start, data);
                }
            }
            catch (WindowException e)
            {
                throw e;
            }
            catch (IOException | RuntimeException e)
            {
                // Includes timeouts and an aborted request.
                throw new WindowException("The stream window " + start + ".." + end + " failed: " + e, 0, e);
            }
            finally
            {
                inFlight = null;
            }
        }
    }

    /**
     * Aborts the request that is on the wire, if any, so its thread lets go of the connection.
     */
    private void abortRequest()
    {
        HttpGet request = inFlight;
        if (request != null)
        {
            request.abort();
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
            abortRequest();
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
        Chunk chunk;
        try
        {
            chunk = fetchWindow(0);
        }
        catch (WindowException e)
        {
            // The caller decides from the status whether the stream url has expired.
            LOGGER.debug("Stream check failed: {}", e.getMessage());
            return e.status;
        }
        install(chunk);
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
