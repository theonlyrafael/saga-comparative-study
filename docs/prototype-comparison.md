# Comparação com o protótipo

Consulta somente leitura em 29/10/2026 ao repositório
[theonlyrafael/saga-financial-transactions-api](https://github.com/theonlyrafael/saga-financial-transactions-api),
`main` em `de1bf1d62e9ba2df56fc1833bc2320cc2b5e6f01`. Nenhuma alteração ou push
foi feito nele. Foram lidos o POM agregador, o POM de conta, o README e as
configurações das três aplicações; classes de negócio não foram copiadas.

| Aspecto observado | Decisão para o estudo |
| --- | --- |
| Agregador Maven com conta, custódia e orquestrador | Manter multimódulo; incluir order e settlement, totalizando cinco aplicações |
| Módulos com parent Boot próprio | Parent/agregador comum da modalidade, herdando Boot; plugin executável apenas nos filhos |
| Java 17 e Boot 4.1.0 | Java 17 e Boot 4.1.0, alinhados ao protótipo por solicitação posterior do autor |
| H2 em memória, console H2 e Hibernate `update` | PostgreSQL persistente, Flyway e Hibernate `validate` |
| RabbitMQ por `docker run`, tag de série e usuário de exemplo | Compose, versão/digest fixos, volume, healthchecks e credenciais externas |
| Conta oferece entrada HTTP no protótipo | Entrada futura pertence a order-service; nenhuma API de negócio nesta tarefa |
| Portas 8081–8083 | Cinco portas 8081–8085 com responsabilidades do guia |
| Contrato `cpfCliente`/`valor` no README | Futuro contrato do PDF: `clienteId`, `ativoId`, `quantidade`, idempotência e resposta 202 |

Incluídos agora: suporte PostgreSQL do Flyway (além do starter do Boot 4), AMQP,
health/readiness/liveness, métricas Prometheus e logs JSON ECS. Não foram copiadas
filas, exchanges ou classes de coordenação do protótipo. Publicação, consumo,
Outbox, DLQ, idempotência e recuperação exigem o desenho específico do PDF.
OpenTelemetry e correlação por identificadores da Saga permanecem para a etapa
que implementar os fluxos; logs JSON e métricas não equivalem a tracing distribuído.
Testcontainers será avaliado com os testes integrados de domínio; o bootstrap
atual verifica os contêineres reais via scripts e não adiciona testes Java vazios.
