# Modalidade orquestrada

| Módulo | Responsabilidade futura | Porta |
| --- | --- | --- |
| order-service | Entrada HTTP, catálogo, preço capturado e estado da ordem | 8081 |
| account-service | Reserva, liberação e consumo de saldo | 8082 |
| custody-service | Reserva, liberação e efetivação de ativos | 8083 |
| settlement-service | Decisão durável de liquidação | 8084 |
| orchestrator-service | Coordenação técnica, recuperação e compensações | 8085 |

Esta etapa prepara apenas as aplicações e a infraestrutura. Não há API de ordens,
entidades, consumidores, Outbox, compensações ou máquina de estados implementados.
Pacotes: `io.github.theonlyrafael.sagastudy.orchestrated.<order|account|custody|settlement|orchestrator>`.
O futuro contrato HTTP mantém `/api/ordens`, `clienteId`, `ativoId`, `quantidade`
e os estados e tipos de mensagens do PDF; inglês interno não altera o contrato.

## Construção e execução

Execute `./mvnw clean verify` neste diretório com JDK 21. O parent gerencia as
versões; cada módulo gera um JAR executável em `target/`. Consulte o
[README principal](../README.md) para infraestrutura e execução das cinco JVMs.

Flyway usa `src/main/resources/db/migration/` de cada serviço. Os `.gitkeep`
apenas preservam os diretórios. A primeira migração será uma alteração real de
esquema quando o domínio for implementado. O aviso de zero migrações é esperado
nesta etapa; Flyway pode criar sua própria tabela técnica de histórico.
Não usar migrações vazias, `ddl-auto=create/update`, H2 ou `baseline-on-migrate`
para mascarar diferenças de esquema.
