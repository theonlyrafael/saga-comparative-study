# Observabilidade da Saga orquestrada

## Executar localmente

Com Docker Compose em Linux, inicie Jaeger e Prometheus na raiz do projeto.
O Prometheus usa a rede do host para alcançar as cinco JVMs nas portas de
desenvolvimento (8081–8085).

```bash
docker compose -f infra/observability/compose.yaml up -d
export SAGA_TRACE_EXPORT=true
export SAGA_TRACE_SAMPLING=1.0
```

Inicie os serviços com essas variáveis conforme o [guia local](execucao-local.md).
Crie uma ordem e procure no Jaeger em `http://127.0.0.1:16686` pelo serviço
`orchestrator-service` e pela tag `saga.id` com o UUID do recibo.
O trace liga o POST às operações processadas nos participantes. As Outboxes
persistem o cabeçalho W3C `traceparent` junto ao envelope e o restauram na
publicação RabbitMQ. Reenvios preservam o contexto original e cada processamento
tem seu próprio span. Mensagens antigas sem contexto iniciam outro trace e
continuam correlacionáveis por Saga, ordem e operação nos logs.

`SAGA_TRACE_ENDPOINT` configura o destino OTLP/HTTP (padrão
`http://127.0.0.1:4318/v1/traces`). A exportação fica desativada por padrão.
A amostragem local pode ser ajustada entre 0 e 1. Use a mesma política nas
duas modalidades durante os experimentos. O Jaeger local usa armazenamento
em memória.

## Medidas por ordem

O coordenador expõe histogramas em `/actuator/prometheus`. Os identificadores
ficam nos traces e logs. O único rótulo de negócio dos histogramas é o
desfecho (`CONCLUIDA` ou `REJEITADA`).

| Família Prometheus | Início | Fim |
| --- | --- | --- |
| `saga_order_latency_seconds` | Criação da ordem, preservada em `OrdemRecebida.occurredAt` | Coordenador registra a confirmação final de ordens |
| `saga_compensation_duration_seconds` | Emissão do primeiro comando de liberação | Recepção da última confirmação de liberação |
| `saga_convergence_duration_seconds` | Primeira recusa registrada ou primeiro timeout de resposta observado pelo reconciliador, o que ocorrer antes | Coordenador registra a confirmação final de ordens |

A convergência usa as confirmações do protocolo como sinal de que os serviços
concluíram seus efeitos. Os ensaios também verificam os invariantes diretamente
nos bancos. Para medir desde o instante exato de uma injeção externa de falha,
o experimento deve registrar esse instante separadamente. A latência cruza
relógios de ordens e coordenação e requer relógios sincronizados.

Cada Saga terminal gera uma amostra por medida aplicável após o commit. Uma
entrega repetida mantém a amostra original. Uma recusa sem liberação não gera
duração de compensação. A view `saga_measurements` no banco do orquestrador
preserva as medições por ordem para exportação, inclusive após reinício. Campos
indisponíveis em históricos anteriores ficam nulos. Os contadores em memória
reiniciam com a JVM; a view é a fonte por ordem inclusive se o processo cair
entre commit e atualização do histograma.

No Prometheus em `http://127.0.0.1:9090`, por exemplo, a consulta abaixo
estima o p95 das ordens por desfecho na janela dos últimos cinco minutos.

```promql
histogram_quantile(0.95, sum by (le, outcome) (rate(saga_order_latency_seconds_bucket[5m])))
```

## Reproduzir a verificação

Com os JARs construídos, `infra/.env` preparado e o Jaeger iniciado, execute:

```bash
SAGA_TRACE_EXPORT=true SAGA_TRACE_SAMPLING=1.0 \
SAGA_TRACE_QUERY_URL=http://127.0.0.1:16686 bash infra/scripts/check-recovery.sh
```

O ensaio usa bancos, broker e portas de serviço próprios. Ele consulta as
amostras SQL, os histogramas expostos e os spans da mesma Saga nos cinco
serviços para uma liquidação negada e uma compra com reinício e resposta
perdida. As medições desse ensaio servem à validação funcional. A definição
das janelas de coleta e a calibração de carga pertencem ao piloto.
