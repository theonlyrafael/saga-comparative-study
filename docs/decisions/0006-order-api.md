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
saldo/estoque serão feitos com os cenários de integração. Outbox/Inbox e eventos
permanecem pendentes; antes de habilitar Saga, ordem e evento de início deverão
ser gravados no mesmo commit.
