/*
 * Copyright 2010-2018 Norwegian Agency for Public Management and eGovernment (Difi)
 *
 * Licensed under the EUPL, Version 1.1 or – as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 *
 * You may not use this work except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/community/eupl/og_page/eupl
 *
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

package network.oxalis.ng.outbound.transmission;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import network.oxalis.ng.test.identifier.*;
import network.oxalis.ng.api.lang.OxalisException;
import network.oxalis.ng.api.model.TransmissionIdentifier;
import network.oxalis.ng.api.outbound.TransmissionRequest;
import network.oxalis.ng.commons.guice.GuiceModuleLoader;
import network.oxalis.ng.sniffer.PeppolStandardBusinessHeader;
import network.oxalis.ng.sniffer.identifier.ParticipantId;
import network.oxalis.ng.test.lookup.MockLookupModule;
import network.oxalis.vefa.peppol.common.model.Endpoint;
import network.oxalis.vefa.peppol.common.model.Header;
import network.oxalis.vefa.peppol.common.model.ParticipantIdentifier;
import network.oxalis.vefa.peppol.common.model.TransportProfile;
import network.oxalis.ng.sniffer.sbdh.SbdhWrapper;
import network.oxalis.vefa.peppol.sbdh.SbdReader;
import org.apache.cxf.io.CachedOutputStream;
import org.testng.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Map;

import static org.testng.Assert.*;

/**
 * These tests needs TransmissionRequestBuilder to run in TEST-mode to be able to override values
 *
 * @author steinar
 * @author thore
 */
@Guice(modules = GuiceModuleLoader.class)
public class TransmissionRequestBuilderTest {

    @Inject
    @Named("test-files-with-identification")
    public Map<String, PeppolStandardBusinessHeader> testFilesForIdentification;

    @Inject
    @Named("test-non-ubl-documents")
    public Map<String, PeppolStandardBusinessHeader> testNonUBLFiles;

    @Inject
    @Named("sample-xml-with-sbdh")
    InputStream inputStreamWithSBDH;

    @Inject
    @Named("sample-xml-no-sbdh")
    InputStream noSbdhInputStream;

    @Inject
    @Named("sample-xml-missing-metadata")
    InputStream missingMetadataInputStream;

    @Inject
    TransmissionRequestBuilder transmissionRequestBuilder;

    @Inject
    private X509Certificate certificate;

    @BeforeMethod
    public void setUp() {
        MockLookupModule.resetService();

        // Request overriding
        transmissionRequestBuilder.setTransmissionBuilderOverride(true);

        inputStreamWithSBDH.mark(Integer.MAX_VALUE);
        noSbdhInputStream.mark(Integer.MAX_VALUE);
    }

    @AfterMethod
    public void tearDown() throws IOException {
        inputStreamWithSBDH.reset();
        noSbdhInputStream.reset();
    }

    @Test
    public void largePayloadWithSbdhIsPassedOnUnchanged() throws Exception {
        // Above the CXF cache threshold (128 KiB), so the payload is cached in a temp file instead of heap
        byte[] payload = withLargeElement(inputStreamWithSBDH.readAllBytes());

        TransmissionRequest transmissionRequest = transmissionRequestBuilder
                .payLoad(new ByteArrayInputStream(payload))
                .build();

        try (InputStream requestPayload = transmissionRequest.getPayload()) {
            assertEquals(requestPayload.readAllBytes(), payload);
        }
        assertEquals(transmissionRequest.getHeader().getReceiver(), WellKnownParticipant.RANDOM_TEST);
    }

    @Test
    public void largePayloadIsWrappedFromCacheToCache() throws Exception {
        // The builder only wraps a payload without SBDH when a content detector deduces the header (the test
        // configuration has none, see the ignored tests below), so exercise the streaming wrap it uses directly
        Header header;
        try (SbdReader sbdReader = SbdReader.newInstance(inputStreamWithSBDH)) {
            header = sbdReader.getHeader();
        }
        byte[] payload = withLargeElement(noSbdhInputStream.readAllBytes());

        CachedOutputStream wrapped = new CachedOutputStream();
        try {
            new SbdhWrapper().wrap(new ByteArrayInputStream(payload), header, wrapped);
            wrapped.lockOutputStream();
            assertNotNull(wrapped.getTempFile(), "the wrapped payload is cached in a temp file");

            byte[] document;
            try (InputStream inputStream = wrapped.getInputStream()) {
                document = inputStream.readAllBytes();
            }
            try (SbdReader sbdReader = SbdReader.newInstance(new ByteArrayInputStream(document))) {
                assertEquals(sbdReader.getHeader().getReceiver(), header.getReceiver());
            }
            assertTrue(new String(document, StandardCharsets.UTF_8).contains(LARGE_ELEMENT_TEXT),
                    "the large element is carried over into the wrapped payload");
        } finally {
            wrapped.close();
        }
    }

