package network.oxalis.ng.outbound.transformer;

import network.oxalis.vefa.peppol.common.model.C1CountryIdentifier;
import network.oxalis.vefa.peppol.common.model.Header;
import network.oxalis.vefa.peppol.sbdh.SbdReader;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class XmlContentWrapperTest {

    @Test
    public void largeContentIsWrapped() throws Exception {
        Header header;
        try (InputStream inputStream = getClass().getResourceAsStream("/simple-sbd.xml");
             SbdReader sbdReader = SbdReader.newInstance(inputStream)) {
            // simple-sbd.xml has no C1 country, which SbdWriter requires
            header = sbdReader.getHeader().c1CountryIdentifier(C1CountryIdentifier.of("NO"));
        }
        // Above the CXF cache threshold (128 KiB), so the wrapped content is cached in a temp file instead of heap
        String largeText = "A".repeat(1024 * 1024);
        byte[] content = ("<Document xmlns=\"urn:oxalis:test\">" + largeText + "</Document>")
                .getBytes(StandardCharsets.UTF_8);

        byte[] wrapped;
        try (InputStream inputStream = new XmlContentWrapper().wrap(new ByteArrayInputStream(content), header)) {
            wrapped = inputStream.readAllBytes();
        }

        try (SbdReader sbdReader = SbdReader.newInstance(new ByteArrayInputStream(wrapped))) {
            Assert.assertEquals(sbdReader.getHeader().getReceiver(), header.getReceiver());
        }
        Assert.assertTrue(new String(wrapped, StandardCharsets.UTF_8).contains(largeText),
                "the content is carried over into the wrapped payload");
    }
}
