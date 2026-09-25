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

import network.oxalis.ng.api.outbound.TransmissionMessage;
import network.oxalis.ng.commons.guice.GuiceModuleLoader;
import network.oxalis.ng.test.lookup.MockLookupModule;
import org.testng.Assert;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Guice(modules = GuiceModuleLoader.class)
public class TransmissionRequestFactoryTest {

    @Inject
    private TransmissionRequestFactory transmissionRequestFactory;

    @Test
    public void simple() throws Exception {
        MockLookupModule.resetService();

        TransmissionMessage transmissionMessage;
        try (InputStream inputStream = getClass().getResourceAsStream("/simple-sbd.xml")) {
            transmissionMessage = transmissionRequestFactory.newInstance(inputStream);
        }

        Assert.assertNotNull(transmissionMessage.getHeader());
    }

    @Test
    public void largePayloadWithSbdhIsPassedOnUnchanged() throws Exception {
        MockLookupModule.resetService();

        // Above the CXF cache threshold (128 KiB), so the payload is cached in a temp file instead of heap,
        // and read twice: once for the SBDH, once as the message payload
        byte[] payload;
        try (InputStream inputStream = getClass().getResourceAsStream("/simple-sbd.xml")) {
            String xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            int rootEnd = xml.lastIndexOf("</");
            payload = (xml.substring(0, rootEnd)
                    + "<LargeTestData xmlns=\"urn:oxalis:test\">" + "A".repeat(1024 * 1024) + "</LargeTestData>"
                    + xml.substring(rootEnd)).getBytes(StandardCharsets.UTF_8);
        }

        TransmissionMessage transmissionMessage = transmissionRequestFactory.newInstance(new ByteArrayInputStream(payload));

        Assert.assertNotNull(transmissionMessage.getHeader());
        try (InputStream messagePayload = transmissionMessage.getPayload()) {
            Assert.assertEquals(messagePayload.readAllBytes(), payload);
        }
    }
}
