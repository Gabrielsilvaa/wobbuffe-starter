# Wobbuffe Starter

Biblioteca Spring Boot para proteger integrações externas. O starter concentra timeout, classificação de falhas, retry com backoff e jitter, circuit breaker, roteamento opcional para SQS e um consumer genérico de operações. A aplicação hospedeira continua responsável por chamar seus clientes de domínio, armazenar seus dados e implementar os handlers específicos da aplicação.

## O que o starter fornece

- `ResilienceExecutor`: API programática para executar uma chamada síncrona ou aceitar uma operação na fila.
- `IntegrationPolicy`: política tipada por integração, criada por `WobbuffeProperties.policyFor(id)`.
- Circuit breaker independente por `integrationId`, timeout, retry seletivo e backoff exponencial com jitter.
- `@ResilientIntegration`: adaptador AOP opcional para chamadas síncronas.
- `OperationEnvelope`, `OperationPublisher` e `OperationStore`: contrato versionado para encaminhamento assíncrono.
- Publisher SQS automático quando uma fila de saída está configurada.
- Consumer SQS opcional: polling, validação, concorrência, limite de taxa, execução resiliente, redelivery, reconhecimento e métricas.
- `OperationHandler` e `OperationLifecycle`: SPI para a aplicação hospedeira fornecer lógica de domínio e persistência de status.
- Métricas Micrometer sem tags de alta cardinalidade, como IDs de operação ou de correlação.

O starter não inclui persistência de produção, não conhece o domínio da aplicação e não fornece garantias transacionais entre SQS e o banco da aplicação.

Para entender como os componentes se conectam e como uma operação percorre os caminhos síncrono e assíncrono, consulte o [guia de arquitetura](docs/architecture.md), que inclui diagramas Mermaid de componentes, sequência, roteamento, consumo e estados do circuit breaker.

Para apresentação executiva, consulte [Wobbuffe Starter — apresentação executiva](docs/apresentacao-executiva.md), com resumo de adoção, diagramas de fluxo, sequência, arquitetura, evidências da demo e limites para produção.

## Requisitos

- Java 25
- Spring Boot 3.5.x (a versão-base deste projeto é 3.5.16)
- Maven 3.9+ ou Maven Wrapper
- AWS SDK for Java 2.x somente para usar SQS
- Um `MeterRegistry` existente é reutilizado; na ausência dele o starter cria um `SimpleMeterRegistry`

## Adicionar a dependência

Se o artefato já estiver publicado no repositório Maven da organização, adicione a dependência ao `pom.xml` da aplicação:

```xml
<dependency>
    <groupId>com.wobbuffe</groupId>
    <artifactId>wobbuffe-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Para usar a versão deste checkout localmente, instale o artefato no repositório Maven local primeiro:

```bash
./mvnw -B -DskipTests install
```

Depois adicione a dependência acima à aplicação. Para usar SQS, adicione também o módulo AWS SDK, pois a dependência é opcional no starter:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
</dependency>
```

Gerencie a versão do SDK com o BOM oficial da AWS no `dependencyManagement` da aplicação. Para usar `@ResilientIntegration`, inclua `spring-boot-starter-aop`; a API programática não precisa de AOP.

## Primeira integração síncrona

Configure uma integração em `application.yml`. As propriedades não especificadas herdam os valores de `wobbuffe.defaults`.

```yaml
wobbuffe:
  enabled: true
  defaults:
    timeout: 2s
    max-attempts: 3
    initial-backoff: 200ms
    max-backoff: 2s
    jitter-ratio: 0.2
    failure-rate-threshold: 50
    slow-call-rate-threshold: 50
    slow-call-duration-threshold: 2s
    minimum-calls: 10
    breaker-window: 30s
    breaker-open-wait: 30s
    half-open-permitted-calls: 3
    latency-window: 30s
    latency-minimum-samples: 10
    degraded-latency-threshold: 2s
    degraded-error-rate-threshold: 50
  integrations:
    catalog:
      enabled: true
      timeout: 1500ms
      max-attempts: 2
      async-eligible: false
```

Injete `ResilienceExecutor` e `WobbuffeProperties` no serviço que chama o sistema externo:

