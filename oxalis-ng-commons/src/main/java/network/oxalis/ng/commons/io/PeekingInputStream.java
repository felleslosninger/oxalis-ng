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

package network.oxalis.ng.commons.io;

import com.google.common.io.ByteStreams;

import java.io.*;

/**
 * Caching InputStream to be used when reading the beginning of a stream is needed before the stream is "reset" when
 * the exact amount of data is unknown and support for marking of is irrelevant.
 *
 * @author erlend
 * @since 4.0.0
 * @deprecated Reads the whole stream into a byte array, so a large payload is held in heap (and one above 2 GiB
 * cannot be read at all). No longer used by Oxalis: TransmissionRequestFactory caches the payload with CXF's
 * CachedOutputStream instead, which spills to a temp file.
 */
@Deprecated
public class PeekingInputStream extends InputStream {

    private final byte[] content;

    private final InputStream internlaInputStream;

    public PeekingInputStream(InputStream sourceInputStream) throws IOException {
        this.content = ByteStreams.toByteArray(sourceInputStream);
        this.internlaInputStream = new ByteArrayInputStream(content);

    }

    @Override
    public int read() throws IOException {
        // Return byte
        return this.internlaInputStream.read();
    }

    public byte[] getContent() {
        return content;
    }

    public InputStream newInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }
}
