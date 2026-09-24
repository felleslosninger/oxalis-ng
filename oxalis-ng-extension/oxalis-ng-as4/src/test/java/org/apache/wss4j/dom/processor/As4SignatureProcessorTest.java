package org.apache.wss4j.dom.processor;

import org.apache.cxf.attachment.AttachmentDataSource;
import org.apache.cxf.attachment.AttachmentImpl;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.ws.security.wss4j.AttachmentCallbackHandler;
import org.apache.wss4j.common.crypto.Merlin;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSDataRef;
import org.apache.wss4j.dom.engine.WSSConfig;
import org.apache.wss4j.dom.engine.WSSecurityEngine;
import org.apache.wss4j.dom.engine.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.message.WSSecHeader;
import org.apache.wss4j.dom.message.WSSecSignature;
import org.apache.wss4j.dom.transform.AttachmentContentSignatureTransform;
import org.apache.wss4j.dom.validate.NoOpValidator;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import jakarta.activation.DataHandler;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.xml.sax.InputSource;

public class As4SignatureProcessorTest {

    private static final String ALIAS = "as4";
    private static final String PASSWORD = "password";
    private static final String ATTACHMENT_ID = "payload";
    private static final String SOAP_MESSAGE =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soapenv:Header/>"
                    + "<soapenv:Body><value xmlns=\"urn:test\">15</value></soapenv:Body>"
                    + "</soapenv:Envelope>";

    private Merlin crypto;
    private X509Certificate certificate;
    private byte[] content;

    @BeforeClass
    public void setUp() throws Exception {
        WSSConfig.init();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=as4-signature-test");
        long now = System.currentTimeMillis();
        certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, new Date(now - 60_000), new Date(now + 3_600_000), name, keyPair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), PASSWORD.toCharArray(), new Certificate[]{certificate});
        crypto = new Merlin();
        crypto.setKeyStore(keyStore);
        crypto.setTrustStore(keyStore);

        content = new byte[256 * 1024];
        new Random(42).nextBytes(content);
    }

    @Test
    public void verifiesSignatureOverBodyAndAttachment() throws Exception {
        Document doc = signedMessage();

        WSHandlerResult result = verify(doc, content);

        WSSecurityEngineResult signature = result.getActionResults().get(WSConstants.SIGN).get(0);
        @SuppressWarnings("unchecked")
        List<WSDataRef> dataRefs = (List<WSDataRef>) signature.get(WSSecurityEngineResult.TAG_DATA_REF_URIS);
        Assert.assertEquals(dataRefs.size(), 2);
        Assert.assertEquals(dataRefs.stream().filter(WSDataRef::isAttachment).count(), 1,
                "the attachment Reference is still reported as an attachment");
    }

    @Test(expectedExceptions = WSSecurityException.class)
    public void rejectsModifiedAttachment() throws Exception {
        Document doc = signedMessage();
        byte[] modified = content.clone();
        modified[1000] ^= 0x01;

        verify(doc, modified);
    }

    @Test
    public void attachmentDigestInputIsNotCached() throws Exception {
        Document doc = signedMessage();
        DOMValidateContext context = validateContext(doc, content);
        XMLSignature xmlSignature = unmarshal(context);

        Assert.assertTrue(new As4SignatureProcessor().validateSignature(xmlSignature, context));

        for (Object object : xmlSignature.getSignedInfo().getReferences()) {
            Reference reference = (Reference) object;
            if (reference.getURI().startsWith("cid:")) {
                Assert.assertNull(reference.getDigestInputStream(), "attachment digest input must not be kept in heap");
            } else {
                Assert.assertNotNull(reference.getDigestInputStream(), "other References are still cached");
            }
        }
    }

    @Test
    public void upstreamValidateKeepsAttachmentDigestInput() throws Exception {
        // Documents the behaviour As4SignatureProcessor avoids: XMLSignature.validate() with cacheReference=TRUE
        Document doc = signedMessage();
        DOMValidateContext context = validateContext(doc, content);
        XMLSignature xmlSignature = unmarshal(context);

        Assert.assertTrue(xmlSignature.validate(context));

        for (Object object : xmlSignature.getSignedInfo().getReferences()) {
            Reference reference = (Reference) object;
            if (reference.getURI().startsWith("cid:")) {
                Assert.assertNotNull(reference.getDigestInputStream(), "the whole attachment is kept in heap");
            }
        }
    }

    private Document signedMessage() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(SOAP_MESSAGE)));

        WSSecHeader securityHeader = new WSSecHeader(doc);
        securityHeader.insertSecurityHeader();
        WSSecSignature signature = new WSSecSignature(securityHeader);
        signature.setUserInfo(ALIAS, PASSWORD);
        signature.setKeyIdentifierType(WSConstants.BST_DIRECT_REFERENCE);
        signature.setSignatureAlgorithm(WSConstants.RSA_SHA256);
        signature.setDigestAlgo(WSConstants.SHA256);
        signature.getParts().add(new org.apache.wss4j.common.WSEncryptionPart(
                WSConstants.ELEM_BODY, WSConstants.URI_SOAP11_ENV, ""));
        signature.getParts().add(new org.apache.wss4j.common.WSEncryptionPart("cid:Attachments", "Content"));
        signature.setAttachmentCallbackHandler(new AttachmentCallbackHandler(attachments(content)));
        signature.build(crypto);
        return doc;
    }

    private WSHandlerResult verify(Document doc, byte[] attachmentContent) throws WSSecurityException {
        WSSConfig config = WSSConfig.getNewInstance();
        config.setProcessor(WSConstants.SIGNATURE, As4SignatureProcessor.class);
        config.setValidator(WSConstants.SIGNATURE, new NoOpValidator());

        RequestData data = new RequestData();
        data.setWssConfig(config);
        data.setSigVerCrypto(crypto);
        data.setDisableBSPEnforcement(true);
        data.setAttachmentCallbackHandler(new AttachmentCallbackHandler(attachments(attachmentContent)));

        WSSecurityEngine engine = new WSSecurityEngine();
        engine.setWssConfig(config);
        return engine.processSecurityHeader(doc, data);
    }

    private static XMLSignature unmarshal(DOMValidateContext context) throws Exception {
        return XMLSignatureFactory.getInstance("DOM", "ApacheXMLDSig").unmarshalXMLSignature(context);
    }

    private DOMValidateContext validateContext(Document doc, byte[] attachmentContent) {
        Element signatureElement = (Element) doc.getElementsByTagNameNS(WSConstants.SIG_NS, "Signature").item(0);
        DOMValidateContext context = new DOMValidateContext(certificate.getPublicKey(), signatureElement);
        context.setProperty("javax.xml.crypto.dsig.cacheReference", Boolean.TRUE);
        context.setProperty(AttachmentContentSignatureTransform.ATTACHMENT_CALLBACKHANDLER,
                new AttachmentCallbackHandler(attachments(attachmentContent)));
        Element body = (Element) doc.getElementsByTagNameNS(WSConstants.URI_SOAP11_ENV, WSConstants.ELEM_BODY).item(0);
        context.setIdAttributeNS(body, WSConstants.WSU_NS, "Id");
        return context;
    }

    /**
     * Like the decrypted attachment WSS4J hands on: a stream without mark support.
     */
    private static List<Attachment> attachments(byte[] content) {
        FilterInputStream stream = new FilterInputStream(new ByteArrayInputStream(content)) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
        return new ArrayList<>(Collections.singletonList(new AttachmentImpl(ATTACHMENT_ID,
                new DataHandler(new AttachmentDataSource("application/octet-stream", stream)))));
    }
}
