# 0004 — Operações locais de reserva de ativos

O custody-service mantém posições identificadas por UUID, com unicidade por
proprietário e ativo. Não existe uma classe PositionId. Identificadores de negócio
são strings; as quantidades agregadas usam long/BIGINT e cada comando aceita uma
quantidade int/INTEGER positiva.

## Contraparte e origem

A contraparte das novas reservas é configurada por `custody.counterparty-id`,
obtida da variável de ambiente `CUSTODY_COUNTERPARTY_ID` (padrão local:
`COUNTERPARTY`). Comprador e contraparte devem ser diferentes. A configuração não
cria posições nem adiciona estoque: os dados iniciais pertencem às sementes dos
cenários. Uma posição da contraparte ausente é uma recusa de negócio.

Cada reserva persiste a posição original, o ativo, o comprador, a quantidade e
os identificadores de ordem, Saga e operação. Confirmação e liberação consultam
a origem persistida, mesmo se a configuração da contraparte tiver mudado.

## Operações e transações

- Reservar reduz a quantidade disponível e aumenta a reservada da contraparte,
  registrando a reserva no mesmo commit.
- Liberar devolve exatamente a quantidade da reserva original ao disponível,
  sem alterar outras reservas nem criar uma posição do comprador.
- Confirmar consome a quantidade reservada e credita o comprador na mesma
  transação local. A posição do comprador é criada quando necessário, ou recebe
  um acréscimo preservando sua identidade e sua quantidade já reservada.

O serviço serializa alterações pela posição de origem, com bloqueio pessimista
em READ COMMITTED. As movimentações usam SQL condicional; o crédito usa UPSERT
pela chave única de proprietário/ativo. O bloqueio da origem precede as alterações
da reserva e da posição do comprador. Falhas técnicas, inclusive overflow e
falhas ao persistir o estado final, provocam rollback de toda a operação.
Deadlocks permanecem falhas técnicas recuperáveis, não recusas de estoque.

## Idempotência e conflitos

A mesma operationId e o mesmo conteúdo retornam a reserva original, inclusive
após confirmação ou liberação. Conteúdo divergente é conflito. Ordem, Saga e
operação têm unicidade independente. Colisões concorrentes entre ativos distintos
são resolvidas pelas constraints; somente essas violações específicas de unicidade
são traduzidas para conflito de negócio, com rollback.

Confirmar ou liberar novamente no mesmo estado terminal não repete movimentações.
Confirmar uma reserva liberada ou liberar uma reserva confirmada é conflito.
O domínio usa RESERVED, CONFIRMED e RELEASED; os contratos externos continuam
preservando os nomes definidos no PDF e serão mapeados pela camada de mensagens.

## Limites

O orquestrador deverá autorizar a reserva de ativos somente após a reserva de
saldo compatível. A custódia não consulta o banco de conta. A decisão de confirmar
após a liquidação durável ou compensar antes dela também pertence à coordenação.

Esta entrega não inclui API, RabbitMQ funcional, Outbox/Inbox ou coordenação.
Recusas sem reserva criada ainda não possuem resultado durável para reprodução;
essa garantia será acrescentada com a Inbox.

## Verificação

`AssetReservationTest` verifica invariantes e transições do domínio.
`AssetReservationServiceIntegrationTest` usa PostgreSQL real via Testcontainers
para verificar migrações, JPA, estoque insuficiente, idempotência, unicidade,
rollback e concorrência. Dados de teste ficam no contêiner descartável.
