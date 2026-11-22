# Progresso — Saga orquestrada

## Inbox dos participantes — 21/11/2026

[Versão validada da Inbox](https://github.com/theonlyrafael/saga-comparative-study/tree/fc8e9420e4)

Na branch `feat/orchestrated-inbox`, conta, custódia e liquidação consomem os sete comandos v1 dos participantes. Cada serviço grava a operação na Inbox, aplica a regra de negócio e guarda a resposta na Outbox em uma única transação PostgreSQL. A reentrega de uma operação com conteúdo equivalente recupera o envelope original, inclusive quando o resultado foi uma recusa por falta de saldo ou estoque.

O `clean verify` com JDK 17 executou **239 testes Java** (40 em ordens, 66 em conta, 85 em custódia e 48 em liquidação), todos aprovados. Passaram também os cinco testes Python de contrato e o teste das rotas dos 21 tipos v1 em um vhost temporário.

Com PostgreSQL 17.11 e RabbitMQ 4.4.0, a suíte de conta exercitou duas entregas concorrentes, falha no commit e fechamento do canal com o ACK pendente após um commit bem-sucedido. A reserva foi aplicada uma vez em cada cenário. Após a perda do ACK, o broker reentregou o comando e o consumidor republicou a resposta com o mesmo corpo e identificador. A suíte verificou também o encaminhamento de entrada inválida para DLQ.

Os testes de custódia cobrem reserva, confirmação, liberação e recusa durável. Em liquidação, um conflito com uma decisão confirmada segue para reconciliação via DLQ. O coordenador persistente, a autorização de compensações e o fluxo completo da ordem até um estado final seguem pendentes. Os passos para reproduzir a validação estão no [guia de execução local](execucao-local.md).

## Mensageria de ordens — 19/11/2026

Na branch `feat/orchestrated-messaging`, as seis etapas de mensageria de ordens
estão concluídas. O POST grava o envelope v1 na Outbox junto com a ordem. O
publicador entrega o envelope ao RabbitMQ e registra a confirmação no PostgreSQL.
A ordem permanece RECEBIDA até a implementação dos consumidores e do coordenador.

Validação da etapa 6 com JDK 17, PostgreSQL 17.11 e RabbitMQ 4.4.0:

- `clean verify` em `orquestrada/` construiu os cinco JARs e executou 220 testes
  Java (40 ordens, 53 saldo, 81 custódia, 46 liquidação), com zero falhas, erros ou ignorados.
- O teste integrado `replayOverHttpPreservesPublishedEvent` exercitou o POST
  real em porta aleatória, o envelope persistido e a entrega AMQP persistente
  em fila de teste. Repetir a mesma chave retornou a mesma resposta 202, com um
  único registro publicado e sem segunda mensagem observada na fila.
- A suíte exercitou também rota ausente e indisponibilidade real do broker.
  A recuperação reenvia o envelope original. Uma falha entre o ACK e o commit
  ainda pode duplicar a entrega. A Inbox será responsável por deduplicar efeitos.
- Separadamente passaram os cinco testes Python de contrato e o teste de roteamento
  dos 21 tipos num vhost temporário, incluindo rejeição para DLQ.

Para reproduzir, siga o [guia de execução](execucao-local.md), os [testes de
contrato](../compartilhado/README.md) e o [teste de rotas](../infra/README.md).
A próxima entrega é a Inbox com consumidores idempotentes.

## Entrega anterior — API local de ordens (16/11/2026)

Branch `feat/orchestrated-order-api`, baseada na dev `368f20c` após o merge da
liquidação. Implementa POST/GET, catálogo local versionado, captura de preço e
idempotência por cliente/chave. Contrato: [decisão 0006](decisions/0006-order-api.md).

Validação final do código `11d6007`: `clean verify` com Java 17, Maven Wrapper e
PostgreSQL real via Testcontainers. **210 testes aprovados**, zero falhas, erros
ou ignorados: 30 ordens, 53 saldo, 81 custódia, 46 liquidação. Cinco JARs construídos.
O teste HTTP inicia Spring Boot numa porta aleatória, ativa demo e exercita as
migrações, criação/consulta, erros, preço capturado, replay, isolamento por cliente,
concorrência e rollback de falhas de banco. Nenhum serviço remoto é simulado para
substituir PostgreSQL. Não houve benchmark nem reinício completo de processo.

A composição tem número constante de instruções por criação; chaves primárias e
índices únicos atendem às buscas. O índice composto cliente/chave serializa apenas
comandos conflitantes, sem lock global. Busca indexada é tipicamente O(log n),
mas espera por transações, I/O e tamanho do pool precisam de medição em piloto.
Validação, HTTP, transação e SQL têm responsabilidades separadas e injeção por
construtor; ainda há dependência de repositórios concretos, sem pretender adesão
absoluta a todas as interpretações de SOLID. Não foram criadas abstrações sem uso.

Limites: não há autenticação/autorização HTTP; a API é de desenvolvimento em
loopback. clienteId não é identidade autenticada. Antes de exposição externa,
será necessário vincular o cliente à identidade e restringir a consulta de ordens.
Ordens ficam RECEBIDA; não há Outbox, publicação de início nem execução da Saga.
Chaves bem-sucedidas não expiram; falhas que revertem a transação não reservam a
chave. Limpeza/retenção exigirá política que preserve idempotência. Dados de demo
são opcionais; seeds de saldo/estoque permanecem para os cenários integrados.

Próximo passo: contratos assíncronos, Outbox/Inbox e mensageria. Esta branch pode
seguir em PR para dev; excluir somente após o merge. Não foi realizado merge.
As seções seguintes preservam o histórico das entregas anteriores.

## Estado atual — liquidação local (16/11/2026)

Branch: `feat/orchestrated-settlement`, criada a partir de `origin/dev`
`888037060287110019ff40824238d08c5fd174b5`. O checkout estava limpo.
O histórico confirma saldo e custódia na dev (PRs #2/#3), e a consolidação na
main pelo PR #4 (`22c200d`). O PDF anexado foi comparado byte a byte por SHA-256
com o versionado: ambos `4c26d88863399196aa33355798e8fcb2af1beb82130103b01054aadbc36c0d6b`.
O guia de preparação permanece histórico; a autorização atual permite implementação,
Java 17/Boot 4.1.0 e o fluxo feature → dev → main são as decisões posteriores.

### Entregue

- Snapshots das reservas, comando com correlação e valores compatíveis.
- Migração de settlements, unicidade e decisão CONFIRMED sem reversão na interface.
- Inserção atômica, replay que preserva identificador/timestamp e consultas por
  operação, ordem e Saga. JDBC explícito e resultado imutável, sem banco compartilhado.
- Testes de conflitos em cada campo e chave única, concorrência, rollback,
  falha técnica e recuperação de resultado por outra instância do serviço.
- Revisão das capacidades anteriores: dois defeitos reais de saldo foram
  reproduzidos antes da correção. Replay divergente com conta inexistente retornava
  ACCOUNT_NOT_FOUND; agora retorna OPERATION_CONFLICT. As primitivas SQL aceitavam
  zero/negativos; agora exigem valor positivo e transação do chamador.
- Commits incrementais de implementação e testes, incluindo duas etapas do comando
  e do serviço. Nenhum defeito foi introduzido deliberadamente para gerar commits fix.

Contrato e avaliação de responsabilidades, consultas e contenção:
[decisão 0005](decisions/0005-local-settlement.md).

### Evidências executadas

A baseline anterior à implementação passou com 126 testes. A validação final
`clean verify` passou no código de `09b391b` (commits seguintes são documentação),
com JDK 17.0.7, Maven Wrapper 3.10.0 e PostgreSQL 17.11 via Testcontainers:

| Componente | Testes | Falhas / erros / ignorados |
| --- | ---: | --- |
| Conta | 53 | 0 / 0 / 0 |
| Custódia | 81 | 0 / 0 / 0 |
| Liquidação | 46 | 0 / 0 / 0 |
| Total | **180** | **0 / 0 / 0** |

Ordens e orquestrador não têm testes de domínio; seus JARs foram construídos.
No ambiente de nuvem, dentro de `orquestrada/`, foi usado:

```bash
JAVA_HOME=/workspace/tools/jdk-17.0.7+7 \
MAVEN_USER_HOME=/workspace/cache/maven-wrapper \
MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
./mvnw -o -B -ntp -s /workspace/tools/maven-settings.xml clean verify
```

Modo offline depende do cache previamente instalado. Fora desta nuvem, usar o
Wrapper com JDK 17 e Docker e omitir os caminhos locais. Relatórios Surefire ficam
nos `target/surefire-reports` dos três módulos. Não foram adicionados segredos ao Git.

Após o build, os cinco serviços foram iniciados: saúde/readiness UP, liveness e
Prometheus responderam nas portas 8081–8085. O isolamento confirmou cinco acessos
próprios e vinte recusas cruzadas, preservando volumes. A aplicação de liquidação
iniciou com sua migração V1. Essas verificações não demonstram Saga fim a fim.

### Limites e continuação

Liquidação local está completa para este escopo. Não confirma estado remoto das
reservas nem entrega mensagens. A recuperação testada é outra instância na mesma
JVM, não crash/reinício completo. Ausência de decisão em consulta isolada não
exclui confirmação futura de um comando em trânsito e não autoriza compensar.
Continuam pendentes API/catálogo/seeds, Outbox/Inbox, mensageria, motor da Saga,
recuperação distribuída, tracing e testes C1–C6. Escalabilidade exige piloto;
concorrência funcional não equivale a medição de capacidade.

Próximo PR: feature atual → `dev`, com título sugerido
**Implementa decisão local durável de liquidação na Saga orquestrada**.
Depois de revisar e integrar o PR, posso excluir esta branch. Manter `dev` e
`main`. Para a próxima etapa, criar `feat/orchestrated-order-api` a partir da dev
atualizada, preservando preço/versão do catálogo e idempotência HTTP. Usar a branch
atual somente para ajustes desta revisão, sem acumular outra capacidade nela.

---

## Histórico do bootstrap

Atualizado em 29/10/2026. Branch de trabalho: `chore/orchestrated-bootstrap`.
Base: `origin/main` atualizada no início, commit
`d3de6f99fde99dc76ec2445c73afc16e03d9a764`. O origin foi confirmado como
`theonlyrafael/saga-comparative-study`, a branch padrão como `main`, e o checkout
estava limpo. Não havia preparação em outras branches remotas; a branch local
`work` apontava para o mesmo commit inicial e foi preservada.

## Entrega

- Seis diretórios principais documentados; coreografada permanece apenas como escopo futuro.
- Guia preservado e PDF original copiado sem alterações; SHA-256 registrado em `environment.md`.
- Parent/agregador em `orquestrada/`, Wrapper único e cinco JARs Spring Boot independentes.
- Apenas cinco classes Java de inicialização, em inglês e nos pacotes do guia.
- PostgreSQL com cinco bancos/usuários isolados; RabbitMQ com vhost local; volumes, healthchecks e imagens fixadas por digest.
- JPA com `ddl-auto=validate`, Flyway com suporte PostgreSQL, sem H2 ou SQL de negócio.
- Conexões AMQP, health/liveness/readiness, métricas Prometheus e logs JSON ECS.
- Scripts para iniciar cada serviço, verificar saúde e testar isolamento dos bancos.
- README com comandos de construção, início, verificação, encerramento e retomada.

[Versões, fontes oficiais e checksums](environment.md). A comparação somente leitura
com o protótipo está em [prototype-comparison.md](prototype-comparison.md).

## Verificações do bootstrap original (Java 21 / Boot 4.1.2)

| Verificação | Resultado observado |
| --- | --- |
| `./mvnw clean verify` após criar módulos | Sucesso nos cinco serviços e no agregador; cinco JARs executáveis |
| `./mvnw clean verify` após adicionar persistência/mensageria | Sucesso nos seis projetos do reactor, sem pular fases por flags |
| Testes Java executados pelo Maven | **Zero**: não há suíte Java nesta etapa; build não é evidência de testes de domínio |
| JARs finais | Todos contêm PostgreSQL, Flyway e AMQP; nenhum contém H2 |
| `docker compose ... config --quiet` | Configuração válida |
| `docker compose ... up -d --wait` | PostgreSQL e RabbitMQ saudáveis |
| Inicialização das cinco JVMs | Sucesso com Temurin 21.0.13+9; portas 8081–8085 |
| Health, liveness, readiness e Prometheus | 20 requisições HTTP bem-sucedidas (quatro por aplicação); status de saúde UP |
| Conectividade RabbitMQ | Cinco conexões autenticadas, usuário local, vhost `saga`, estado `running` |
| Flyway | Cinco bancos acessados; zero migrações aplicadas; apenas `flyway_schema_history` no schema public de cada banco |
| Acesso ao próprio banco | Cinco conexões TCP autenticadas com usuário/banco esperado; DDL/DML transacional revertido |
| Isolamento entre bancos | Vinte conexões cruzadas recusadas especificamente por permissão de banco |
| Senhas inválidas/privilégios | Cinco senhas inválidas rejeitadas; nenhum usuário de aplicação com superuser, createdb, createrole, replication ou bypassrls |
| RabbitMQ parado | Cinco readiness HTTP 503/DOWN; liveness HTTP 200/UP; recuperação após reinício |
| PostgreSQL parado | Cinco readiness HTTP 503/DOWN; liveness HTTP 200/UP; recuperação após reinício |
| Ciclo completo de encerramento/retomada | Cinco JVMs reiniciadas após `down` e `up`; mesmo identificador do cluster PostgreSQL, histórico Flyway e isolamento preservados |
| API de ordens | HTTP 404, esperado porque não foi implementada |
| Integridade do PDF | Conteúdo idêntico ao anexo por SHA-256 |
| Revisão Git | Diff conferido antes dos commits; `.env`, logs e targets ignorados; nenhuma senha local nos arquivos versionados |

O ciclo final encerrou as JVMs e os contêineres criados pela tarefa, preservando os
volumes e o `.env` local. Para trabalhar novamente, use os comandos do README.
A verificação de persistência cobre cluster, usuários e histórico técnico; não
existe dado de negócio ou mensagem de Saga para validar persistência funcional.

## Problemas encontrados e resolvidos

A imagem da nuvem tinha runtime Java 21 sem compilador; foi instalado o Temurin
JDK completo. A confiança TLS do novo JDK foi configurada com o truststore da
plataforma, mantendo verificação de certificados. O Maven recebeu configuração de
proxy somente fora do repositório. O acesso Git e Docker exigiu execução autorizada
fora das restrições do sandbox; nenhum segredo adicional foi solicitado.

A primeira inicialização PostgreSQL não leu o script devido às permissões locais;
o script passou a ser executável/legível e o volume novo, ainda sem bancos de
aplicação, foi recriado. A inicialização limpa e os reinícios posteriores passaram.
Não houve descarte de dados ou alterações de trabalho preexistentes.

O aviso do Flyway sobre ausência de migrações é esperado: os diretórios foram
preparados sem migração vazia. As configurações de confirmação de publicação AMQP
estão presentes, mas nenhum fluxo de publicação/consumo foi implementado ou testado.

## Limites e pendências

- Windows/Git Bash, Docker Desktop e `mvnw.cmd` não foram executados neste Linux. A configuração atual usa o JDK 17.0.7 que eu já tinha instalado. O protótipo pode continuar nessa versão.
- API, entidades, contratos implementados, migrações de negócio, consumidores, Outbox/inbox, compensações e máquina de estados permanecem fora do escopo concluído.
- Testes de domínio, idempotência, concorrência, recuperação de Saga e cenários C1–C6 não foram executados porque dependem dessas implementações e do protocolo-base ainda não fornecido.
- OpenTelemetry, exportação para coletor e correlação por sagaId/orderId/operationId serão integrados com os fluxos; métricas e logs estruturados atuais não comprovam tracing distribuído.
- Uma instância PostgreSQL compartilhada isola acesso, mas não falhas entre bancos. A topologia dos experimentos exige decisão específica antes do piloto.
- Não há merge na branch padrão. Após o push, abrirei o PR manualmente. Posso excluir a branch depois do merge.

## Próximo passo

Definir e versionar os contratos conforme o PDF, criar as primeiras migrações reais
e as entidades/constraints correspondentes, com testes de reservas e concorrência
em PostgreSQL. Depois implementar a API de ordens e os mecanismos assíncronos na
sequência documentada. Não tratar este bootstrap como uma Saga funcional.

A entrega inicial foi organizada em cinco commits com prefixos convencionais e assunto/
descrição em português. Consulte os hashes definitivos com:

```bash
git log --reverse --format='%h %s' dd33c0b^1..dd33c0b^2
```

## Ajuste posterior — Java 17 e Spring Boot 4.1.0

Defini no parent comum o uso de Java 17 (`release=17`) e Spring
Boot 4.1.0, alinhado ao protótipo e ao JDK 17.0.7 já instalado no computador.
O guia anexado e o PDF foram preservados; esta decisão substitui a escolha Java 21
na configuração executável e na documentação de uso.

Validação desta alteração, em Linux com Temurin **17.0.7+7**:

- `./mvnw clean verify`: sucesso no agregador e nos cinco serviços; zero testes Java, pois ainda não há suíte de domínio.
- Cinco aplicações iniciadas com Java 17.0.7 e conectadas ao PostgreSQL e RabbitMQ.
- Saúde, liveness, readiness e Prometheus: vinte verificações HTTP aprovadas.
- Cinco conexões aos bancos próprios e vinte recusas de acesso cruzado confirmadas; schemas com apenas o histórico técnico Flyway.
- Processos e contêineres encerrados após a validação, preservando volumes.

Os testes de indisponibilidade descritos acima pertencem ao bootstrap original;
não foram repetidos nesta alteração. Windows permanece sem execução neste ambiente.
Não é necessário instalar Java 21 nem instalar Spring Boot manualmente no PC.

## Entrega posterior — reservas locais de saldo

Atualizado em 05/11/2026, na branch `feat/orchestrated-account-reservations`.
O bootstrap acima já foi integrado à `main` pelo PR #1 (`dd33c0b`). A branch
`dev` foi criada a partir desse commit, sem integração desta entrega.

Implementados no `account-service`, conforme a decisão 0003:

- Comando validado, estados e exceção de negócio das reservas.
- Migração Flyway V1 de contas e reservas, com precisão monetária, constraints
  de saldo não negativo e unicidade independente por ordem, Saga e operação.
- Entidades encapsuladas e repositórios com operações restritas.
- Reserva, confirmação e liberação em transações locais; bloqueio por conta e
  atualizações condicionais, preservando saldo e reserva conjuntamente.
- Repetição da operação retorna a reserva original; repetição de uma conclusão
  não movimenta saldo novamente. Transições terminais incompatíveis são recusadas.
- Falhas de persistência propagam como falhas técnicas, com rollback.

Revisão: validação de entrada separada do domínio, transações no serviço de
aplicação e acesso ao banco em interfaces pequenas. Não foram adicionadas
interfaces de serviço sem necessidade nem compartilhadas entidades entre serviços.
Os pacotes existentes foram preservados; esta entrega não renomeia o bootstrap.

Validação em Java 17.0.7, Spring Boot 4.1.0 e PostgreSQL 17.11:

- Construção Maven dos cinco serviços aprovada.
- 45 testes aprovados, zero falhas, erros ou ignorados: 14 de validação do comando,
  9 da entidade e 22 de integração. Imagem PostgreSQL fixada por digest, igual à
  infraestrutura; Testcontainers usa banco descartável separado do Compose.
- Integração exercita migração e validação JPA, idempotência, saldo insuficiente,
  unicidade, rollback após falha de INSERT e concorrência entre reservas,
  confirmações, liberações e confirmação contra liberação.
- Os outros quatro serviços continuam sem testes Java nesta etapa.

Com Docker disponível, na pasta `orquestrada/`, executar
`./mvnw -pl account-service -am clean verify` para validar esta entrega.
Relatórios: `account-service/target/surefire-reports/`.

Ainda faltam reservas de ativos, liquidação, API de ordens, Outbox/Inbox,
mensageria e coordenação/recuperação da Saga. Recusas sem reserva não possuem
resultado durável reapresentável; isso pertence à futura Inbox. A decisão de
compensar somente antes da liquidação durável pertence à coordenação, ainda não
implementada. Os testes atuais não validam uma Saga distribuída completa.

## Entrega posterior — reservas locais de ativos

Atualizado em 10/11/2026, na branch `feat/orchestrated-custody-reservations`.
Concluídas as operações locais do `custody-service`: reserva, liberação e
transferência ao comprador, com idempotência, transações e controle de concorrência.
A [decisão 0004](decisions/0004-asset-reservations.md) detalha o contrato.

A migração V2 adiciona `asset_reservations`, preservando a V1 já publicada.
Foram adicionados AssetReservation, seu repositório e AssetReservationService,
com testes de domínio e integração. A posição original fica registrada na reserva;
confirmar/liberar não depende do valor atual da configuração da contraparte.

Validação com Java 17.0.7 e Spring Boot 4.1.0:

- Construção e empacotamento dos cinco módulos aprovados.
- 126 testes aprovados, sem falhas, erros ou ignorados: 45 de conta e 81 de custódia.
- Custódia: 21 testes do comando, 22 da entidade e 38 de integração com PostgreSQL
  17.11 real, fixado por digest e executado em contêiner descartável.
- Cobertura de reserva integral, falta de estoque, origem ausente, transferência
  para posição nova/existente, repetição em estados terminais e conflitos.
- Rollback após falhas no INSERT, crédito ao comprador e gravação do estado final.
- Concorrência entre reservas, duplicatas, confirmação/liberação e colisões de
  identificadores entre ativos diferentes, sem dupla movimentação.
- Uso da origem persistida validado com uma instância de serviço configurada com
  outra contraparte; esse teste não simula reinício completo dos processos.

Para testar, em `orquestrada/`, executar
`./mvnw -pl custody-service -am clean verify` com Docker disponível.
Para configurar a contraparte ao iniciar localmente, na raiz do repositório:
`CUSTODY_COUNTERPARTY_ID=COUNTERPARTY bash infra/scripts/run-service.sh custody`.
A posição dessa contraparte deverá ser preparada pelos dados de cenário.

A etapa de reservas locais de saldo e ativos está concluída. Ainda faltam
liquidação, API de ordens/catálogo, mensagens, Outbox/Inbox e coordenação/recuperação.
A autorização de efetivar após a liquidação e a reprodução durável de recusas
não são garantias desta camada local. Ainda não existe uma Saga distribuída completa.
