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
