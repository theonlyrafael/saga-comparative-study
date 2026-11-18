# 0006 — API local de ordens

POST /api/ordens recebe clienteId, ativoId e quantidade inteira positiva, com
Idempotency-Key obrigatória (até 128 caracteres, sem espaços nas extremidades).
A chave é opaca, sensível a maiúsculas e única por cliente. Mesmo conteúdo retorna
202 com a ordem original e Location; divergência retorna 409. Identificadores
textuais não são normalizados silenciosamente. Cliente tem até 64 caracteres;
ativo até 128. Campos desconhecidos e números fracionários são rejeitados.

GET /api/ordens/{orderId} retorna 200 ou 404. O preço e a versão do catálogo são
capturados na transação de criação; retries não consultam novamente o catálogo.
Valores monetários positivos usam NUMERIC(19,2) e BigDecimal sem arredondamento.
Uma ordem permanece RECEBIDA: não existe execução automática da Saga nesta etapa.

Uma tabela de idempotência reclama a chave com INSERT ON CONFLICT DO NOTHING.
A FK para a ordem é diferida até o commit, permitindo criar ambos atomicamente.
A segunda consulta em READ COMMITTED observa o vencedor concorrente. Falhas
revertem chave e ordem; não há expiração de chaves neste MVP. Banco próprio,
sem acesso a conta/custódia, sem alegar que recursos estejam reservados.

Catálogo inicial é opcional e ativado somente pelo perfil demo. A versão deve
mudar ao publicar novos preços; não se alteram migrações aplicadas. Seeds de
saldo/estoque serão feitos com os cenários de integração. Desde a migração V4, a criação de cada nova ordem grava a chave HTTP, a ordem e
o envelope v1 de OrdemRecebida em uma transação de orders_db. O POST retorna
202 após o commit. Uma falha de inserção na Outbox reverte também a ordem e a chave.
O replay utiliza o registro original. A migração preserva ordens anteriores a V4
sem gerar eventos retroativos, inclusive em reenvios HTTP.

O futuro publicador deverá ler o envelope persistido, preservando messageId,
operationId, sagaId, orderId, occurredAt e o snapshot do catálogo. published_at
nulo indica publicação pendente. O índice parcial cobre as pendências por data
e identificador. Para inspecionar as últimas dez no banco de ordens:

```sql
SELECT order_id, message_id, envelope, published_at
FROM outbox_messages
WHERE published_at IS NULL
ORDER BY created_at DESC, message_id DESC
LIMIT 10;
```

O seed demo é uma migração repetível, sem sobrescrever ativos existentes. Isso
não bloqueia futuras migrações numeradas. Mantenha o perfil demo nos bancos
inicializados com ele para que Flyway continue encontrando o histórico do seed.
