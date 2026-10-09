# Arquitetura do Wobbuffe Starter

Este documento descreve os limites arquiteturais do starter e o caminho que uma chamada percorre nos modos síncrono e assíncrono. O desenho representa o estado atual do código: o starter contém os mecanismos genéricos de resiliência; a aplicação hospedeira contém os clientes e handlers do seu domínio e os adaptadores para persistência.

## Visão de sistema

O starter é uma biblioteca carregada dentro do processo Spring Boot da aplicação. Não é um serviço independente e não cria um banco próprio. Quando SQS está configurado, o mesmo processo pode publicar e, opcionalmente, consumir mensagens.

```mermaid
flowchart LR
    User[Cliente ou usuário] --> Host[Aplicação hospedeira<br/>API, jobs ou serviços]

    subgraph JVM[Um processo Spring Boot]
        Host --> Auto[Autoconfiguração Wobbuffe]
        Auto --> Executor[ResilienceExecutor]
        Executor --> Policies[Políticas por integração]
        Executor --> Breakers[Circuit breakers]
        Executor --> Router[Roteador adaptativo]
        Executor --> Metrics[Métricas Micrometer]
        Router --> Store[OperationStore<br/>SPI da aplicação]
        Router --> Publisher[OperationPublisher<br/>SQS automático]
        Consumer[Consumer SQS do starter] --> Executor
        Consumer --> Handler[OperationHandler<br/>implementado pela aplicação]
        Consumer --> Lifecycle[OperationLifecycle<br/>implementado pela aplicação]
        Handler --> Domain[Cliente e domínio da aplicação]
        Lifecycle --> DB[(Persistência da aplicação)]
    end

    Publisher --> Queue[(SQS: fila principal)]
    Queue --> Consumer
    Queue --> DLQ[(SQS: DLQ)]
    Domain --> Upstream[Sistema externo]
```

### Limites dos componentes

- **Wobbuffe Starter:** carrega as propriedades, constrói políticas, executa chamadas protegidas, decide se uma operação elegível pode ser encaminhada, publica e consome envelopes SQS, aplica limites de concorrência e taxa, agenda redelivery e registra métricas.
- **Aplicação hospedeira:** escolhe quais integrações usar, configura endpoints e credenciais, adapta exceções do cliente HTTP, fornece handlers de domínio, fornece `OperationStore` para roteamento e pode fornecer `OperationLifecycle` para atualizar status e persistir resultados.
- **SQS:** armazena temporariamente mensagens e entrega pelo menos uma vez. A redrive policy da fila principal controla a transferência para a DLQ.
- **Sistema externo:** é chamado pelo código da aplicação dentro do executor do starter. A biblioteca não substitui nem simula o sistema externo.

## Chamada síncrona

`ResilienceExecutor.execute(policy, supplier)` é a fronteira programática. O starter verifica se a política está ativa, seleciona o circuit breaker da integração e executa cada tentativa com timeout. Falhas classificadas como transitórias podem ser repetidas até o limite configurado.

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Serviço hospedeiro
    participant Exec as ResilienceExecutor
    participant CB as Circuit breaker da integração
    participant Pool as Executor limitado de I/O
    participant API as Cliente do sistema externo

    Caller->>Exec: execute(policy, supplier)
    Exec->>CB: solicitar permissão
    CB-->>Exec: permitido
    Exec->>Pool: iniciar tentativa
    Pool->>API: chamada externa
    alt resposta bem-sucedida
        API-->>Pool: resultado
        Pool-->>Exec: resultado dentro do timeout
        Exec->>CB: registrar sucesso e latência
        Exec-->>Caller: Completed(resultado)
    else falha transitória e há tentativas restantes
        API-->>Pool: erro de rede, timeout ou HTTP transitório
        Exec->>CB: registrar resultado da tentativa
        Exec->>Exec: backoff exponencial com jitter
        Exec->>CB: solicitar permissão para nova tentativa
        CB-->>Exec: permitido
        Exec->>Pool: executar próxima tentativa
        Pool->>API: chamada externa novamente
        API-->>Pool: resultado ou erro final
        Pool-->>Exec: resultado final
        Exec-->>Caller: Completed ou exceção tipada
    else breaker aberto ou timeout esgotado
        CB-->>Exec: chamada não permitida
        Exec-->>Caller: CircuitOpenException ou IntegrationTimeoutException
    end
