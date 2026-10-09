package com.wobbuffe.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wobbuffe.core.OperationEnvelope;
import com.wobbuffe.core.OperationPublicationException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public final class SqsOperationPublisher implements OperationPublisher {
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;
    private final SqsClient client;
    private final String queueUrl;
    private final ObjectMapper objectMapper;

    public SqsOperationPublisher(SqsClient client, String queueUrl, ObjectMapper objectMapper) {
        this.client = client; this.queueUrl = queueUrl; this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public void publish(OperationEnvelope envelope) {
        try {
            String body = objectMapper.writeValueAsString(envelope);
            if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("SQS message exceeds 256 KiB");
            }
            client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
        } catch (JsonProcessingException e) {
            throw new OperationPublicationException(envelope.operationId(), e);
        } catch (RuntimeException e) {
            throw new OperationPublicationException(envelope.operationId(), e);
        }
    }
}
