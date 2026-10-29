# Progresso — bootstrap orquestrado

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

- Windows/Git Bash, Docker Desktop e `mvnw.cmd` não foram executados neste Linux. A configuração atual usa o JDK 17.0.7 já instalado pelo autor; o protótipo não precisa mudar.
- API, entidades, contratos implementados, migrações de negócio, consumidores, Outbox/inbox, compensações e máquina de estados permanecem fora do escopo concluído.
- Testes de domínio, idempotência, concorrência, recuperação de Saga e cenários C1–C6 não foram executados porque dependem dessas implementações e do protocolo-base ainda não fornecido.
- OpenTelemetry, exportação para coletor e correlação por sagaId/orderId/operationId serão integrados com os fluxos; métricas e logs estruturados atuais não comprovam tracing distribuído.
- Uma instância PostgreSQL compartilhada isola acesso, mas não falhas entre bancos. A topologia dos experimentos exige decisão específica antes do piloto.
- Não há merge na branch padrão. O PR será aberto manualmente pelo autor após o push da branch; sua exclusão é recomendada somente após o merge.

## Próximo passo

Definir e versionar os contratos conforme o PDF, criar as primeiras migrações reais
e as entidades/constraints correspondentes, com testes de reservas e concorrência
em PostgreSQL. Depois implementar a API de ordens e os mecanismos assíncronos na
sequência documentada. Não tratar este bootstrap como uma Saga funcional.

A entrega inicial foi organizada em cinco commits com prefixos convencionais e assunto/
descrição em português. Consulte os hashes definitivos com:

```bash
git log --reverse --format='%h %s' d3de6f99fde99dc76ec2445c73afc16e03d9a764..chore/orchestrated-bootstrap
```

## Ajuste posterior — Java 17 e Spring Boot 4.1.0

A pedido do autor, o parent comum passou a usar Java 17 (`release=17`) e Spring
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