```

O timeout cancela a espera e tenta interromper a tarefa. Clientes de I/O bloqueantes podem não parar ao receber uma interrupção; a aplicação também deve configurar timeout de conexão e leitura no cliente HTTP.

## Decisão de execução ou encaminhamento

O roteamento adaptativo é desligado por padrão. Quando ligado, só se aplica a uma integração configurada com `async-eligible: true`, e o encaminhamento ainda depende de degradação, amostragem, capacidade, `OperationStore` e confirmação do publisher.

```mermaid
flowchart TD
    Start[Chega chamada elegível] --> Enabled{Resiliência global e integração habilitadas?}
    Enabled -- Não --> Direct[Executar supplier diretamente]
    Enabled -- Sim --> Degraded{Breaker OPEN ou monitor indica degradação?}
    Degraded -- Não --> Sync[Executar sincronamente com timeout, retry e breaker]
    Degraded -- Sim --> CanRoute{Roteamento ativo, integração elegível,<br/>publisher e store disponíveis?}
    CanRoute -- Não --> Breaker{Breaker permite chamada síncrona?}
    Breaker -- Sim --> Sync
    Breaker -- Não --> Fail[Falhar com circuito aberto]
    CanRoute -- Sim --> Capacity{Pendências abaixo do limite?}
    Capacity -- Não --> Breaker
    Capacity -- Sim --> Sample{Operação selecionada<br/>pela amostragem atual?}
    Sample -- Não --> Breaker
    Sample -- Sim --> Reserve[Reservar idempotência no OperationStore]
    Reserve -- Chave já existe --> Reject[Rejeitar duplicata]
    Reserve -- Reservada --> Publish[Publicar envelope na fila SQS]
    Publish -- Confirmação --> Mark[Marcar publicação aceita no store]
    Mark --> Accepted[Retornar Queued / HTTP 202]
    Publish -- Erro --> MarkFailure[Marcar publicação com falha]
    MarkFailure --> PublishError[Propagar falha; não declarar aceitação]
```

O monitor avalia uma janela de latência e falhas com número mínimo de amostras. A amostragem inicia em 5%, sobe em passos de 5 pontos percentuais até `max-percent` durante degradação e diminui após janelas saudáveis. Mesmo com rota habilitada, chamadas saudáveis normalmente seguem pelo caminho síncrono.

`enqueue(policy, envelope)` é diferente: ele solicita enfileiramento imediato e falha explicitamente se a rota não estiver pronta, a integração não for elegível, o limite de pendências for atingido, a chave idempotente já existir ou o envio falhar.

## Publicação e consistência

O envelope inclui versão do schema, IDs de operação e integração, instante de criação, chave idempotente, payload JSON e correlation ID. Ele deve conter apenas o necessário ao handler e permanecer pequeno e sem dados sensíveis.

```mermaid
sequenceDiagram
    autonumber
    participant API as Endpoint da aplicação
    participant Exec as ResilienceExecutor
    participant Store as OperationStore da aplicação
    participant Pub as OperationPublisher do starter
    participant SQS as Fila SQS

    API->>API: montar OperationEnvelope
    API->>Exec: execute(policy, envelope, supplier)
    Exec->>Store: reservar idempotencyKey
    Store-->>Exec: reserva confirmada
    Exec->>Pub: publicar envelope
    Pub->>SQS: SendMessage
    SQS-->>Pub: confirmação
    Pub-->>Exec: publicação aceita
    Exec->>Store: markPublished(operationId)
    Exec-->>API: Queued(operationId)
    API-->>API: responder 202 e expor URL de status
```

O store e SQS não participam da mesma transação. Uma queda entre a reserva e o envio pode deixar uma operação pendente; uma resposta ambígua de rede pode causar reentrega. O starter registra falha de publicação quando consegue observar o erro, mas não fornece outbox. A chave idempotente e um store transacional reduzem duplicação; não substituem handlers seguros para repetição.

## Consumo de uma operação

O consumer é criado apenas com `wobbuffe.consumer.enabled=true`, AWS SDK SQS no classpath e URL de fila configurada. A autoconfiguração registra o polling, o executor limitado, o rate limiter, o processador e as métricas da fila.

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as Poller do starter
    participant SQS as Fila SQS
    participant Processor as OperationProcessor
    participant Lifecycle as OperationLifecycle da aplicação
    participant Exec as ResilienceExecutor
    participant Handler as OperationHandler da aplicação
    participant Upstream as Sistema externo
    participant DB as Persistência da aplicação

    loop Polling com long poll
        Scheduler->>SQS: ReceiveMessage (lote e visibility timeout)
        SQS-->>Scheduler: envelope(s)
    end
    Scheduler->>Processor: despachar dentro do limite de concorrência
    Processor->>Processor: validar schema, tamanho e handler
    Processor->>Lifecycle: processing(envelope)
    Processor->>Exec: executar handler pela policy de integrationId
    Exec->>Handler: handle(envelope), com timeout/retry/breaker
    Handler->>Upstream: chamada de domínio
    Upstream-->>Handler: resultado
    Handler-->>Exec: JSON de resultado
    Exec-->>Processor: Completed(resultado)
    Processor->>Lifecycle: succeeded(envelope, resultado)
    Lifecycle->>DB: persistir estado e resultado
    DB-->>Lifecycle: commit
    Processor->>SQS: DeleteMessage (ack após persistência)
```