```java
@Service
public class CatalogService {
    private final ResilienceExecutor resilience;
    private final WobbuffeProperties properties;
    private final CatalogClient client;

    public CatalogService(ResilienceExecutor resilience,
                          WobbuffeProperties properties,
                          CatalogClient client) {
        this.resilience = resilience;
        this.properties = properties;
        this.client = client;
    }

    public Product find(String productId) {
        ResilienceResult<Product> result = resilience.execute(
                properties.policyFor("catalog"),
                () -> client.find(productId));

        if (result instanceof ResilienceResult.Completed<Product> completed) {
            return completed.value();
        }
        throw new IllegalStateException("Uma chamada síncrona não deveria retornar uma operação enfileirada");
    }
}
```

O `integrationId` deve ser estável e vir da configuração, por exemplo `catalog` ou `billing`. Não use IDs de requisição, nomes de usuário ou outros valores variáveis como ID de integração.

### Erros transitórios e permanentes

O retry padrão cobre falhas de rede e timeout, HTTP 408, 429, 500, 502, 503 e 504. Outros 4xx, 501 e 505 são tratados como permanentes. Adapte as exceções do cliente HTTP para `HttpStatusException` para que o status seja classificado corretamente:

```java
catch (RestClientResponseException e) {
    throw new HttpStatusException(e.getStatusCode().value(), "Falha no serviço externo", e);
}
```

O starter cancela a tarefa quando o timeout expira, mas uma operação de I/O bloqueante pode ignorar a interrupção. Configure também timeout de conexão e leitura no cliente HTTP. O executor de I/O do starter tem 8 threads e fila limitada a 256 tarefas por padrão.

Exceções públicas que podem ser observadas pela aplicação incluem `CircuitOpenException`, `IntegrationTimeoutException`, `ResilienceExecutionException`, `OperationPublicationException`, `OperationRejectedException` e `OperationRoutingUnavailableException`. Traduza-as para as respostas HTTP apropriadas na borda da aplicação.

## Valores padrão das políticas

| Política | Padrão |
| --- | ---: |
| Timeout por chamada | 2 s |
| Tentativas totais | 3 |
| Backoff inicial / máximo | 200 ms / 2 s |
| Jitter | 20% |
| Janela do circuit breaker | 30 s |
| Mínimo de chamadas para avaliar | 10 |
| Taxa de falhas para abrir | 50% |
| Taxa de chamadas lentas para abrir | 50% |
| Chamada lenta | 2 s |
| Espera em OPEN | 30 s |
| Chamadas permitidas em HALF_OPEN | 3 |
| Amostras mínimas da degradação | 10 em 30 s |

`wobbuffe.enabled=false` desativa globalmente a política. `wobbuffe.integrations.<id>.enabled=false` desativa uma integração individual. Um override só substitui os campos fornecidos; os outros continuam herdando os defaults.

## Usar `@ResilientIntegration` (opcional)

Com `spring-boot-starter-aop` no classpath, anote um método síncrono:

```java
@ResilientIntegration("catalog")
public Product find(String id) {
    return catalogClient.find(id);
}
```

O aspecto obtém a política pelo valor da anotação e retorna diretamente o resultado do método. A anotação não enfileira operações. Como em outros proxies Spring, a chamada precisa atravessar o proxy: chamadas internas entre métodos do mesmo objeto não passam pelo aspecto. Prefira `ResilienceExecutor` quando precisar de roteamento ou controle explícito do resultado.

## Encaminhar uma operação à fila

O roteamento é opt-in. O exemplo abaixo configura o publisher e os limites de admissão:

```yaml
wobbuffe:
  routing:
    enabled: true
    max-percent: 25
    step-percent: 5
    max-pending: 1000
    evaluation-interval: 10s
  integrations:
    catalog:
      async-eligible: true
  sqs:
    region: us-east-1
    endpoint: http://localhost:4566 # omita em AWS
    queue-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations
```

Implemente o SPI `OperationStore` na aplicação. A implementação deve reservar atomicamente a chave idempotente, informar o total pendente e persistir aceitação ou falha de publicação. O starter não fornece banco ou implementação de produção.

Crie um envelope pequeno, versionado e sem dados sensíveis. O retorno distingue execução direta de aceitação na fila:

