# 0005 — Decisão local e durável de liquidação

A liquidação registra o compromisso previsto nas seções 3, 5–10 do PDF.
Existe uma decisão CONFIRMED (CONFIRMADA no contrato externo) ou não existe
registro. Não há estado REJECTED, cancelamento nem método para apagar/reverter
uma liquidação. A existência da decisão não significa que a ordem já esteja
LIQUIDADA: as efetivações de saldo e ativos e a finalização da ordem ainda faltam.

## Entrada e fronteira de confiança

`ConfirmSettlementCommand` recebe operationId estável, `ReservedBalance`,
`ReservedAsset`, preço capturado e versão do catálogo original. Os dois snapshots
representam respostas bem-sucedidas das reservas, não suas efetivações posteriores.
Cada um contém reservationId, orderId, sagaId e buyerId; saldo inclui amount,
e ativo inclui assetId e quantity. Não há chamada aos bancos de conta/custódia.

O comando exige mesma ordem, Saga e comprador, quantidade positiva e saldo
exatamente igual a preço × quantidade. Valores usam BigDecimal/NUMERIC(19,2),
sem arredondamento nem overflow aceito. Escalas equivalentes são normalizadas.
O comprador cabe nos 64 caracteres do contrato atual da conta; ativo e versão
do catálogo são identificadores textuais não vazios. Identificadores de reserva
pertencem a serviços distintos, sem impor que seus UUIDs sejam diferentes.

Esses dados são declarações da futura coordenação confiável. Construir o comando
não prova que reservas remotas existam ou ainda estejam ativas. O consumidor
futuro deverá aceitar apenas mensagens internas válidas, correlacionadas à ordem
e às duas reservas bem-sucedidas, preservando preço/versão e impedindo confirmação
simultânea a compensação. Nenhuma API pública de liquidação foi acrescentada.

## Persistência, idempotência e concorrência

`SettlementService.confirm` executa em READ COMMITTED. O repositório faz
`INSERT ... ON CONFLICT DO NOTHING` e uma segunda consulta por operationId.
Índices UNIQUE de ordem, Saga, operação e das duas reservas impedem duas decisões
ou reutilização dos recursos. A segunda instrução vê o vencedor já commitado
quando uma inserção concorrente precisou aguardar a resolução da unicidade.

Reenvio com todos os dados iguais retorna o mesmo settlementId, payload e
confirmedAt. Mudança em qualquer campo, inclusive versão de catálogo ou IDs das
reservas, gera `SettlementConflictException`. O timestamp é produzido pelo banco,
sem renovação em retries. Mesmo orderId com outro operationId é conflito: o ID
da operação deve permanecer estável. Falhas técnicas não são traduzidas para
recusas de negócio, e a transação não deixa decisões parciais.

Foi usado JdbcTemplate para expressar a inserção PostgreSQL e mapear uma projeção
imutável (`Settlement`), sem entidade JPA mutável ou operações de save/delete
que permitam reverter a decisão. O banco desse serviço continua exclusivo e
Flyway cria seu esquema. Hibernate não valida a tabela JDBC; sua migração,
constraints e mapeamento são exercitados pelos testes com PostgreSQL real.
Não há consultas a dados de outros serviços nem dependência Maven deles.

## Recuperação e limites

Consultas por operationId, orderId e sagaId recuperam a decisão persistida, sem
cache de resultados no processo. Um resultado ausente é uma observação naquele
instante, não prova de que um comando em trânsito não poderá confirmar depois.
Timeout, erro técnico ou consulta vazia isolada **não autorizam compensação**.
O protocolo futuro deverá excluir confirmações ainda possíveis antes de compensar.
Após CONFIRMED, deve concluir as efetivações, nunca liberar automaticamente reservas.

Outbox/Inbox, consumidores, autenticação das mensagens, decisão de compensar,
recuperação pós-crash do processo completo e publicação de LiquidacaoConcluida
não pertencem a esta entrega. Antes de ativar mensageria, a gravação da Outbox
precisa participar da mesma transação da decisão. Falhas/recusas sem decisão
não têm resultado persistido para reprodução nesta etapa.

## Revisão e testes

As responsabilidades separam validação de entrada, transação e SQL; os snapshots
são imutáveis, as dependências entram pelos construtores e o repositório expõe
somente inserir/consultar. Não há interface de serviço ou hierarquia sem uso real.
Isso favorece código limpo e os princípios de responsabilidade única e interfaces
restritas, sem alegar que uma suíte de testes prove todos os princípios SOLID.

Liquidação usa número constante de instruções por comando e consultas por índices
únicos (custo de busca tipicamente logarítmico no volume). Não há varredura de
liquidações, lock global, polling ou retry infinito no serviço. Operações que
competem pelas mesmas chaves podem esperar; ordens independentes podem avançar.
Conta serializa por conta e custódia por posição de origem; esses pontos quentes,
os índices e o pool de conexão limitam vazão. Deadlocks e indisponibilidade
continuam falhas técnicas recuperáveis. Testes concorrentes pequenos não são
benchmark nem prova de escalabilidade: carga, latência e tamanho dos pools
continuam dependentes de piloto.

A suíte testa validação, persistência, todas as chaves únicas, payload divergente,
replay, concorrência, rollback e falha técnica injetada. A recuperação usa uma nova
instância do serviço na mesma JVM; não simula reinício do processo ou entrega de
mensagem perdida. As suítes de saldo e custódia continuam cobrindo movimentações,
transições, rollback e disputa por recursos, sem provar a Saga distribuída.
