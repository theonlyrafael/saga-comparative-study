# saga-comparative-study

Estudo comparativo de Sagas para compra simulada de um ativo. A modalidade
orquestrada começa com quatro serviços de domínio e um coordenador técnico.
A coreografia será implementada posteriormente, preservando as regras de domínio.

O progresso da implementação está na [checklist da Saga orquestrada](#progresso-da-saga-orquestrada).
Os resultados detalhados e as limitações estão em [docs/progress.md](docs/progress.md).

## Organização

| Diretório | Finalidade |
| --- | --- |
| `orquestrada/` | Cinco aplicações Spring Boot e construção Maven da modalidade |
| `coreografada/` | Escopo documentado para implementação posterior |
| `compartilhado/` | Contratos e regras puras, sem persistência compartilhada |
| `infra/` | PostgreSQL, RabbitMQ e ferramentas de desenvolvimento |
| `experimentos/` | Cenários e resultados futuros, dependentes do piloto |
| `docs/` | Especificação, decisões e acompanhamento |

A [especificação original](docs/specifications/orchestrated-saga-specification.pdf)
é a referência de domínio. O [guia de preparação](docs/preparation-guide.md) foi
preservado como referência histórica da preparação. O estado atual inclui
infraestrutura executável, reservas e liquidação locais, conforme a checklist abaixo.

Código, pacotes e artefatos usam inglês. Documentação e mensagens de commit usam
português, com prefixos Conventional Commits (`chore`, `build`, `docs`, `feat`, `test`).
Branches curtas por tarefa partem de `dev` e entram por pull request em `dev`;
o conjunto aceito segue de `dev` para `main`. Excluir a feature somente após seu merge. As duas modalidades coexistem no mesmo histórico.

Estado e validações: [docs/progress.md](docs/progress.md).

## Executar o bootstrap

Pré-requisitos: JDK 17 completo (Temurin recomendado), Docker Engine/Desktop com
Compose v2 e Bash/curl. No Windows, use Git Bash com Docker Desktop em modo Linux.
Maven global é dispensável: o Wrapper baixa e verifica a distribuição 3.10.0.
Confira `java -version`, `javac -version`, `docker version` e `docker compose version`.
[Versões, checksums e ajustes para esta nuvem](docs/environment.md).

Na raiz do repositório:

```bash
# Preserva um .env que já exista.
test -e infra/.env || cp infra/.env.example infra/.env
# Edite infra/.env e substitua todos os CHANGE_ME por senhas locais distintas.
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up -d --wait
bash infra/scripts/check-database-isolation.sh
cd orquestrada
./mvnw clean verify
cd ..
```

Abra cinco terminais na raiz, configurando o JDK 17 em cada um, e execute **um
comando por terminal**:

```bash
bash infra/scripts/run-service.sh order
bash infra/scripts/run-service.sh account
bash infra/scripts/run-service.sh custody
bash infra/scripts/run-service.sh settlement
bash infra/scripts/run-service.sh orchestrator
```

O script lê seu `.env` local e executa o JAR em primeiro plano. Opcionalmente,
execute `export JAVA_TOOL_OPTIONS='-Xms64m -Xmx256m'` em cada terminal; foi o limite usado
na validação do bootstrap. Não é uma recomendação de capacidade para experimentos.
Aguarde a inicialização e então, em outro terminal na raiz:

```bash
bash infra/scripts/check-health.sh
bash infra/scripts/check-database-isolation.sh
docker compose --env-file infra/.env -f infra/compose.yaml ps
```

Endpoints disponíveis em **cada** porta 8081–8085:

| Caminho HTTP | Resultado esperado |
| --- | --- |
| `/actuator/health` | HTTP 200 e status UP com dependências disponíveis |
| `/actuator/health/liveness` | Estado do processo, independente de banco/broker |
| `/actuator/health/readiness` | Inclui PostgreSQL e RabbitMQ; HTTP 503 se indisponíveis |
| `/actuator/prometheus` | Métricas em formato Prometheus |

A API `/api/ordens` ainda não existe (HTTP 404). A confirmação de saúde não
comprova publicação/consumo de mensagens nem funcionamento da Saga.

## Encerrar e retomar

Use `Ctrl+C` em cada terminal de aplicação. Em seguida:

```bash
docker compose --env-file infra/.env -f infra/compose.yaml down
```

Os volumes são preservados. Para retomar, execute `up -d --wait` e os cinco comandos
de aplicação novamente. **Não use `down -v` para encerrar:** ele destrói os dados.
Trocar senhas no `.env` não modifica usuários já persistidos nos volumes; consulte
[infra/README.md](infra/README.md) para o ciclo de vida da configuração.

## Progresso da Saga orquestrada

Os itens marcados representam capacidades implementadas e verificadas nesta branch,
não aprovação de PR nem funcionamento completo da Saga distribuída.

### Etapas concluídas

- [x] Preparar o monorepositório, a especificação, as convenções e a documentação de desenvolvimento.
- [x] Configurar Java 17, Maven Wrapper e as cinco aplicações Spring Boot independentes.
- [x] Configurar PostgreSQL com cinco bancos/usuários isolados, RabbitMQ, volumes e migrações Flyway.
- [x] Disponibilizar saúde, liveness, readiness, métricas Prometheus e logs estruturados.
- [x] Implementar [reservas locais de saldo](docs/decisions/0003-account-balance-reservations.md): reservar, confirmar e liberar com idempotência, transações e controle de concorrência.
- [x] Implementar [reservas locais de ativos](docs/decisions/0004-asset-reservations.md): reservar, liberar e transferir ao comprador uma única vez, preservando a posição de origem.
- [x] Implementar [liquidação local durável](docs/decisions/0005-local-settlement.md): validar reservas compatíveis, registrar uma decisão única e permitir replay e consultas por operação, ordem e Saga.
- [x] Corrigir o conflito de replay com conta inexistente e proteger as operações SQL de saldo contra valores não positivos e ausência de transação.
- [x] Validar as três capacidades locais com **180 testes aprovados**, sem falhas, erros ou ignorados: 53 de saldo, 81 de custódia e 46 de liquidação.
- [x] Verificar a inicialização das cinco aplicações, saúde/readiness UP e isolamento dos bancos (cinco acessos próprios e vinte recusas cruzadas).

Os resultados acima correspondem à validação registrada em
[docs/progress.md](docs/progress.md). Os testes concorrentes locais não comprovam
escalabilidade sob carga nem recuperação de uma Saga distribuída.

### Próximas etapas

Sequência prevista a partir da especificação; manter cada item desmarcado até sua
implementação e validação. A próxima entrega é a API de ordens com catálogo e
idempotência HTTP.

- [ ] Implementar catálogo fixo/versionado e captura do preço no registro da ordem.
- [ ] Implementar `POST /api/ordens` e consulta por `GET /api/ordens/{orderId}`, com validação e persistência do estado da ordem.
- [ ] Implementar idempotência HTTP por cliente e `Idempotency-Key`: reenvio igual retorna a ordem original; conteúdo divergente recebe 409.
- [ ] Versionar seeds de catálogo, saldo inicial e estoque configuráveis por cenário.
- [ ] Definir contratos de mensagens v1, roteamento RabbitMQ, validação dos envelopes e correlação por `orderId`, `sagaId` e `operationId` estável.
- [ ] Implementar Outbox transacional, publicação persistente com publisher confirms e reenvio de publicações pendentes.
- [ ] Implementar consumidores idempotentes/Inbox, resultados duráveis das operações, ACK após commit, retries e DLQ para erros de contrato.
- [ ] Implementar o motor persistente da Saga, com estados técnicos, etapas, prazos e consulta restrita de diagnóstico.
- [ ] Integrar o caminho feliz: reservar saldo → reservar ativo → registrar liquidação → efetivar saldo/ativo → finalizar a ordem como LIQUIDADA.
- [ ] Implementar compensações em ordem inversa antes da liquidação, rejeitando a ordem somente após confirmar a liberação dos recursos.
- [ ] Implementar reconciliação após falhas/reinício e consulta de resultados desconhecidos; após liquidação confirmada, concluir efetivações sem compensação automática.
- [ ] Completar observabilidade do fluxo com tracing OpenTelemetry, identificadores nos logs e métricas de convergência, compensação e latência.
- [ ] Executar testes integrados de falhas C1–C6, duplicação, concorrência, crash entre commit/publicação e recuperação após liquidação, conforme o protocolo-base.
- [ ] Validar os invariantes fim a fim: ausência de reservas após rejeição, efetivações únicas antes de LIQUIDADA e identificação das Sagas não terminais.
- [ ] Preparar o piloto com cenários, seeds, versões e configurações reprodutíveis; medir carga e capacidade antes de fixar parâmetros experimentais.

A modalidade coreografada permanece uma etapa posterior, após a validação da
orquestrada, preservando as mesmas regras de negócio e critérios de comparação.

## Infraestrutura e limites

```mermaid
flowchart LR
    order[order-service :8081] --> pg[(PostgreSQL: cinco bancos isolados)]
    account[account-service :8082] --> pg
    custody[custody-service :8083] --> pg
    settlement[settlement-service :8084] --> pg
    orchestrator[orchestrator-service :8085] --> pg
    order --> rabbit[RabbitMQ: vhost saga]
    account --> rabbit
    custody --> rabbit
    settlement --> rabbit
    orchestrator --> rabbit
```

O diagrama representa conexões de infraestrutura, não um fluxo de Saga implementado.
A checklist distingue as capacidades locais já verificadas das etapas distribuídas
pendentes. Veja também a [comparação com o protótipo](docs/prototype-comparison.md)
e os [resultados e limitações](docs/progress.md).