O `OperationHandler` contém a operação de domínio. O starter aplica a política em torno do handler; não coloque uma segunda camada de retry/circuit breaker no handler. `OperationLifecycle` é opcional: sem implementação da aplicação, o consumer processa e confirma a mensagem sem persistir estados de negócio.

### Falhas no consumer

```mermaid
flowchart TD
    Receive[Mensagem recebida] --> Valid{Envelope válido e handler conhecido?}
    Valid -- Não --> Poison[Não apagar; contar recebimentos]
    Poison --> Redrive[SQS move para DLQ ao exceder maxReceiveCount]
    Valid -- Sim --> Handler[Executar handler com política da integração]
    Handler -- Sucesso --> Persist[Lifecycle persiste sucesso/resultado]
    Persist --> Ack[Apagar mensagem da fila]
    Handler -- Erro permanente --> Failed[Lifecycle registra falha]
    Failed --> Drop[Apagar mensagem]
    Handler -- Circuito aberto --> Wait[Aumentar visibility timeout]
    Handler -- Erro transitório ou timeout --> Retry[Contar recebimento e adiar redelivery]
    Retry --> Limit{Excedeu limite configurado?}
    Limit -- Não --> Handler
    Limit -- Sim --> Mark[Registrar RETRIES_EXHAUSTED]
    Mark --> DLQ[SQS redrive para DLQ]
```

O limite local `max-receive-count` deve ser igual ao `maxReceiveCount` da redrive policy da fila. Configure o visibility timeout para cobrir o tempo total do handler, incluindo tentativas e backoffs. Falhas de polling são medidas e o scheduler tenta novamente no próximo ciclo.

## Estados do circuit breaker

O breaker é isolado por `integrationId`. Ele usa uma janela temporal, mínimo de chamadas, limites de falha e de chamadas lentas; depois de esperar em `OPEN`, permite um número limitado de probes em `HALF_OPEN`.

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> CLOSED: sucesso ou amostra insuficiente
    CLOSED --> OPEN: taxa de falha/lentidão atinge o limite
    OPEN --> OPEN: chamada rejeitada antes do tempo de espera
    OPEN --> HALF_OPEN: período OPEN terminou
    HALF_OPEN --> CLOSED: probes aprovados
    HALF_OPEN --> OPEN: probe falha ou é lenta
```

O monitor de degradação e o circuit breaker se complementam. O breaker decide se uma tentativa síncrona pode começar; o monitor calcula saúde recente e informa o roteamento adaptativo.

## O que a aplicação precisa fornecer

- Um `OperationStore` para usar roteamento assíncrono; ele reserva idempotência, informa pendências e atualiza publicação.
- Um ou mais beans `OperationHandler` para tipos de operação consumidos.
- Um `OperationLifecycle` quando estados/resultados precisarem ser gravados no banco da aplicação.
- Cliente HTTP/RPC de domínio com timeout de rede e adaptação de exceções para classificação.
- Fila principal e DLQ com redrive policy compatível; credenciais vêm da cadeia padrão do AWS SDK ou do secret manager da plataforma.

O starter não exige SQS para execução síncrona. Sem URL de fila, não cria publisher SQS. Sem `consumer.enabled`, não inicia polling.

## Implementação de referência neste repositório

Na aplicação demo, a API mantém o handler Pokémon e o adaptador JPA. A API instancia o envelope com o nome Pokémon, enquanto o starter aceita a publicação, consome a mensagem e envolve o handler com a política `pokeapi`. O adaptador da API atualiza `PROCESSING`, salva o Pokémon e marca `SUCCEEDED`. O LocalStack fornece fila e DLQ. H2 em memória é somente armazenamento de demonstração e perde os dados ao reiniciar a API.

Veja [a configuração da API](../../wobbuffe-api/src/main/resources/application.yml), [o handler Pokémon](../../wobbuffe-api/src/main/java/com/wobbuffe/api/operation/PokemonImportOperationHandler.java) e [o adaptador de ciclo de vida](../../wobbuffe-api/src/main/java/com/wobbuffe/api/operation/ApiOperationLifecycle.java) para a implementação concreta.
