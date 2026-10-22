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

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.io.IOException;
import java.net.URI;

/**
 * Replacement for {@code dev.lavalink.youtube.track.YoutubePersistentHttpStream} that requests
 * byte ranges using a bounded HTTP {@code Range} header.
 *
 * <p><b>Why this exists:</b> youtube-source's original implementation asked YouTube for a byte
 * range with the legacy {@code range=} <em>query parameter</em> and deliberately disabled the
 * HTTP {@code Range} header ({@code useHeadersForRange()} returned {@code false}). YouTube no
 * longer accepts that: a request without a {@code Range} header is answered with
 * {@code HTTP 403}, and the {@code range=} parameter on its own is not enough either.
 *
 * <p>Measured against YouTube's own responses:
 * <pre>
 *   Range: bytes=0-2047   (bounded)          -&gt; HTTP 206
 *   Range: bytes=0-       (open ended)        -&gt; HTTP 403
 *   no Range header, range= query param only  -&gt; HTTP 403
 *   window larger than 512 KiB                -&gt; HTTP 403
 * </pre>
 *
 * <p>The stream therefore asks for a bounded window of at most {@value #WINDOW_SIZE} bytes at a
 * time and transparently opens the next window once the current one is exhausted.
 *
 * <p>The original class is excluded from the shaded jar (see the {@code maven-shade-plugin}
 * filter in {@code pom.xml}) so that exactly one implementation is present at runtime.
 *
 * @author Michaili K. &lt;git@michaili.dev&gt;
 * @author John Grosh (jagrosh)
 * @author Arif Banai (arif-banai)
 */
public class YoutubePersistentHttpStream extends PersistentHttpStream
{
    /**
     * Largest range window YouTube accepts. Anything bigger is answered with {@code HTTP 403}.
     */
    private static final long WINDOW_SIZE = 512 * 1024L;

    /**
     * Total size of the content, if it was known up front. {@code -1} when unknown.
     */
    private final long declaredLength;

    /**
     * Total size of the content, learned from the {@code Content-Range} response header.
     * {@code -1} when still unknown.
     */
    private long totalLength = -1;

    /**
     * Last byte of the window currently being read, or {@code -1} when not connected.
     */
    private long rangeEnd = -1;

    /**
     * Position at which the last end-of-stream was hit, used to avoid reconnecting forever
     * when the total length of the content is unknown.
     */
    private long lastEofPosition = -1;

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
    }

    /**
     * Use the HTTP {@code Range} header rather than the legacy {@code range=} query parameter.
     */
    @Override
    protected boolean useHeadersForRange()
    {
        return true;
    }

    /**
     * The original implementation appended a {@code range=} query parameter to the URL here.
     * That parameter is no longer honoured on its own, so the URL is used unchanged and the
     * range is expressed through the header instead.
     */
    @Override
    protected URI getConnectUrl()
    {
        return contentUrl;
    }

    /**
     * Builds the request used to open the stream.
     *
     * <p>{@link PersistentHttpStream} only sets the {@code Range} header once {@code position > 0},
     * so the very first request of a track went out without it and was rejected with
     * {@code HTTP 403}. The header is therefore always set here, and always bounded, because
     * YouTube also rejects open-ended and oversized ranges.
     */
    @Override
    protected HttpGet getConnectRequest()
    {
        long end = position + WINDOW_SIZE - 1;

        long total = getTotalLength();
        if (total > 0 && end > total - 1)
        {
            end = total - 1;
        }
        rangeEnd = end;

        HttpGet request = new HttpGet(getConnectUrl());
        if (!request.containsHeader(HttpHeaders.RANGE))
        {
            request.setHeader(HttpHeaders.RANGE, "bytes=" + position + "-" + rangeEnd);
        }
        return request;
    }

    @Override
    protected int internalRead(byte[] b, int off, int len, boolean attemptReconnect) throws IOException
    {
        connect(false);
        learnTotalLength();

        // The current window is used up: open the next one before reading. This has to happen
        // before the read, otherwise the bytes of the old window would be overwritten in the
        // caller's buffer.
        if (position > rangeEnd && hasMoreContent())
        {
            if (!openNextWindow())
            {
                return -1;
            }
        }

        try
        {
            int result = currentContent.read(b, off, len);

            if (result >= 0)
            {
                position += result;
                lastEofPosition = -1;
                return result;
            }

            // The server closed the connection before the content was complete. Reconnect and
            // carry on from the current position.
            if (hasMoreContent() && position != lastEofPosition)
            {
                lastEofPosition = position;
                close();
                try
                {
                    connect(false);
                    learnTotalLength();

                    int next = currentContent.read(b, off, len);
                    if (next >= 0)
                    {
                        position += next;
                        lastEofPosition = -1;
                    }
                    return next;
                }
                catch (RuntimeException e)
                {
                    // Nothing more to read.
                    return -1;
                }
            }

            return result;
        }
        catch (IOException e)
        {
            if (!attemptReconnect)
            {
                throw e;
            }
            close();
            return internalRead(b, off, len, false);
        }
    }

    /**
     * Skips over the given number of bytes.
     *
     * <p>The bytes are read and discarded rather than skipped: HTTP response streams do not
     * support {@code skip()}, so the inherited implementation returned 0 and lavaplayer's
     * {@code skipFully} failed with {@code "Cannot skip any further"}. Reading also lets the
     * skip continue across window boundaries.
     */
    @Override
    protected long internalSkip(long n, boolean attemptReconnect) throws IOException
    {
        connect(false);
        learnTotalLength();

        long remaining = n;
        long skipped = 0;
        byte[] discard = new byte[8192];

        while (remaining > 0)
        {
            long available = rangeEnd - position + 1;

            if (available <= 0)
            {
                // The current window is used up, open the next one.
                if (!hasMoreContent() || !openNextWindow())
                {
                    break;
                }
                continue;
            }

            int chunk = (int) Math.min(remaining, Math.min(available, discard.length));
            int read = currentContent.read(discard, 0, chunk);

            if (read > 0)
            {
                position += read;
                skipped += read;
                remaining -= read;
                continue;
            }

            // End of the window before the requested amount was reached.
            if (!hasMoreContent() || !openNextWindow())
            {
                break;
            }
        }

        return skipped;
    }

    /**
     * This stream can always be re-opened at an arbitrary position, because every request
     * carries its own {@code Range} header.
     */
    @Override
    public boolean canSeekHard()
    {
        return true;
    }

    /**
     * Opens the window that follows the current one.
     *
     * @return true if the new window was opened successfully
     */
    private boolean openNextWindow()
    {
        try
        {
            close();
            connect(false);
            learnTotalLength();
            return true;
        }
        catch (IOException | RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Reads the total size of the content from the {@code Content-Range} response header.
     */
    private void learnTotalLength()
    {
        HttpResponse response = getCurrentResponse();
        if (response == null)
        {
            return;
        }

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
                // Leave the total unknown; the stream simply relies on the server closing the
                // connection when the content ends.
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
        long total = getTotalLength();
        return total <= 0 || position < total;
    }
}
