package org.apache.cxf.attachment;

import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.wss4j.common.ext.AttachmentResultCallback;
import org.apache.wss4j.dom.transform.AttachmentContentSignatureTransform;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import jakarta.activation.DataHandler;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dom.DOMCryptoContext;
import javax.xml.crypto.dsig.TransformException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class As4RereadableAttachmentTest {

    private static final int THRESHOLD = 1024;

    private Path cacheDir;
    private byte[] content;

    @BeforeMethod
    public void setUp() throws IOException {
        cacheDir = Files.createTempDirectory("as4-attachment-cache");
        content = new byte[1024 * 1024];
        new Random(42).nextBytes(content);
    }

    @AfterMethod
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(cacheDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void decryptedAttachmentIsCachedInTempFileAndRereadable() throws IOException {
        As4AttachmentDeserializer deserializer = new As4AttachmentDeserializer(message());
        Attachment attachment = new AttachmentImpl("payload", new DataHandler(
                new AttachmentDataSource("application/octet-stream", new NoMarkInputStream(content))));

        deserializer.makeRereadable(attachment);

        Assert.assertTrue(attachment.getDataHandler().getDataSource() instanceof As4RereadableDataSource);
        InputStream in = attachment.getDataHandler().getInputStream();
        Assert.assertTrue(in.markSupported());

        in.mark(Integer.MAX_VALUE);
        Assert.assertEquals(in.readAllBytes(), content);
        Assert.assertEquals(cachedFileCount(), 1, "content above the threshold is cached in a temp file");

        in.reset();
        Assert.assertEquals(in.readAllBytes(), content);

        deserializer.closeRereadable();
        Assert.assertEquals(cachedFileCount(), 0, "temp file is deleted on cleanup");
    }

    @Test
    public void attachmentsFromTheMimeStreamAreLeftAlone() throws IOException {
        As4AttachmentDeserializer deserializer = new As4AttachmentDeserializer(message());
        As4AttachmentDataSource mimeSource =
                new As4AttachmentDataSource("application/octet-stream", new ByteArrayInputStream(content));
        Attachment attachment = new AttachmentImpl("payload", new DataHandler(mimeSource));

        deserializer.makeRereadable(attachment);

        Assert.assertSame(attachment.getDataHandler().getDataSource(), mimeSource);
    }

    @Test
    public void wss4jUsesMarkAndResetInsteadOfBufferingInHeap() throws Exception {
        AtomicReference<Integer> markedWith = new AtomicReference<>();
        As4MarkableCachedInputStream in = new As4MarkableCachedInputStream(new NoMarkInputStream(content), cache()) {
            @Override
            public synchronized void mark(int readLimit) {
                markedWith.set(readLimit);
                super.mark(readLimit);
            }
        };

        AtomicReference<org.apache.wss4j.common.ext.Attachment> result = new AtomicReference<>();
        DOMCryptoContext context = new DOMCryptoContext() {
        };
        context.setProperty(AttachmentContentSignatureTransform.ATTACHMENT_CALLBACKHANDLER,
                (javax.security.auth.callback.CallbackHandler) callbacks ->
                        result.set(((AttachmentResultCallback) callbacks[0]).getAttachment()));

        org.apache.wss4j.common.ext.Attachment attachment = new org.apache.wss4j.common.ext.Attachment();
        attachment.setId("payload");
        attachment.setMimeType("application/octet-stream");
        attachment.setSourceStream(in);

        ByteArrayOutputStream digestInput = new ByteArrayOutputStream();
        new ExposedAttachmentContentSignatureTransform().process(context, digestInput, attachment);

        Assert.assertEquals(markedWith.get(), Integer.MAX_VALUE,
                "WSS4J marks our stream directly instead of wrapping it in a BufferedInputStream");
        Assert.assertEquals(digestInput.toByteArray(), content);
        Assert.assertEquals(result.get().getSourceStream().readAllBytes(), content,
                "the verified attachment is read again from the cache");
    }

    @Test
    public void resetCanBeRepeatedAndMarkedMidStream() throws IOException {
        try (As4MarkableCachedInputStream in =
                     new As4MarkableCachedInputStream(new NoMarkInputStream(content), cache())) {
            Assert.assertEquals(in.readNBytes(100).length, 100);
            in.mark(0);
            byte[] rest = in.readAllBytes();

            in.reset();
            Assert.assertEquals(in.readAllBytes(), rest);
            in.reset();
            Assert.assertEquals(in.readAllBytes(), rest);
        }
        Assert.assertEquals(cachedFileCount(), 0);
    }

    @Test
    public void resetCachesWhatWasNotRead() throws IOException {
        try (As4MarkableCachedInputStream in =
                     new As4MarkableCachedInputStream(new NoMarkInputStream(content), cache())) {
            in.mark(0);
            in.readNBytes(10);
            in.reset();
            Assert.assertEquals(in.readAllBytes(), content);
        }
    }

    @Test
    public void resetWorksWithEncryptedTempFile() throws IOException {
        CachedOutputStream cache = cache();
        // As with -Dorg.apache.cxf.io.CachedOutputStream.CipherTransformation; CTR streams, GCM would buffer on decrypt
        cache.setCipherTransformation("AES/CTR/NoPadding");
        try (As4MarkableCachedInputStream in = new As4MarkableCachedInputStream(new NoMarkInputStream(content), cache)) {
            in.mark(0);
            Assert.assertEquals(in.readAllBytes(), content);

            byte[] onDisk;
            try (Stream<Path> files = Files.list(cacheDir)) {
                onDisk = Files.readAllBytes(files.findFirst().orElseThrow());
            }
            Assert.assertEquals(onDisk.length, content.length);
            Assert.assertNotEquals(onDisk, content, "the temp file is encrypted");

            in.reset();
            Assert.assertEquals(in.readAllBytes(), content);
            in.reset();
            Assert.assertEquals(in.readAllBytes(), content);
        }
        Assert.assertEquals(cachedFileCount(), 0);
    }

    @Test
    public void closeDeletesTempFileWithoutReset() throws IOException {
        As4MarkableCachedInputStream in = new As4MarkableCachedInputStream(new NoMarkInputStream(content), cache());
        in.readAllBytes();
        Assert.assertEquals(cachedFileCount(), 1);

        in.close();
        Assert.assertEquals(cachedFileCount(), 0);
    }

    @Test(expectedExceptions = IOException.class)
    public void exceedingMaxSizeIsReportedAsIOException() throws IOException {
        CachedOutputStream cache = cache();
        cache.setMaxSize(THRESHOLD * 2L);
        try (As4MarkableCachedInputStream in = new As4MarkableCachedInputStream(new NoMarkInputStream(content), cache)) {
            in.readAllBytes();
        }
    }

    private Message message() {
        Message message = new MessageImpl();
        message.put(AttachmentDeserializer.ATTACHMENT_DIRECTORY, cacheDir.toFile());
        message.put(AttachmentDeserializer.ATTACHMENT_MEMORY_THRESHOLD, (long) THRESHOLD);
        return message;
    }

    private CachedOutputStream cache() throws IOException {
        CachedOutputStream cache = new CachedOutputStream();
        cache.setThreshold(THRESHOLD);
        cache.setOutputDir(cacheDir.toFile());
        return cache;
    }

    private long cachedFileCount() throws IOException {
        try (Stream<Path> files = Files.list(cacheDir)) {
            return files.count();
        }
    }

    /**
     * Like the stream WSS4J gets from AES-GCM decryption: no mark support.
     */
    private static class NoMarkInputStream extends FilterInputStream {

        NoMarkInputStream(byte[] content) {
            super(new ByteArrayInputStream(content));
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    private static class ExposedAttachmentContentSignatureTransform extends AttachmentContentSignatureTransform {

        void process(XMLCryptoContext context, ByteArrayOutputStream os,
                     org.apache.wss4j.common.ext.Attachment attachment) throws TransformException {
            processAttachment(context, os, "cid:" + attachment.getId(), attachment);
        }
    }
}
