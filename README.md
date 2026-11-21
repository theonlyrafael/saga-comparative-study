# saga-comparative-study

Estudo comparativo de Sagas para a compra simulada de um ativo. O desenvolvimento está na modalidade orquestrada. Hoje, criar uma ordem gera o evento `OrdemRecebida` na fila do orquestrador. A ordem permanece `RECEBIDA` enquanto aguarda o processamento pelo futuro consumidor.

## Progresso da Saga orquestrada

**Base e operações locais**

- [x] Preparar o monorepositório e as cinco aplicações Spring Boot.
- [x] Configurar bancos PostgreSQL isolados, RabbitMQ, migrações, saúde e métricas.
- [x] Reservar, consumir e liberar saldo com idempotência.
- [x] Reservar e liberar ativos e efetivar sua transferência ao comprador.
- [x] Registrar e consultar a decisão durável de liquidação.
- [x] Criar e consultar ordens com catálogo versionado, preço capturado e idempotência HTTP.

**Mensageria de ordens · 6 de 6 etapas concluídas**

- [x] 1. Definir o contrato v1 dos comandos e respostas.
- [x] 2. Configurar exchanges, filas, rotas e filas de erro no RabbitMQ.
- [x] 3. Gravar a ordem e o evento na Outbox na mesma transação.
- [x] 4. Publicar o evento persistente e registrar a confirmação do broker.
- [x] 5. Reenviar pendências após indisponibilidade do broker ou ausência de rota.
- [x] 6. Validar o fluxo do POST até a fila, o reenvio HTTP e a recuperação. Registrar as evidências.

**Inbox dos participantes**

- [x] Criar índices únicos por consumidor, mensagem e operação em cada banco.
- [ ] Consumir comandos de saldo.
- [ ] Consumir comandos de custódia.
- [ ] Consumir comandos de liquidação.
- [ ] Validar concorrência, rollback e reentrega após commit.

**Próximas entregas**
- [ ] Versionar os dados de catálogo, saldo e estoque por cenário.
- [ ] Persistir o estado do orquestrador e integrar o fluxo da ordem até `LIQUIDADA`.
- [ ] Compensar reservas antes da liquidação e retomar efetivações pendentes após reinício.
- [ ] Completar tracing e métricas de convergência, compensação e latência.
- [ ] Executar os cenários C1–C6 e verificar os invariantes da Saga de ponta a ponta.
- [ ] Executar o piloto com carga e configurações reproduzíveis.

A modalidade coreografada está prevista para depois da validação da orquestrada.

[Histórico e resultados dos testes](docs/progress.md) · [Guia de execução local](docs/execucao-local.md) · [Especificação técnica](docs/specifications/orchestrated-saga-specification.pdf)