    private static final String LARGE_ELEMENT_TEXT = "A".repeat(1024 * 1024);

    /**
     * Adds a 1 MiB element just before the closing tag of the root element
     */
    private static byte[] withLargeElement(byte[] document) {
        String xml = new String(document, StandardCharsets.UTF_8);
        int rootEnd = xml.lastIndexOf("</");
        return (xml.substring(0, rootEnd)
                + "<LargeTestData xmlns=\"urn:oxalis:test\">" + LARGE_ELEMENT_TEXT + "</LargeTestData>"
                + xml.substring(rootEnd)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void makeSureWeAllowOverrides() {
        assertNotNull(transmissionRequestBuilder);
        assertTrue(transmissionRequestBuilder.isOverrideAllowed(),
                "Overriding transmission request parameters is not permitted!");
    }

    @Test
    public void createTransmissionRequestBuilderWithOnlyTheMessageDocument() throws Exception {

        assertNotNull(transmissionRequestBuilder);
        assertNotNull(inputStreamWithSBDH);

        transmissionRequestBuilder.payLoad(inputStreamWithSBDH);

        // Builds the actual transmission request
        TransmissionRequest transmissionRequest = transmissionRequestBuilder.build();

        PeppolStandardBusinessHeader sbdh = transmissionRequestBuilder.getEffectiveStandardBusinessHeader();
        assertNotNull(sbdh);
        assertEquals(sbdh.getRecipientId(), WellKnownParticipant.RANDOM_TEST);

        assertNotNull(transmissionRequest.getEndpoint());

        assertNotNull(transmissionRequest.getHeader());

        assertEquals(transmissionRequest.getHeader().getReceiver(), WellKnownParticipant.RANDOM_TEST);

        assertEquals(transmissionRequest.getEndpoint().getTransportProfile(),
                TransportProfile.of("peppol-transport-as4-v2_0"));

        assertNotNull(transmissionRequest.getHeader().getIdentifier());
    }

    @Test
    @Ignore
    public void xmlWithNoSBDH() throws Exception {

        TransmissionRequestBuilder builder = transmissionRequestBuilder.payLoad(noSbdhInputStream)
                .receiver(WellKnownParticipant.DIFI_TEST);
        TransmissionRequest request = builder.build();

        assertNotNull(builder);
        assertNotNull(builder.getEffectiveStandardBusinessHeader(), "Effective SBDH is null");

        assertEquals(builder.getEffectiveStandardBusinessHeader().getRecipientId(),
                WellKnownParticipant.DIFI_TEST, "Receiver has not been overridden");
        assertEquals(request.getHeader().getReceiver(), WellKnownParticipant.DIFI_TEST);

    }


    @Test
    @Ignore
    public void overrideFields() throws Exception {

        TransmissionRequestBuilder builder = transmissionRequestBuilder.payLoad(noSbdhInputStream)
                .sender(WellKnownParticipant.DIFI_TEST)
                .receiver(WellKnownParticipant.U4_TEST)
                .documentType(PeppolDocumentTypeIdAcronym.ORDER.toVefa());

        TransmissionRequest request = builder.build();

        assertEquals(request.getEndpoint().getTransportProfile(), TransportProfile.of("busdox-transport-as2-ver1p0"));
        assertEquals(request.getHeader().getReceiver(), WellKnownParticipant.U4_TEST);
        assertEquals(request.getHeader().getSender(), WellKnownParticipant.DIFI_TEST);
        assertEquals(request.getHeader().getDocumentType(),
                PeppolDocumentTypeIdAcronym.ORDER.toVefa());
    }

    @Test
    public void testOverrideEndPoint() throws Exception {
        assertNotNull(inputStreamWithSBDH);
        URI url = URI.create("http://localhost:8080/oxalis/as4");
        TransmissionRequest request = transmissionRequestBuilder
                .payLoad(inputStreamWithSBDH)
                .overrideAs4Endpoint(Endpoint.of(TransportProfile.PEPPOL_AS4_2_0, url, certificate))
                .build();
        assertEquals(request.getEndpoint().getTransportProfile(), TransportProfile.PEPPOL_AS4_2_0);
        assertEquals(request.getEndpoint().getAddress(), url);
    }

    @Test
    public void testOverrideOfAllValues() throws Exception {
        TransmissionIdentifier transmissionIdentifier = TransmissionIdentifier.of("messageid");
        TransmissionRequest request = transmissionRequestBuilder
                    .payLoad(inputStreamWithSBDH)
                .sender(WellKnownParticipant.DIFI_TEST)
                .receiver(WellKnownParticipant.U4_TEST)
                .documentType(PeppolDocumentTypeIdAcronym.ORDER.toVefa())
                .processType(PeppolProcessTypeIdAcronym.ORDER_ONLY.toVefa())
                .c1CountryIdentifier(CountryIdentifierExample.BE)
                .mlsToIdentifier(MlsToIdentifierExample.TEST)
                .mlsTypeIdentifier(MlsTypeIdentifierExample.TEST)
                .build();

        Header header = request.getHeader();
        assertEquals(header.getSender(), WellKnownParticipant.DIFI_TEST);
        assertEquals(header.getReceiver(), WellKnownParticipant.U4_TEST);
        assertEquals(header.getDocumentType(), PeppolDocumentTypeIdAcronym.ORDER.toVefa());
        assertEquals(header.getProcess(), PeppolProcessTypeIdAcronym.ORDER_ONLY.toVefa());
        assertEquals(header.getC1CountryIdentifier(), CountryIdentifierExample.BE);
        assertEquals(header.getMlsToIdentifier(), MlsToIdentifierExample.TEST);
        assertEquals(header.getMlsTypeIdentifier(), MlsTypeIdentifierExample.TEST);
        assertNotEquals(header.getIdentifier().getIdentifier(), transmissionIdentifier.getIdentifier(),
                "The SBDH instanceId should not be equal to the AS4 transmission identifier");
    }

    /**
     * If a messageId is not provided a default one is created before sending.
     */
    @Test
    @Ignore
    public void testMessageIdSuppliedByBuilder() throws OxalisException {
        TransmissionRequest request = transmissionRequestBuilder
                .payLoad(inputStreamWithSBDH)
                .sender(WellKnownParticipant.DIFI_TEST)
                .receiver(WellKnownParticipant.U4_TEST)
                .documentType(PeppolDocumentTypeIdAcronym.ORDER.toVefa())
                .processType(PeppolProcessTypeIdAcronym.ORDER_ONLY.toVefa())
                .build();

        assertNotNull(request.getHeader().getIdentifier());

        transmissionRequestBuilder.reset();
        TransmissionRequest request2 = transmissionRequestBuilder.payLoad(noSbdhInputStream)
                .sender(WellKnownParticipant.DIFI_TEST)
                .receiver(WellKnownParticipant.U4_TEST)
                .documentType(PeppolDocumentTypeIdAcronym.ORDER.toVefa())
                .processType(PeppolProcessTypeIdAcronym.ORDER_ONLY.toVefa())
                .build();

        assertNotNull(request2.getHeader().getIdentifier());
    }

    @Test
    @Ignore
    public void makeSureWeDetectMissingProperties() {
        try {
            transmissionRequestBuilder
                    .payLoad(missingMetadataInputStream)
                    .build();
            fail("The build() should have failed indicating missing properties");
        } catch (Exception ex) {
            assertEquals(ex.getMessage(),
                    "TransmissionRequest can not be built, missing [recipientId, senderId] metadata.");
        }
    }

    @Test
    @Ignore
    public void testIssue250() throws Exception {
        InputStream resourceAsStream = getClass().getResourceAsStream("/Issue250-sample-invoice.xml");
        assertNotNull(resourceAsStream);

        transmissionRequestBuilder.reset();
        TransmissionRequest transmissionRequest = transmissionRequestBuilder.payLoad(resourceAsStream).build();

        ParticipantIdentifier recipientId = transmissionRequest.getHeader().getReceiver();
        assertEquals(recipientId, new ParticipantId("9954:111111111").toVefa());
        assertNotNull(transmissionRequest.getHeader().getIdentifier());
    }
}