```java
OperationEnvelope envelope = new OperationEnvelope(
        1,
        UUID.randomUUID().toString(),
        "catalog.refresh",
        "catalog",
        Instant.now(),
        idempotencyKey,
        objectMapper.valueToTree(Map.of("productId", productId)),
        correlationId);

ResilienceResult<Product> result = resilience.execute(
        properties.policyFor("catalog"),
        envelope,
        () -> catalogClient.refresh(productId));

if (result instanceof ResilienceResult.Queued<Product>) {
    // A publicação foi confirmada pela fila; o processamento ainda não terminou.
}
```

`execute(policy, envelope, call)` usa admissão adaptativa durante degradação. A amostragem começa em 5%, sobe em passos de 5 pontos até o limite configurado e recua após janelas saudáveis. Só operações com `async-eligible: true` podem ser encaminhadas. Se a chamada continuar síncrona, o resultado é `Completed<T>`; se o publisher confirmar o envio, o resultado é `Queued<T>`.

Para solicitar o enfileiramento diretamente, use `enqueue(policy, envelope)`. Isso exige elegibilidade, `routing.enabled`, publisher SQS e `OperationStore`; se a infraestrutura não estiver pronta, o método falha explicitamente. Uma fila cheia ou indisponível nunca é tratada como publicação bem-sucedida. Se o breaker estiver aberto e nenhuma rota for aceita, o executor lança `CircuitOpenException`.

Não interprete `Queued` como conclusão do trabalho. Em um endpoint HTTP, responda `202 Accepted` somente depois de receber esse resultado e exponha um identificador para consulta de status.

## Ativar o consumer genérico SQS

O consumer roda dentro do processo Spring Boot que inclui o starter. Ele permanece desligado por padrão. Quando ligado, é o starter que faz polling, valida o `OperationEnvelope`, aplica concorrência e rate limit e executa o handler sob a política `wobbuffe.integrations.<integrationId>`. O handler da aplicação deve conter apenas lógica de domínio; não chame `ResilienceExecutor` novamente dentro dele.

```yaml
wobbuffe:
  sqs:
    endpoint: http://localhost:4566 # omita em AWS
    region: us-east-1
    queue-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations
  consumer:
    enabled: true
    queue-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations
    dlq-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations-dlq
    poll-wait-seconds: 20
    batch-size: 5
    visibility-timeout-seconds: 90
    max-receive-count: 5
    max-concurrency: 2
    rate-limit-per-second: 5
    max-message-bytes: 262144
    poll-delay-millis: 1000
    queue-metrics-delay-millis: 30000
```

Registre um bean `OperationHandler` para cada par de tipo e integração:

```java
@Component
public class CatalogRefreshHandler implements OperationHandler {
    public String operationType() { return "catalog.refresh"; }
    public String integrationId() { return "catalog"; }

    public JsonNode handle(OperationEnvelope operation) {
        String productId = operation.payload().path("productId").asText();
        CatalogResult result = catalogClient.refresh(productId);
        return objectMapper.valueToTree(result);
    }
}
```

O starter rejeita pares sem handler e coloca mensagens inválidas na política de redrive da fila. Falhas transitórias são liberadas para nova entrega com atraso. Falhas permanentes podem ser sinalizadas com `PermanentOperationException`. Mensagens processadas com sucesso são apagadas da fila.

Implemente `OperationLifecycle` para persistir os estados `PROCESSING`, `SUCCEEDED` e `FAILED`. Sem esse bean, o starter usa um lifecycle vazio; o processamento ainda funciona, mas não há atualização de status na aplicação. O método `succeeded` recebe o resultado JSON do handler, permitindo que a aplicação persista resultado e status na mesma transação local.

Configure a redrive policy na fila principal com o mesmo `max-receive-count` e a DLQ indicada em `dlq-url`. O visibility timeout deve cobrir o tempo total esperado do handler, incluindo as tentativas e backoffs da política. SQS fornece entrega ao menos uma vez: os handlers e o armazenamento da aplicação devem tolerar reentrega e duplicação.

## Configuração completa de propriedades

