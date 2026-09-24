package org.apache.cxf.attachment;

import org.apache.cxf.io.CacheSizeExceededException;
import org.apache.cxf.io.CachedOutputStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads through to a source stream while copying every byte to a {@link CachedOutputStream}, so the stream can be
 * reset and read again from the cache (memory below the threshold, a temp file above) instead of from heap.
 * <p>
 * WSS4J marks attachment streams with {@code Integer.MAX_VALUE} while computing the attachment digest, and wraps
 * streams without mark support in a {@link java.io.BufferedInputStream}, which holds the whole attachment in heap.
 */
public class As4MarkableCachedInputStream extends InputStream {

    private final InputStream source;
    private final CachedOutputStream cache;

    /**
     * Stream from the cache after the first reset, {@code null} while reading from the source.
     */
    private InputStream replay;

    private long position;
    private long markPosition;
    private boolean closed;

    public As4MarkableCachedInputStream(InputStream source, CachedOutputStream cache) {
        this.source = source;
        this.cache = cache;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        int b;
        if (replay == null) {
            b = source.read();
            if (b >= 0) {
                writeToCache(new byte[]{(byte) b}, 0, 1);
            }
        } else {
            b = replay.read();
        }
        if (b >= 0) {
            position++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        int n;
        if (replay == null) {
            n = source.read(b, off, len);
            if (n > 0) {
                writeToCache(b, off, n);
            }
        } else {
            n = replay.read(b, off, len);
        }
        if (n > 0) {
            position += n;
        }
        return n;
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        return replay == null ? source.available() : replay.available();
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    /**
     * The read limit is ignored: everything read is cached, so the stream can always be reset to the mark.
     */
    @Override
    public synchronized void mark(int readLimit) {
        markPosition = position;
    }

    @Override
    public synchronized void reset() throws IOException {
        ensureOpen();
        if (replay == null) {
            // Cache whatever the reader did not consume, so the cache holds the complete source
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) != -1) {
                writeToCache(buffer, 0, n);
            }
            source.close();
            cache.lockOutputStream();
        }
        // Open the next stream before closing the current one: CachedOutputStream deletes the temp file
        // when its last input stream is closed
        InputStream next = cache.getInputStream();
        skipFully(next, markPosition);
        if (replay != null) {
            replay.close();
        }
        replay = next;
        position = markPosition;
    }

    /**
     * Closes the source and deletes the cached copy.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            source.close();
        } finally {
            try {
                if (replay != null) {
                    replay.close();
                }
            } finally {
                cache.close();
            }
        }
    }

    private void writeToCache(byte[] b, int off, int len) throws IOException {
        try {
            cache.write(b, off, len);
        } catch (CacheSizeExceededException e) {
            // Surface as IOException, so WSS4J reports it as a security error like any other read failure
            throw new IOException("Attachment exceeds the maximum cached size", e);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new EOFException("Cache ended before the mark position");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
