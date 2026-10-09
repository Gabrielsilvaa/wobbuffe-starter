# Prompt do agente de implantação do Wobbuffe Starter

Copie o prompt abaixo para um agente de programação e execute-o com o repositório da aplicação-alvo aberto. Se o starter não estiver publicado, informe o caminho local do repositório Wobbuffe para que o agente instale ou referencie o artefato durante o desenvolvimento.

---

## Prompt

Você é um agente de engenharia responsável por integrar o **Wobbuffe Starter** à aplicação aberta neste workspace. Faça as mudanças necessárias no código e na configuração, preservando o comportamento de negócio atual.

### Objetivo

Proteger chamadas da aplicação a integrações externas com as capacidades já implementadas no Wobbuffe Starter: timeout, classificação de falhas, retries limitados com backoff e jitter, circuit breaker, métricas e, quando for seguro e solicitado pela arquitetura da aplicação, roteamento e consumo assíncronos via SQS.

O Wobbuffe Starter é uma biblioteca Java/Spring Boot. Primeiro confirme que o projeto-alvo executa em Java e Spring Boot compatíveis. Se o alvo for de outra linguagem, ou não puder carregar uma biblioteca Spring Boot, **não finja que a dependência foi integrada** e não tente copiar o código-fonte do starter para outra linguagem. Explique a incompatibilidade e proponha uma integração adequada, como um serviço Java dedicado ou um cliente/contrato de mensagens, sem implementá-la sem autorização.

### Regras de integração

1. Leia `AGENTS.md`, README, arquivos de build, configuração, código e testes relevantes do projeto antes de editar. Siga as convenções locais.
2. Verifique a versão Java e Spring Boot, como as dependências são resolvidas e se `com.wobbuffe:wobbuffe-starter` está publicado ou instalado localmente. Não invente uma versão, URL de repositório Maven ou credencial. Se o artefato não estiver acessível, use o checkout local informado ou deixe um diagnóstico claro da dependência que falta.
3. Mapeie as chamadas HTTP/RPC a sistemas externos e identifique seus clientes, pontos de entrada, políticas de timeout e tratamento atual de erro. Integre só chamadas de saída elegíveis. Não envolva banco de dados, lógica interna ou operações sem chamada externa.
4. Adicione a dependência Maven `com.wobbuffe:wobbuffe-starter` usando a versão aprovada e a dependência de AOP apenas se optar por `@ResilientIntegration`. Para SQS, inclua o módulo AWS SDK SQS e gerencie a versão por BOM. Não adicione credenciais reais ao código, YAML ou exemplos versionados.
5. Configure `wobbuffe.integrations.<integration-id>` em `application.yml`/configuração equivalente. Use IDs estáveis, como `catalog` ou `payment-provider`, nunca valores por requisição. Defina timeout do cliente HTTP além do timeout do starter. Adapte exceções HTTP para `HttpStatusException` quando necessário para classificar corretamente o status.
6. Prefira `ResilienceExecutor` quando a aplicação precisar tratar explicitamente resultado ou roteamento. `ResilienceResult.Completed<T>` contém o resultado direto. `ResilienceResult.Queued<T>` significa somente que SQS confirmou a aceitação. Mapeie-o para `202 Accepted` e forneça um identificador/status consultável quando a API tiver esse contrato.
7. Use `@ResilientIntegration("<id>")` somente para execução síncrona simples. Adicione `spring-boot-starter-aop` se ele não existir. Lembre-se de que chamadas internas no mesmo objeto não atravessam o proxy Spring.
8. Não habilite roteamento ou consumer por padrão sem verificar a necessidade e a infraestrutura da aplicação. Operações encaminhadas precisam ser idempotentes, seguras para reentrega e carregar payload JSON pequeno, sem dados sensíveis. Não encaminhe operações com efeitos que não possam ser repetidos com segurança.
9. Para roteamento assíncrono, configure fila SQS, região, endpoint quando local, `wobbuffe.routing.enabled` e `async-eligible`. Implemente `OperationStore` com reserva atômica da chave idempotente, contagem de pendências e atualização de publicação. Não alegue atomicidade entre a gravação e o publish: o starter não inclui outbox.
10. Para consumo, configure `wobbuffe.consumer.enabled`, fila, DLQ, concorrência, rate limit, visibility timeout e limite de recebimentos. Configure a redrive policy da fila para o mesmo número de recebimentos. Registre beans `OperationHandler` por par `operationType`/`integrationId`; o handler implementa domínio e retorna `JsonNode`. O starter aplica a política configurada ao handler: **não crie um segundo retry/circuit breaker dentro do handler**. Se a aplicação precisar persistir estados/resultados, implemente `OperationLifecycle` e faça as atualizações transacionais no armazenamento da própria aplicação.
11. Mantenha as chaves e tokens em secret manager ou variáveis de ambiente. O AWS SDK deve usar sua cadeia padrão de credenciais. Nunca registre payloads ou credenciais.
12. Preserve os controles, contratos HTTP, nomes de campos e respostas existentes, exceto onde uma alteração for necessária e compatível com o objetivo. Não substitua chamadas reais por mocks ou dados falsos em runtime. Não adicione uma API externa simulada.
13. Atualize a documentação de uso e as variáveis de ambiente. Inclua exemplos sem segredos e explique dependências de SQS, Store e Lifecycle que a aplicação precisar fornecer.

### Processo de trabalho

1. Resuma em poucas linhas a arquitetura existente e os pontos externos que pretende proteger.
2. Faça a integração no repositório atual. Mantenha o diff focado; não reestruture módulos sem necessidade.
3. Acrescente ou atualize testes para a integração: configuração/defaults, adaptação de erro HTTP, resultado direto e roteado, idempotência/lifecycle e contexto Spring conforme aplicável. Não chame AWS real ou APIs de negócio reais nos testes. Use doubles, WireMock ou LocalStack de acordo com o padrão existente.
4. Execute os comandos de build e testes definidos pelo projeto. Se faltar JDK, acesso a artefato ou serviço local, não declare a verificação aprovada: registre precisamente o que foi executado, o que passou e o bloqueio.
5. Revise `git diff --check`, procure segredos e confirme que o comportamento síncrono continua disponível quando o roteamento estiver desligado.

### Resposta final

Informe:

- integrações protegidas e pontos de código/configuração modificados;
- políticas e decisões de roteamento aplicadas;
- configurações/variáveis de ambiente necessárias;
- comandos de teste/build executados e seus resultados;
- limitações, dependências externas ou itens que ficaram pendentes.

Não faça commit, push, publicação ou deploy, a menos que o usuário peça isso explicitamente.