```yaml
wobbuffe:
  enabled: true
  defaults:                 # timeout, retry e limites comuns do circuit breaker
    timeout: 2s
    max-attempts: 3
    initial-backoff: 200ms
    max-backoff: 2s
    jitter-ratio: 0.2
    failure-rate-threshold: 50
    slow-call-rate-threshold: 50
    slow-call-duration-threshold: 2s
    minimum-calls: 10
    breaker-window: 30s
    breaker-open-wait: 30s
    half-open-permitted-calls: 3
    latency-window: 30s
    latency-minimum-samples: 10
    degraded-latency-threshold: 2s
    degraded-error-rate-threshold: 50
  integrations:             # overrides independentes por integração
    catalog:
      enabled: true
      async-eligible: false
      timeout: 1500ms
      max-attempts: 2
  routing:
    enabled: false
    max-percent: 25
    step-percent: 5
    max-pending: 1000
    evaluation-interval: 10s
  sqs:
    region: us-east-1
    endpoint: http://localhost:4566
    queue-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations
  consumer:
    enabled: false
    queue-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations
    dlq-url: http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations-dlq
    poll-wait-seconds: 20
    batch-size: 5
    visibility-timeout-seconds: 90
    max-receive-count: 5
    max-concurrency: 2
    rate-limit-per-second: 5
    max-message-bytes: 262144
    poll-delay-millis: 1000
    queue-metrics-delay-millis: 30000
```

Todas as propriedades também podem ser definidas com variáveis de ambiente Spring, por exemplo `WOBBUFFE_CONSUMER_ENABLED`, `WOBBUFFE_CONSUMER_MAX_CONCURRENCY`, `WOBBUFFE_SQS_ENDPOINT` e `WOBBUFFE_SQS_QUEUE_URL`. Use o mecanismo de secrets da plataforma para credenciais AWS. O SDK usa a cadeia padrão de credenciais e não precisa de chaves codificadas no YAML.

## Métricas e logs

As métricas de integração usam o prefixo `wobbuffe.integration.*` e incluem latência, resultado, tentativas, status HTTP, estado do breaker, degradação, roteamento, publicação e pendências. O consumer publica métricas `wobbuffe.consumer.*` para mensagens recebidas e concluídas, redelivery, falhas de polling, concorrência, rate limit e backlog da fila/DLQ. Os logs podem incluir operation ID e correlation ID para diagnóstico, mas não registram payload, credenciais ou tokens.

## Executar localmente com LocalStack

A infraestrutura de demonstração fica no repositório [wobbuffe-demo](https://github.com/Gabrielsilvaa/wobbuffe-demo). Clone os dois repositórios lado a lado conforme as instruções do demo para executar LocalStack e a API.

```bash
cd ../wobbuffe-demo
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/compose.yaml up --build -d
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url=http://localhost:4566 sqs list-queues
```

Para conferir o redrive, continue no diretório `wobbuffe-demo`:

```bash
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url=http://localhost:4566 sqs get-queue-attributes \
  --queue-url http://localhost:4566/queue/us-east-1/000000000000/wobbuffe-operations \
  --attribute-names RedrivePolicy
```

Dentro do Compose, use `http://localstack:4566` como endpoint e o host `localstack` nas URLs das filas. Na máquina host, use `http://localhost:4566`. As credenciais `test/test` são placeholders somente para LocalStack.

## Limites de entrega e consistência

- A confirmação de publicação SQS não é transacional com `OperationStore`; esta versão não inclui outbox.
- SQS entrega pelo menos uma vez. Chaves idempotentes ajudam, mas o handler ainda precisa tolerar reentrega.
- H2 em memória desaparece quando o processo reinicia; não o trate como armazenamento durável de status.
- O cancelamento por timeout interrompe a espera e tenta cancelar a tarefa, mas não garante que I/O bloqueante pare.
- Um status `202` significa aceitação confirmada pela fila, não conclusão do processamento.
- O consumer genérico não sabe como persistir os dados de domínio; essa responsabilidade é do `OperationLifecycle` hospedeiro.

## Construir e verificar

```bash
./mvnw -B verify
```

Os testes do starter não chamam APIs de domínio reais. O uso do LocalStack em produção local é opcional; configure publisher e consumer somente quando a aplicação realmente for usar SQS.

## Aplicar o starter em outra aplicação

O prompt reutilizável para um agente está em [agente-de-implantacao/PROMPT.md](agente-de-implantacao/PROMPT.md). Passe o arquivo ao agente junto com o repositório-alvo. Ele instrui o agente a primeiro verificar se a aplicação é compatível com Spring Boot/Java, integrar apenas as chamadas adequadas e preservar a lógica de domínio.
