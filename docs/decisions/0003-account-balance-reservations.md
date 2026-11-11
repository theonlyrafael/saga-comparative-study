# 0003 — Operações locais de reserva de saldo

As operações desta entrega pertencem exclusivamente ao account-service.

## Valores e identificação

- accountId identifica a conta local do comprador.
- Valores monetários usam BigDecimal e NUMERIC(19,2).
- Valores devem ser positivos e não podem conter frações de centavo.
- orderId, sagaId e operationId são obrigatórios na criação da reserva.
- Cada ordem e cada Saga podem possuir apenas uma reserva de saldo.

## Operações

Reservar:

- exige saldo disponível suficiente;
- reduz o saldo disponível e aumenta o reservado;
- registra a reserva na mesma transação.

Confirmar:

- reduz o saldo reservado e aumenta o total consumido;
- não reduz novamente o saldo disponível.

Liberar:

- reduz o saldo reservado e devolve o valor ao saldo disponível;
- utiliza o valor da reserva original.

## Estados

| Estado interno | Estado correspondente na especificação |
| --- | --- |
| RESERVED | SALDO_RESERVADO |
| CONFIRMED | CONFIRMADO |
| RELEASED | RESERVA_DESFEITA |

Os estados internos não devem ser serializados diretamente como contratos externos.
A futura camada de mensagens deve preservar os nomes definidos na especificação.

## Idempotência local

Repetir a criação com o mesmo operationId e os mesmos dados retorna o mesmo
identificador de reserva, inclusive quando ela já estiver em estado terminal.

Mesmo operationId com dados diferentes é conflito.

Repetir confirmação ou liberação no estado correspondente não repete efeitos.
Confirmar uma reserva liberada ou liberar uma reserva confirmada é conflito.

Recusas sem reserva criada ainda não são armazenadas como resultados de operações.
A reprodução durável de respostas, inclusive recusas, será tratada pela Inbox.

## Transações e concorrência

Operações sobre a mesma conta são serializadas por bloqueio pessimista da conta.
As alterações dos saldos também usam UPDATE condicional.

A ordem de acesso é conta antes de alterar reserva.
A transação mantém conjuntamente saldo e reserva consistentes.

Falhas de infraestrutura não são convertidas em recusas de saldo.

## Limites

Não há API HTTP, consumidor RabbitMQ, Outbox ou Inbox nesta entrega.

A autorização de compensar antes da decisão de liquidação pertence à coordenação
da Saga. O account-service impede transições incompatíveis com o estado local,
mas não consulta o banco do serviço de liquidação.
