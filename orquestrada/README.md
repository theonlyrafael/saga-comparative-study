# Modalidade orquestrada

| Módulo | Responsabilidade | Porta |
| --- | --- | --- |
| order-service | Entrada HTTP, catálogo, preço capturado e estado da ordem | 8081 |
| account-service | Reserva, liberação e consumo de saldo | 8082 |
| custody-service | Reserva, liberação e efetivação de ativos | 8083 |
| settlement-service | Decisão durável de liquidação | 8084 |
| orchestrator-service | Estado e etapas da Saga, correlação das respostas e emissão de comandos | 8085 |

O POST de ordens registra `OrdemRecebida` na Outbox. Conta, custódia e liquidação
consomem comandos v1 em suas filas e persistem as respostas junto aos efeitos
locais. O módulo `participant-messaging` reúne a Inbox e o publicador das Outboxes.
O orquestrador grava cada comando junto de sua etapa. Ao receber as duas
efetivações, envia `FinalizarOrdem`. A confirmação de ordens encerra a execução.

Pacotes: `io.github.theonlyrafael.sagastudy.orchestrated.<order|account|custody|settlement|orchestrator>`.
O contrato HTTP mantém `/api/ordens`, `clienteId`, `ativoId`, `quantidade`
e os estados e tipos de mensagens do PDF; inglês interno não altera o contrato.

## Construção e execução

Execute `./mvnw clean verify` neste diretório com JDK 17. O parent gerencia as
versões; cada um dos cinco serviços gera um JAR executável em `target/`. Consulte o
[guia de execução local](../docs/execucao-local.md) para infraestrutura e execução das cinco JVMs.

Flyway usa `src/main/resources/db/migration/` de cada serviço. Conta, custódia e
liquidação possuem migrações de negócio e testes com PostgreSQL via Testcontainers.
Ordens também persiste o desfecho e a Inbox de finalização. No banco do orquestrador,
`saga_instances` registra o estado e o snapshot. Cada linha de `saga_steps` guarda
a operação emitida, o tipo de comando e a resposta aceita. Inbox e Outbox participam
da mesma transação que a transição de estado.
Não usar migrações vazias, `ddl-auto=create/update`, H2 ou `baseline-on-migrate`
para mascarar diferenças de esquema.

## Validar as capacidades locais

Com JDK 17 e Docker disponíveis, `./mvnw clean verify` executa as suítes de ordens, conta, custódia, liquidação e orquestração e constrói os cinco JARs.
Para focar na liquidação: `./mvnw -pl settlement-service -am test`.
Pare as JVMs antes de reempacotar os JARs utilizados por elas.

`SettlementService.confirm` recebe snapshots das duas reservas bem-sucedidas,
operationId estável, preço capturado e versão do catálogo. Repetições iguais
retornam a decisão original; dados divergentes geram conflito. Consultas por
operação, ordem e Saga permitem recuperar a decisão sem efeitos adicionais.
O [contrato detalhado](../docs/decisions/0005-local-settlement.md) explica a
fronteira de confiança e por que ausência/timeout isolado não autoriza compensação.

Para testar apenas ordens: `./mvnw -pl order-service -am test`. A API e o perfil
opcional demo estão descritos no [guia de execução local](../docs/execucao-local.md) e na [decisão 0006](../docs/decisions/0006-order-api.md).


## Coordenação persistente

A recepção de `OrdemRecebida` registra a Saga e emite `ReservarSaldo` no mesmo
commit. O primeiro estado observável é `RESERVANDO_SALDO`. O coordenador usa o
snapshot de ordens em toda a execução. Cada comando tem um `operationId` estável
e um envelope persistido na Outbox para publicação em `saga.v1.commands`.

Para avançar, a resposta precisa corresponder à Saga, à ordem, à operação
pendente e aos campos enviados, incluindo os identificadores das reservas.
Uma entrega duplicada com o mesmo conteúdo preserva a transição já concluída.
As confirmações de saldo e ativo podem chegar em qualquer ordem. Um lock na
instância da Saga serializa o registro dessas respostas e a criação do comando
final. As consultas usam chaves indexadas com um número fixo de etapas por ordem.

## Compensação e recuperação

- [x] **Recusa de ativo.** Ao receber `AtivoRecusado`, o coordenador entra em `COMPENSANDO` e envia `DesfazerReservaSaldo` à conta. A resposta `ReservaSaldoDesfeita` deve identificar a mesma operação e reserva. Só então o coordenador envia `FinalizarOrdem` com o motivo original da Inbox. A Saga termina em `REJEITADA` ao receber `OrdemRejeitada`.
- [x] **Liquidação negada.** O serviço de liquidação grava a decisão antes de emitir `LiquidacaoFalhou`. O coordenador libera o ativo, aguarda `ReservaAtivoDesfeita`, libera o saldo e aguarda `ReservaSaldoDesfeita`. A rejeição preserva o motivo original.
- [x] **Operações com prazo expirado.** O reconciliador procura etapas pendentes por prazo em um índice parcial. O reenvio usa o envelope original. A Inbox do participante consulta a resposta por operação e a republica. Um comando que ainda aguarda publicação segue a agenda da Outbox.
- [x] **Reinício após liquidação confirmada.** Cada efetivação possui uma etapa persistente. Após reinício, o coordenador recupera a resposta que falta e só finaliza a compra depois que conta e custódia confirmam.

As migrações V2 e V3 do orquestrador retomam recusas definitivas de ativo e liquidação registradas pela versão anterior. A V4 inclui no índice de prazos as operações antigas que ainda aguardam resposta. Na liquidação, a V5 preserva as confirmações anteriores e reserva as chaves de ordem, Saga, operação e recursos para uma única decisão.

Com os cinco serviços ativos no cenário `demo-v1`, execute `bash infra/scripts/check-happy-path.sh` para verificar a compra liquidada e a compra sem estoque. Para reproduzir a recusa de liquidação e o reinício com perda de resposta, execute `bash infra/scripts/check-recovery.sh` na raiz. O segundo comando inicia seus próprios serviços. [Guia de execução local](../docs/execucao-local.md#verificar-a-compensação-e-o-reinício)

## Observabilidade

- [x] Correlacionar o POST e os consumidores RabbitMQ nos cinco serviços com OpenTelemetry.
- [x] Expor os histogramas de latência, compensação e convergência e preservar as medições por ordem em PostgreSQL.
- [x] Validar traces e medições nos ensaios de recusa e reinício.

[Executar Jaeger e Prometheus e consultar uma Saga](../docs/observabilidade.md)

## Cenários de falha

- [x] Retomar compras após queda, atraso ou interrupção da publicação.
- [x] Validar a disputa pela última unidade de um ativo e a recusa por saldo insuficiente.
- [x] Reentregar comandos sem duplicar transferências e concluir compensações interrompidas.
- [ ] Calibrar a carga e as janelas de medição no piloto.

Execute `bash infra/scripts/check-failure-scenarios.sh` na raiz para reproduzir C1–C6. Para verificar uma única falha, passe o identificador do cenário. [Condições de cada teste e evidência registrada](../docs/cenarios-de-falha.md)
