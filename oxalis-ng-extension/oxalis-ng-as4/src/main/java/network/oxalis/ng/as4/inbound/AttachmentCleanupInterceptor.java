package network.oxalis.ng.as4.inbound;

import lombok.SneakyThrows;
import org.apache.cxf.attachment.As4AttachmentDataSource;
import org.apache.cxf.attachment.As4AttachmentDeserializer;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;

import jakarta.activation.DataSource;

public class AttachmentCleanupInterceptor extends AbstractPhaseInterceptor<Message> {

    public AttachmentCleanupInterceptor() {
        this(Phase.POST_INVOKE);
    }

    /**
     * @param phase phase to run in, e.g. an out-fault phase so cached attachments are also removed when the
     *              request is rejected
     */
    public AttachmentCleanupInterceptor(String phase) {
        super(phase);
    }

    public void handleMessage(Message message) throws Fault {
        Exchange exchange = message.getExchange();
        cleanRequestAttachment(exchange);
    }

    @SneakyThrows
    private void cleanRequestAttachment(Exchange exchange) {
        Message inMessage = exchange.getInMessage();
        As4AttachmentDeserializer ad = inMessage == null ? null : inMessage.get(As4AttachmentDeserializer.class);
        if (ad == null) {
            return;
        }
        ad.getRemoved().forEach(this::close);
        ad.closeRereadable();
    }

    @SneakyThrows
    private void close(Attachment attachment) {
        DataSource dataSource = attachment.getDataHandler().getDataSource();

        if (dataSource instanceof As4AttachmentDataSource) {
            As4AttachmentDataSource ads = (As4AttachmentDataSource) dataSource;
            ads.closeAll();
        }
    }
}
