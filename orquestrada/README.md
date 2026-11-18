# Modalidade orquestrada

| Módulo | Responsabilidade | Porta |
| --- | --- | --- |
| order-service | Entrada HTTP, catálogo, preço capturado e estado da ordem | 8081 |
| account-service | Reserva, liberação e consumo de saldo | 8082 |
| custody-service | Reserva, liberação e efetivação de ativos | 8083 |
| settlement-service | Decisão durável de liquidação | 8084 |
| orchestrator-service | Coordenação técnica, recuperação e compensações | 8085 |

Estão implementadas as reservas locais de saldo e ativos e a decisão durável de
liquidação. Há API local de ordens com catálogo e idempotência HTTP. Ainda não há
consumidores, Inbox ou máquina de estados distribuída. A Outbox de ordens registra OrdemRecebida no POST. As liberações locais existem; a coordenação das compensações ainda não.
Pacotes: `io.github.theonlyrafael.sagastudy.orchestrated.<order|account|custody|settlement|orchestrator>`.
O futuro contrato HTTP mantém `/api/ordens`, `clienteId`, `ativoId`, `quantidade`
e os estados e tipos de mensagens do PDF; inglês interno não altera o contrato.

## Construção e execução

Execute `./mvnw clean verify` neste diretório com JDK 17. O parent gerencia as
versões; cada módulo gera um JAR executável em `target/`. Consulte o
[README principal](../README.md) para infraestrutura e execução das cinco JVMs.

Flyway usa `src/main/resources/db/migration/` de cada serviço. Conta, custódia e
liquidação possuem migrações de negócio e testes com PostgreSQL via Testcontainers.
Ordens tem migrações de catálogo, ordens e idempotência. Apenas o orquestrador
ainda não tem migrações de negócio.
Não usar migrações vazias, `ddl-auto=create/update`, H2 ou `baseline-on-migrate`
para mascarar diferenças de esquema.

## Validar as capacidades locais

Com JDK 17 e Docker disponíveis, `./mvnw clean verify` executa 215 testes
(35 ordens, 53 conta, 81 custódia e 46 liquidação) e constrói os cinco JARs.
Para focar na liquidação: `./mvnw -pl settlement-service -am test`.
Pare as JVMs antes de reempacotar os JARs utilizados por elas.

`SettlementService.confirm` recebe snapshots das duas reservas bem-sucedidas,
operationId estável, preço capturado e versão do catálogo. Repetições iguais
retornam a decisão original; dados divergentes geram conflito. Consultas por
operação, ordem e Saga permitem recuperar a decisão sem efeitos adicionais.
O [contrato detalhado](../docs/decisions/0005-local-settlement.md) explica a
fronteira de confiança e por que ausência/timeout isolado não autoriza compensação.

Para testar apenas ordens: `./mvnw -pl order-service -am test`. A API e o perfil
opcional demo estão descritos no README principal e na decisão 0006.
