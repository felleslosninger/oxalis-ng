package network.oxalis.ng.as4.util;

import org.apache.cxf.attachment.As4MarkableCachedInputStream;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.io.CacheSizeExceededException;
import org.apache.cxf.io.CachedOutputStream;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public class CompressionUtil {

    /**
     * Gets Compressed Stream for given input Stream
     * <p>
     * The returned stream supports mark/reset by re-reading the cache (memory below the threshold, a temp file
     * above). WSS4J marks the attachment stream with Integer.MAX_VALUE when signing, and would otherwise wrap it in
     * a BufferedInputStream holding the whole compressed payload in heap. Closing the stream deletes the cache.
     *
     * @param sourceStream : Input Stream to be compressed to
     * @return Compressed Stream
     * @throws IOException when some thing bad happens
     */
    public InputStream getCompressedStream(final InputStream sourceStream) throws IOException {

        if (sourceStream == null) {
            throw new IllegalArgumentException("Source Stream cannot be NULL");
        }

        CachedOutputStream cache = new CachedOutputStream();

        // Closing the GZIPOutputStream must not close the cache: that would delete it before it is read
        OutputStream keepCacheOpen = new FilterOutputStream(cache) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };

        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(keepCacheOpen)) {
            IOUtils.copyAndCloseInput(sourceStream, gzipOutputStream);
            gzipOutputStream.finish();
        } catch (CacheSizeExceededException | IOException cee) {
            sourceStream.close();
            cache.close();
            throw cee;
        }

        try {
            return new As4MarkableCachedInputStream(cache);
        } catch (IOException e) {
            cache.close();
            throw e;
        }
    }
}
