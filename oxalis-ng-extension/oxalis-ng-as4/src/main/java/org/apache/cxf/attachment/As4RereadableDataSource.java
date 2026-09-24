package org.apache.cxf.attachment;

import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.message.Message;

import jakarta.activation.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wraps a streamed attachment so its input stream supports mark/reset without buffering in heap. The stream is
 * cached as it is read, using the CXF attachment cache settings (directory, memory threshold, max size).
 * <p>
 * Like {@link AttachmentDataSource}, every call to {@link #getInputStream()} returns the same stream.
 */
public class As4RereadableDataSource implements DataSource {

    private final DataSource source;
    private final Message message;

    private InputStream inputStream;
    private As4MarkableCachedInputStream cachedInputStream;

    public As4RereadableDataSource(DataSource source, Message message) {
        this.source = source;
        this.message = message;
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
        if (inputStream == null) {
            InputStream in = source.getInputStream();
            if (in.markSupported()) {
                inputStream = in;
            } else {
                CachedOutputStream cache = new CachedOutputStream();
                AttachmentUtil.setStreamedAttachmentProperties(message, cache);
                cachedInputStream = new As4MarkableCachedInputStream(in, cache);
                inputStream = cachedInputStream;
            }
        }
        return inputStream;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return source.getOutputStream();
    }

    @Override
    public String getContentType() {
        return source.getContentType();
    }

    @Override
    public String getName() {
        return source.getName();
    }

    /**
     * Deletes the cached copy. Needed because WSS4J hands the stream on wrapped in a stream that ignores close().
     */
    public synchronized void close() throws IOException {
        if (cachedInputStream != null) {
            cachedInputStream.close();
        }
    }
}
