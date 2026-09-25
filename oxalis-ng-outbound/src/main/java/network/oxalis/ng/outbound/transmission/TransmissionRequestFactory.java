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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import network.oxalis.ng.api.header.HeaderParser;
import network.oxalis.ng.api.lang.OxalisContentException;
import network.oxalis.ng.api.model.Direction;
import network.oxalis.ng.api.outbound.TransmissionMessage;
import network.oxalis.ng.api.tag.Tag;
import network.oxalis.ng.api.tag.TagGenerator;
import network.oxalis.ng.api.transformer.ContentDetector;
import network.oxalis.ng.api.transformer.ContentWrapper;
import network.oxalis.ng.commons.tracing.Traceable;
import network.oxalis.vefa.peppol.common.model.Header;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.io.CachedOutputStream;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author erlend
 * @since 4.0.0
 */
public class TransmissionRequestFactory extends Traceable {

    private final ContentDetector contentDetector;

    private final ContentWrapper contentWrapper;

    private final TagGenerator tagGenerator;

    private final HeaderParser headerParser;

    @Inject
    public TransmissionRequestFactory(ContentDetector contentDetector, ContentWrapper contentWrapper,
                                      TagGenerator tagGenerator, HeaderParser headerParser, Tracer tracer) {
        super(tracer);
        this.contentDetector = contentDetector;
        this.contentWrapper = contentWrapper;
        this.tagGenerator = tagGenerator;
        this.headerParser = headerParser;
    }

    public TransmissionMessage newInstance(InputStream inputStream)
            throws IOException, OxalisContentException {
        return newInstance(inputStream, Tag.NONE);
    }

    public TransmissionMessage newInstance(InputStream inputStream, Tag tag)
            throws IOException, OxalisContentException {
        Span span = tracer.spanBuilder(getClass().getSimpleName()).startSpan();
        try {
            return perform(inputStream, tag);
        } finally {
            span.end();
        }
    }

    private TransmissionMessage perform(InputStream inputStream, Tag tag)
            throws IOException, OxalisContentException {
        // Cached in memory below the CXF threshold and in a temp file above it, so a large payload is not held in
        // heap. The temp file is kept while it is read more than once, and deleted once the message's payload
        // stream is closed.
        CachedOutputStream payload = new CachedOutputStream();
        try {
            IOUtils.copy(inputStream, payload);
            payload.lockOutputStream();
            payload.holdTempFile();

            try {
                Header header = readHeaderFromSbdh(payload);
                return new DefaultTransmissionMessage(header, payload.getInputStream(),
                        tagGenerator.generate(Direction.OUT, tag));
            } catch (OxalisContentException e) {
                Header header = detectHeaderFromContent(payload);
                InputStream wrappedContent = wrapContentInSbdh(header, payload);
                return new DefaultTransmissionMessage(header, wrappedContent, tagGenerator.generate(Direction.OUT, tag));
            }
        } finally {
            payload.releaseTempFileHold();
            payload.close();
        }
    }

    private Header readHeaderFromSbdh(CachedOutputStream payload) throws IOException, OxalisContentException {
        Span span = tracer.spanBuilder("Reading SBDH").startSpan();
        try (InputStream inputStream = payload.getInputStream()) {
            Header header = headerParser.parse(inputStream);
            span.setAttribute("identifier", header.getIdentifier().getIdentifier());
            return header;
        } catch (OxalisContentException e) {
            span.setAttribute("exception", e.getMessage());
            throw e;
        } finally {
            span.end();
        }

    }

    private Header detectHeaderFromContent(CachedOutputStream payload) throws IOException, OxalisContentException {
        Span span = tracer.spanBuilder("Detect SBDH from content").startSpan();
        try (InputStream inputStream = payload.getInputStream()) {
            Header header = contentDetector.parse(inputStream);
            span.setAttribute("identifier", header.getIdentifier().getIdentifier());
            return header;
        } catch (OxalisContentException e) {
            span.setAttribute("exception", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private InputStream wrapContentInSbdh(Header header, CachedOutputStream payload)
            throws IOException, OxalisContentException {
        Span span = tracer.spanBuilder("Wrap content in SBDH").startSpan();
        try (InputStream inputStream = payload.getInputStream()) {
            return contentWrapper.wrap(inputStream, header);
        } catch (OxalisContentException e) {
            span.setAttribute("exception", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
