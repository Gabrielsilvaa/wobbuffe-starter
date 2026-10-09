package com.wobbuffe.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wobbuffe.core.OperationEnvelope;
import com.wobbuffe.core.OperationPublicationException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class SqsOperationPublisherTest {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.7.0"))
            .withServices(LocalStackContainer.Service.SQS);

    @Test
    void publishesVersionedEnvelopeToLocalstackQueue() throws Exception {
        try (SqsClient client = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            String queueUrl = client.createQueue(builder -> builder.queueName("wobbuffe-operations")).queueUrl();
            SqsOperationPublisher publisher = new SqsOperationPublisher(client, queueUrl, new ObjectMapper());
            OperationEnvelope envelope = new OperationEnvelope(1, "op-1", "pokemon.import", "pokeapi", Instant.now(),
                    "idem-1", new ObjectMapper().readTree("{\"name\":\"pikachu\"}"), "corr-1");
            publisher.publish(envelope);
            var response = client.receiveMessage(builder -> builder.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(2));
            assertEquals(1, response.messages().size());
            assertTrue(response.messages().getFirst().body().contains("\"schemaVersion\":1"));
            assertTrue(response.messages().getFirst().body().contains("\"idempotencyKey\":\"idem-1\""));
        }
    }

    @Test
    void reportsQueueUnavailableInsteadOfTreatingPublishAsAccepted() throws Exception {
        try (SqsClient client = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            SqsOperationPublisher publisher = new SqsOperationPublisher(client, "http://localhost:4566/missing-queue", new ObjectMapper());
            OperationEnvelope envelope = new OperationEnvelope(1, "op-missing", "catalog.refresh", "catalog", Instant.now(),
                    "idem-missing", new ObjectMapper().readTree("{}"), "corr-missing");
            assertThrows(OperationPublicationException.class, () -> publisher.publish(envelope));
        }
    }
}
