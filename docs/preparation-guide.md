**Guia de preparação do repositório saga-comparative-study**

Rafael Fraga · IC / UFU · 29 de outubro de 2026

Este guia prepara o ambiente e a organização do repositório para começar pela Saga orquestrada. A especificação técnica anexada, datada de 29/10/2026, é a referência de implementação. Os comandos abaixo são instruções para você executar no computador. Nenhum deles foi executado no seu GitHub ou no seu Windows durante a elaboração deste guia.

**Limite desta etapa:** instalar e verificar ferramentas, clonar o repositório, criar uma branch, preparar pastas, documentação e regras de versionamento. Não criar classes Java, testes Java, POMs, SQL, Dockerfiles ou Docker Compose nesta etapa. A geração pelo Spring Initializr fica descrita para o próximo passo, porque o botão Generate já produz código, mesmo sem regras de negócio.

**1. O que foi conferido e o que veio do seu relato**

Foi possível autenticar a conexão GitHub como `theonlyrafael` e consultar o repositório público `saga-financial-transactions-api`. Na branch padrão `main`, ele possui um POM agregador e os módulos `conta-service`, `custodia-service` e `orquestrador-service`. O POM do módulo de conta declara Java 17, Spring Boot 4.1.0, Spring Data JPA, AMQP e H2. Também há Maven Wrapper nos módulos.

Essa organização é compatível com projetos gerados pelo Spring Initializr. Os arquivos não comprovam por si só que você usou o site, mas os ZIPs de que se lembra provavelmente vieram de [start.spring.io](https://start.spring.io/).

O repositório `saga-comparative-study` retornou 404 para a conexão atual e não apareceu na busca pelo nome. Portanto, o estado inicial “README e .gitignore de Java” é o que você informou, não um conteúdo que eu tenha conseguido inspecionar. Isso não prova que o repositório não exista. Confira, na configuração da integração GitHub, se ele está entre os repositórios autorizados, especialmente se for privado ou recém-criado.

O protótipo serve como referência de organização Maven e ferramentas. As responsabilidades dos serviços, os contratos, a persistência e os critérios de recuperação do novo sistema vêm da especificação da IC.

**2. As decisões para este repositório**

| Item | Organização adotada neste guia |
| --- | --- |
| Repositório | Um monorepositório: `saga-comparative-study` |
| Modalidades | Pastas `orquestrada/` e `coreografada/` no mesmo histórico Git |
| Primeiro desenvolvimento | Modalidade orquestrada |
| Componentes da orquestrada | Quatro serviços de domínio e um orquestrador técnico |
| Construção futura | Maven multimódulo por modalidade |
| Idioma do código | Classes, interfaces, métodos, variáveis e pacotes em inglês |
| Idioma das pastas principais | Manter os nomes definidos na seção 11 da especificação |
| Persistência | PostgreSQL próprio por serviço, com credenciais e acesso separados |
| Mensageria | RabbitMQ |
| Migrações | Flyway, separadamente em cada serviço |
| Git | `main` e branches curtas por tarefa, integradas por pull request |

As duas modalidades devem coexistir no repositório. Uma branch é uma linha temporária de trabalho. Usar somente uma branch para cada modalidade dificultaria comparar os dois diretórios no mesmo commit.

O documento anexado especifica a modalidade orquestrada. Ele exige preservar as regras de negócio na futura coreografia, mas não detalha toda a coordenação coreografada. Por enquanto, `coreografada/` conterá apenas documentação de escopo e um marcador de diretório.

**3. Entenda as tecnologias antes de instalar**

| Tecnologia | Função no projeto |
| --- | --- |
| JDK | Compilar e executar aplicações Java |
| Spring Boot | Configuração e execução das aplicações, com dependências integradas |
| Maven | Resolver dependências, compilar, executar testes e empacotar os módulos |
| Maven Wrapper | Executar a versão de Maven fixada pelo projeto |
| PostgreSQL | Banco de dados relacional persistente |
| Jakarta Persistence / JPA | Especificação de mapeamento entre objetos Java e dados relacionais |
| Hibernate | Implementação de JPA normalmente utilizada com Spring Data JPA |
| Spring Data JPA | Facilitar acesso a dados e repositórios sobre JPA |
| Flyway | Aplicar alterações de esquema por migrações versionadas |
| RabbitMQ | Transportar comandos e respostas assíncronos entre aplicações |
| Docker e Compose | Executar e organizar a infraestrutura local em contêineres |
| Thunder Client | Enviar requisições HTTP durante o desenvolvimento |
| Actuator / Micrometer / OpenTelemetry | Saúde, métricas e rastreamento, a configurar na implementação |

**H2 e JPA são coisas diferentes.** H2 é um banco. JPA é uma especificação de persistência. O H2 pode operar em memória ou em arquivo. No seu protótipo, o README descreve o modo em memória. Usar JPA não obriga a usar H2 nem provoca perda de dados ao desligar a aplicação.

No projeto novo, podemos manter Spring Data JPA e Hibernate, usando PostgreSQL. A persistência também dependerá de volumes Docker e de não recriar o esquema ao iniciar. Para concorrência e recuperação, os testes integrados deverão usar PostgreSQL, pois H2 não reproduz todos os seus comportamentos.

**4. Ferramentas e versões de partida**

As versões abaixo são escolhas para a implementação, consultadas em fontes oficiais em 29/10/2026. A especificação exige versões estáveis fixadas, mas não determina esses números.

| Componente | Escolha | O que fazer agora |
| --- | --- | --- |
| Java | JDK 21 LTS, Eclipse Temurin | Instalar o JDK, incluindo compilador |
| Spring Boot | 4.1.2 | Registrar a escolha; será obtido pelo Maven na geração dos projetos |
| Maven | 3.10.0 | Instalar para preparar o ambiente; depois usar Wrapper fixado |
| PostgreSQL | 17.11 | Registrar; o servidor será executado por Docker na próxima etapa |
| RabbitMQ | 4.4.0 | Registrar; usar imagem com painel management na próxima etapa |
| Docker Desktop | Versão estável compatível com seu Windows | Instalar ou verificar a instalação existente |
| Git | Versão estável para Windows | Instalar com Git Bash |
| VS Code | Instalação existente atualizada | Preparar extensões |

Spring Boot 4.1.2 declara suporte a Java 17 ou superior e Maven 3.6.3 ou superior. Java 21 é uma escolha LTS compatível. Não é necessário voltar ao Boot 3 para este projeto. Maven 3.10.0 é a versão estável indicada pela página oficial consultada. PostgreSQL 17 continua suportado e a revisão consultada é 17.11. A página inicial oficial do RabbitMQ anuncia 4.4.0.

Na implementação, confirmar as tags de imagem disponíveis, fixar versões completas e registrar os digests usados nos experimentos. Evitar tags flutuantes como `latest` em uma execução que precise ser reproduzida. Registrar também a revisão exata do JDK retornada por `java -version`. Esta etapa não realizou um teste de integração dessas dependências.

Downloads e referências oficiais:

- [Git](https://git-scm.com/downloads)
- [Temurin JDK 21](https://adoptium.net/temurin/releases/?version=21)
- [Maven](https://maven.apache.org/download.cgi)
- [Requisitos do Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
- [Docker Desktop no Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Versões do PostgreSQL](https://www.postgresql.org/support/versioning/)
- [RabbitMQ](https://www.rabbitmq.com/)

**5. Preparar seu Windows e o VS Code**

Para o seu Windows 11, a proposta é executar Java e Maven no Windows e usar Docker Desktop com backend WSL 2 para PostgreSQL e RabbitMQ. Os comandos de arquivos e Git deste guia usam **Git Bash**, inclusive quando aberto no terminal do VS Code. Não misture a instalação Java do Windows com a da sua VM Ubuntu neste roteiro.

1. Instale Git for Windows e abra Git Bash.
2. Instale Temurin JDK 21, arquitetura x64 e pacote JDK. Configure `JAVA_HOME` para a pasta do JDK e inclua seu diretório `bin` no `PATH`, se o instalador não fizer isso.
3. Instale Maven 3.10.0 a partir do ZIP binário. Extraia para uma pasta estável, como `C:\Tools\apache-maven-3.10.0`, e acrescente `C:\Tools\apache-maven-3.10.0\bin` ao `PATH`.
4. Instale ou verifique Docker Desktop. Habilite a opção de backend WSL 2 e o uso de contêineres Linux. Siga as instruções do instalador se WSL precisar ser habilitado ou atualizado. Reinicie o Windows se solicitado.
5. Reabra o VS Code e os terminais depois de modificar variáveis de ambiente.
6. Instale no VS Code: **Extension Pack for Java**, **Spring Boot Extension Pack** e **Thunder Client**. Uma extensão de contêineres é opcional. Não é preciso instalar simultaneamente Postman e Insomnia.
7. No VS Code, use `Java: Configure Java Runtime` para conferir o JDK do projeto quando os módulos existirem. Uma extensão pode usar seu próprio runtime, mas a compilação deste projeto deve mirar Java 21.

No Git Bash, execute:

```bash
git --version
java -version
javac -version
mvn -version
docker version
docker compose version
```

O que verificar:

| Comando | Resultado esperado |
| --- | --- |
| `git --version` | Git instalado |
| `java -version` e `javac -version` | Ambos apontam para Java 21 |
| `mvn -version` | Maven 3.10.0 e Java 21 na informação do runtime |
| `docker version` | Informações de Client e Server, com Docker Desktop aberto |
| `docker compose version` | Compose disponível como subcomando de Docker |

Se Java e Maven estiverem usando JDKs diferentes, corrija `JAVA_HOME` e a ordem do `PATH` antes de continuar. Se aparecer somente o cliente Docker ou erro de conexão, abra Docker Desktop e aguarde a inicialização do engine.

Um teste opcional da instalação Docker, sem subir o sistema da IC:

```bash
docker run --rm hello-world
```

Esse comando precisa baixar uma imagem se ela ainda não estiver no computador.

Considerando o notebook com 8 GB que você mencionou, a proposta inicial é evitar manter a VM VMware aberta junto da infraestrutura. Execute uma modalidade por vez. As cinco JVMs, o VS Code, o Windows e o Docker podem pressionar a memória. Os 8 GB listados nos requisitos do Docker não são uma garantia de desempenho para toda essa carga.

**6. Clonar o repositório existente e abrir a primeira branch**

Se ainda não clonou o projeto, abra Git Bash na pasta onde guarda seus projetos e execute:

```bash
git clone https://github.com/theonlyrafael/saga-comparative-study.git
cd saga-comparative-study
git remote -v
git branch --show-current
git status
```

Se já clonou, entre na pasta existente e execute apenas as três verificações finais. Não crie outro repositório Git dentro dele. O remoto `origin` deve apontar para `theonlyrafael/saga-comparative-study`.

Confira a identidade dos commits:

```bash
git config user.name
git config user.email
```

Se estiverem incorretos ou vazios, ajuste no próprio repositório, usando seu nome e um e-mail verificado no GitHub ou o endereço noreply fornecido pela sua conta. A autenticação do Git no computador é separada da conexão GitHub deste chat. Faça o login oferecido pelo gerenciador de credenciais do Git, quando necessário.

Os próximos comandos assumem que a branch padrão é `main`. Confira isso no GitHub ou na saída do Git. Se houver outro nome, use o nome real. Com a árvore de trabalho limpa:

```bash
git switch main
git pull --ff-only origin main
git switch -c chore/setup-environment
```

`pull --ff-only` atualiza sem criar um merge inesperado. Se falhar por divergência ou se `git status` mostrar alterações que você não reconhece, examine o estado antes de prosseguir. Não use `reset --hard` nem force push para resolver a preparação.

**7. Criar as pastas que a especificação prevê**

| Caminho | Finalidade |
| --- | --- |
| `orquestrada/` | Aplicações da modalidade orquestrada e seu futuro agregador Maven |
| `coreografada/` | Espaço reservado à segunda modalidade |
| `compartilhado/` | Contratos e regras puras compartilháveis, sem estado de infraestrutura |
| `infra/` | Configurações de Docker, banco, broker e observabilidade |
| `experimentos/` | Cenários, sementes, configurações e resultados do estudo |
| `docs/` | Especificação, decisões, ambiente e acompanhamento |

Dentro de `orquestrada/`, adotar os seguintes nomes de módulo:

| Nome do documento | Diretório futuro | Responsabilidade |
| --- | --- | --- |
| `ordens-service` | `orquestrada/order-service/` | Entrada HTTP, catálogo local, preço capturado e situação da ordem |
| `conta-service` | `orquestrada/account-service/` | Reserva, liberação e consumo do saldo |
| `custodia-service` | `orquestrada/custody-service/` | Reserva, liberação e efetivação da posição do ativo |
| `liquidacao-service` | `orquestrada/settlement-service/` | Registro durável da decisão de liquidação |
| `orquestrador-service` | `orquestrada/orchestrator-service/` | Sequenciamento, correlação, recuperação e compensações |

Execute na raiz do clone, na branch `chore/setup-environment`:

```bash
mkdir -p orquestrada/order-service
mkdir -p orquestrada/account-service
mkdir -p orquestrada/custody-service
mkdir -p orquestrada/settlement-service
mkdir -p orquestrada/orchestrator-service
mkdir -p coreografada compartilhado infra experimentos docs/specifications docs/decisions

touch orquestrada/order-service/.gitkeep
touch orquestrada/account-service/.gitkeep
touch orquestrada/custody-service/.gitkeep
touch orquestrada/settlement-service/.gitkeep
touch orquestrada/orchestrator-service/.gitkeep
touch coreografada/.gitkeep
touch compartilhado/.gitkeep
touch infra/.gitkeep
touch experimentos/.gitkeep
touch docs/decisions/.gitkeep
```

Git versiona arquivos. Os `.gitkeep` são arquivos vazios usados por convenção para manter diretórios no repositório. Não têm significado especial para Git, Java ou Maven. Retire-os quando essas pastas receberem conteúdo real.

Copie o PDF anexado para `docs/specifications/orchestrated-saga-specification.pdf`, preservando seu conteúdo. O nome do arquivo pode ser normalizado sem modificar o documento. Não use o PDF do protótipo ou uma versão anterior da especificação.

Crie documentos curtos pelo VS Code, com estes conteúdos:

| Arquivo | O que registrar agora |
| --- | --- |
| `docs/environment.md` | Sistema operacional, versões verificadas, forma de execução, portas propostas e comandos de verificação |
| `docs/decisions/0001-project-structure.md` | Monorepositório, seis pastas principais, cinco módulos da orquestrada, inglês no código e Maven por modalidade |
| `docs/decisions/0002-local-data-isolation.md` | Uma instância PostgreSQL no desenvolvimento, cinco bancos e cinco usuários isolados, com a limitação de falha compartilhada |
| `docs/progress.md` | Etapa atual: preparação; tarefas feitas e próximo passo: infraestrutura e projetos base |
| `orquestrada/README.md` | Responsabilidades dos cinco módulos e status de implementação |
| `coreografada/README.md` | Ainda não implementada; preservará regras de domínio e mudará a coordenação |
| `compartilhado/README.md` | Apenas contratos e regras puras; não colocar entidades JPA ou repositórios compartilhados |
| `infra/README.md` | Serviços de infraestrutura previstos, sem afirmar que já podem ser iniciados |
| `experimentos/README.md` | Espaço reservado; cargas e parâmetros dependem do piloto |

Isso já popula o repositório com uma estrutura útil, sem implementar o sistema. Não é necessário criar antecipadamente pacotes `controller`, `service` ou `repository` vazios. Os pacotes serão definidos com os primeiros módulos.

**8. Revisar o .gitignore e atualizar o README**

Mantenha o `.gitignore` existente e confira se cobre os itens abaixo. Acrescente apenas o que estiver faltando:

```gitignore
**/target/
*.class
*.log

.env
.env.*
!.env.example

.idea/
*.iml
.classpath
.project
.settings/
```

O `.env.example` futuro deverá conter nomes de variáveis e valores fictícios. Senhas reais ficam fora do Git. O fato de um arquivo entrar no `.gitignore` não remove uma versão que já tenha sido commitada.

Quando houver Maven Wrapper, versionar `mvnw`, `mvnw.cmd` e `.mvn/wrapper/`. Se o tipo de Wrapper incluir um JAR e o `.gitignore` ignorar todos os `*.jar`, será necessário liberar especificamente esse arquivo. Não ignore toda a pasta `.mvn`.

Volumes de banco ficarão sob gerenciamento do Docker, fora da árvore de código. Dumps, diretórios locais de dados e credenciais também não devem ser adicionados por acidente. Resultados experimentais precisam de uma política própria: configurações, sementes e resultados pequenos podem ser versionados; artefatos volumosos devem ser referenciados em um manifesto.

No README principal, acrescente uma descrição honesta do estado do trabalho:

- Objetivo do estudo e caso de compra simulada de ativo.
- Referência à especificação em `docs/specifications/`.
- Significado das seis pastas principais.
- Tecnologias escolhidas e instruções para verificar o ambiente.
- Convenção de inglês para código.
- Fluxo de branches e pull requests.
- Status: ambiente em preparação; orquestrada ainda não implementada; coreografada planejada.

Inicialização do sistema, endpoints executáveis, diagramas da implementação e comandos de testes entram conforme essas funcionalidades existirem. Evite publicar instruções que dependam de um `compose.yaml` ou de um POM ainda inexistentes.

**9. Planejar a infraestrutura que será criada em seguida**

Para desenvolvimento no seu notebook, este guia recomenda **um contêiner PostgreSQL com cinco bancos distintos e cinco usuários de aplicação**, além de um RabbitMQ. É uma proposta de organização local permitida pelo isolamento equivalente previsto no documento, desde que as permissões sejam configuradas de fato.

| Serviço | Banco | Usuário de aplicação | Porta HTTP proposta |
| --- | --- | --- | --- |
| Order | `orders_db` | `orders_user` | 8081 |
| Account | `accounts_db` | `accounts_user` | 8082 |
| Custody | `custody_db` | `custody_user` | 8083 |
| Settlement | `settlements_db` | `settlements_user` | 8084 |
| Orchestrator | `orchestrator_db` | `orchestrator_user` | 8085 |

As portas são uma convenção de desenvolvimento proposta aqui. Somente o serviço de ordens oferece a entrada pública da operação. Os demais podem ter HTTP para saúde e diagnóstico, sem transformar as etapas da Saga em chamadas HTTP síncronas.

As credenciais serão separadas, sem superusuário nas aplicações. Cada usuário deverá ter acesso apenas ao banco e aos objetos que lhe pertencem. Revogar permissões públicas que permitam acesso cruzado e validar esse isolamento. Nenhum serviço fará consultas diretas às tabelas de outro.

A instância compartilhada economiza processos, mas uma falha nessa instância afeta os cinco bancos. Para experimentos que exijam derrubar somente um banco, serão necessárias instâncias separadas. A topologia experimental deve ser definida antes do piloto e aplicada de forma comparável às duas modalidades. Não tratar a instância única como isolamento de falhas equivalente a cinco servidores.

Proposta de portas da infraestrutura:

| Componente | Acesso pelo Windows | Porta dentro do contêiner |
| --- | --- | --- |
| PostgreSQL | `localhost:5433` | 5432 |
| RabbitMQ, AMQP | `localhost:5672` | 5672 |
| RabbitMQ, painel | `localhost:15672` | 15672 |

A porta 5433 evita disputar a 5432 com uma instalação PostgreSQL já existente. Se o RabbitMQ do protótipo ainda estiver rodando nessas portas, pare-o ou escolha outras portas documentadas para o estudo.

Aplicações executadas no Windows usarão `localhost` e as portas publicadas. Quando executadas em contêineres, usarão os nomes dos serviços na rede Compose e suas portas internas. `localhost` dentro de um contêiner aponta para aquele contêiner.

Na próxima etapa, o Compose deverá incluir volumes persistentes para PostgreSQL e RabbitMQ, credenciais externas, healthchecks, nomes de projeto próprios e versões de imagens fixadas. Interromper os contêineres deve preservar os dados. Remover os volumes é um reset deliberado dos dados, que precisa de um procedimento separado.

O pgAdmin é opcional, e você pode usar a instalação que já conhece. Não há necessidade de instalar um segundo PostgreSQL nativo nem o RabbitMQ nativo no Windows.

**10. O Spring Initializr: ficha pronta para o próximo passo**

O site [Spring Initializr](https://start.spring.io/) gera ZIPs contendo projetos Spring Boot. O pacote já inclui uma classe de inicialização, arquivos de construção e normalmente testes. Por isso, deixe a geração para a etapa de criação dos projetos base.

Quando começarmos essa etapa, use estes campos em todos os cinco projetos:

| Campo | Valor |
| --- | --- |
| Project | Maven |
| Language | Java |
| Spring Boot | 4.1.2 estável |
| Group | `io.github.theonlyrafael.sagastudy` |
| Artifact e Name | Nome do módulo, conforme tabela abaixo |
| Packaging | Jar |
| Java | 21 |
| Configuração | Properties, se houver seleção desse formato |

| Artifact | Package name |
| --- | --- |
| `order-service` | `io.github.theonlyrafael.sagastudy.orchestrated.order` |
| `account-service` | `io.github.theonlyrafael.sagastudy.orchestrated.account` |
| `custody-service` | `io.github.theonlyrafael.sagastudy.orchestrated.custody` |
| `settlement-service` | `io.github.theonlyrafael.sagastudy.orchestrated.settlement` |
| `orchestrator-service` | `io.github.theonlyrafael.sagastudy.orchestrated.orchestrator` |

Dependências planejadas:

| Dependência | Aplicações | Motivo |
| --- | --- | --- |
| Spring Web / MVC | Todas | API em ordens e acesso HTTP de saúde/diagnóstico nos demais |
| Validation | Todas | Validação das entradas e dos contratos quando explicitamente acionada |
| Spring Data JPA | Todas | Persistência local, inclusive estado do orquestrador |
| PostgreSQL Driver | Todas | Conexão ao PostgreSQL |
| Spring for RabbitMQ / AMQP | Todas | Publicação e consumo das mensagens |
| Flyway Migration | Todas | Migrações por banco |
| Spring Boot Actuator | Todas | Saúde e observabilidade |
| Prometheus | Todas | Exposição de métricas via Micrometer |
| Testcontainers | Testes integrados | PostgreSQL e RabbitMQ reais nos testes |

Os nomes exibidos pelo Initializr podem variar. As coordenadas resultantes serão conferidas no POM. No Spring Boot 4, Flyway com PostgreSQL requer também suporte específico, como `org.flywaydb:flyway-database-postgresql`, conforme a documentação oficial. Não basta presumir que qualquer dependência genérica de Flyway é suficiente.

O tracing OpenTelemetry será configurado com dependências compatíveis geridas pelo Spring Boot. Prometheus, por si só, fornece métricas e não substitui rastreamento distribuído. Não é necessário selecionar H2, WebFlux, Spring Cloud, Eureka, Gateway, Kafka ou um motor de Saga externo para cumprir o escopo atual.

Depois de gerar os ZIPs, extrair o conteúdo de cada projeto para seu diretório correspondente. Evitar uma duplicação como `orquestrada/order-service/order-service/`. Preservar os READMEs e substituir os marcadores vazios conforme necessário. Não copiar classes nem `application.properties` do protótipo antigo.

A geração automática será seguida pela organização do Maven, não por implementação imediata de regras financeiras.

**11. Como ficará o Maven multimódulo**

Na primeira entrega de código, `orquestrada/pom.xml` será um agregador com empacotamento `pom`, listando os cinco serviços. Ele também poderá servir como parent comum, herdando a configuração do Spring Boot e centralizando Java e versões. Cada serviço continuará sendo uma aplicação executável independente, com seu próprio JAR, banco e processo.

Os POMs gerados pelo Initializr precisam ser ajustados para essa hierarquia. Somente mover os ZIPs para pastas irmãs não cria a relação de módulos nem a herança do parent. O empacotamento executável deve ser aplicado aos serviços, não ao agregador.

Manter um Maven Wrapper no nível da modalidade, com versão fixada. A versão coreografada ganhará seu agregador quando começar sua implementação. Não é necessário um POM na raiz geral do repositório durante esta preparação.

`compartilhado/` começa como documentação de contratos. Não é uma sexta aplicação Spring Boot. Se surgirem regras puras ou tipos realmente compartilháveis, a transformação em biblioteca Maven deverá definir explicitamente a relação de construção entre os agregadores. Não introduzir entidades JPA, repositórios de banco ou lógica do orquestrador em uma biblioteca que determine a coordenação das duas variantes.

As migrações futuras ficam em cada serviço, no caminho convencional `src/main/resources/db/migration/`. Flyway será responsável pelo esquema. A estratégia de Hibernate deverá validar o esquema, evitando recriação automática que apague as evidências de recuperação.

**12. Convenções em inglês e fidelidade ao documento**

Usar classes como `Order`, `BalanceReservation`, `AssetReservation`, `Settlement` e `SagaInstance`, quando forem implementadas. Esses nomes são uma convenção proposta, não classes criadas nesta etapa. Pacotes em minúsculas e nomes de artefato com hífens.

O pedido de classes em inglês não altera automaticamente o contrato externo. Preservar inicialmente `POST /api/ordens`, os campos `clienteId`, `ativoId`, `quantidade` e os estados e tipos de mensagem descritos no PDF. Nomes internos em inglês podem ser associados explicitamente aos nomes externos. Qualquer mudança de endpoint, valor de enum serializado ou tipo de evento exige atualizar e versionar o contrato.

O documento também define tabelas com nomes em inglês na seção 10. Manter um glossário entre os conceitos em português e os identificadores de implementação evita traduções diferentes do mesmo conceito.

Pontos que a organização deve permitir implementar depois:

- Quatro serviços de domínio e um coordenador, com dados próprios.
- `orderId`, `sagaId`, `operationId` e `messageId` com funções distintas. Repetições da mesma operação preservam `operationId`.
- Outbox e processamento idempotente em cada serviço, inclusive no orquestrador.
- Estado da Saga persistido, com recuperação após reinício.
- Valores monetários em `BigDecimal` e `NUMERIC`.
- Liquidação durável como barreira de decisão. Depois dela, completar efetivações, sem liberação automática das reservas.
- Ordem concluída somente após efetivação de conta e custódia e confirmação da atualização final de ordens.
- Logs com identificadores, saúde, métricas e tracing.

O PDF cita o protocolo-base e cenários C1–C6, mas o protocolo-base completo não foi fornecido nesta solicitação. É possível preparar o ambiente com o anexo atual. Para reproduzir a numeração e os detalhes exatos dos cenários experimentais, será necessário consultar esse protocolo quando chegarmos à etapa de testes de falha.

**13. Fazer o primeiro commit e abrir o pull request**

Depois de criar as pastas, copiar a especificação e preencher os documentos:

```bash
git status
git diff
git add README.md .gitignore orquestrada coreografada compartilhado infra experimentos docs
git diff --cached --stat
git diff --cached
git commit -m "chore: prepare repository structure and development guide"
git push -u origin chore/setup-environment
```

Antes do commit, conferir que a lista contém somente preparação e documentação. Não incluir senhas, dados locais de banco, arquivos ZIP do Initializr, pastas `target` ou mudanças do protótipo.

No GitHub, abra um pull request com:

- Base: `main`.
- Compare: `chore/setup-environment`.
- Título: `chore: prepare repository structure and development guide`.
- Descrição: explicar a preparação das duas modalidades, a inclusão da especificação e das convenções, e informar que ainda não há implementação. Registrar quais verificações locais você realmente executou.

Revise a aba de arquivos alterados e faça o merge. Para esta etapa inicial, um merge normal facilita a remoção local da branch sem forçar a exclusão. Depois:

```bash
git switch main
git pull --ff-only origin main
git branch -d chore/setup-environment
```

Você pode remover a branch remota pelo botão de exclusão mostrado pelo GitHub após o merge. Se optar por squash merge, a exclusão local com `-d` pode recusar porque o histórico foi condensado. Verifique que o conteúdo entrou em `main` antes de qualquer remoção forçada.

Como política de projeto, manter branches por tarefa e PRs pequenos. Não é necessário introduzir uma branch permanente `dev` agora. Caso ela seja adotada mais tarde, deve ter uma função clara de integração.

Exemplos de próximas branches, criadas a partir de `main` atualizada quando a tarefa começar:

| Branch | Escopo |
| --- | --- |
| `chore/orchestrated-bootstrap` | Projetos base, POMs, Wrapper, infraestrutura e migrações iniciais |
| `feat/orchestrated-order-api` | API de ordens e idempotência HTTP |
| `feat/orchestrated-reservations` | Reservas de conta e custódia |
| `feat/orchestrated-messaging` | Outbox, inbox e contratos assíncronos |
| `feat/orchestrated-coordination` | Máquina de estados persistente e avanço das etapas |
| `feat/orchestrated-recovery` | Compensações e reconciliação |
| `test/orchestrated-failure-scenarios` | Falhas e concorrência |

Não crie todas essas branches antecipadamente. Separe tarefas grandes em PRs menores conforme as dependências reais aparecerem. Proteção de `main` por pull request é útil quando disponível, mas, trabalhando sozinho, não configure exigência de aprovação de outra pessoa que impeça suas próprias entregas. Checks obrigatórios entram quando a integração contínua existir.

**14. Quando considerar esta preparação concluída**

- [ ] Git, JDK, Maven, Docker e Compose verificados no computador.
- [ ] Java 21 selecionado de forma consistente no terminal e no projeto.
- [ ] Clone com `origin` apontando para `saga-comparative-study`.
- [ ] Branch `chore/setup-environment` criada antes das alterações.
- [ ] Seis pastas principais presentes e cinco diretórios de serviço na orquestrada.
- [ ] Especificação atual copiada para `docs/specifications/`.
- [ ] Decisões de ambiente, isolamento e nomenclatura registradas.
- [ ] README descreve corretamente o estado de preparação.
- [ ] `.gitignore` revisado e nenhum segredo incluído.
- [ ] Commit e PR de preparação revisados e integrados.
- [ ] Nenhuma classe, SQL, POM ou configuração executável do sistema criada nesta etapa.

Nesse ponto, o repositório estará pronto para receber a implementação. Ele ainda não terá cinco aplicações executáveis. A etapa seguinte será gerar os projetos base, montar o Maven multimódulo, configurar Compose e criar as primeiras migrações, seguida de verificações de inicialização e isolamento dos bancos.

**15. GitHub conectado, Chat, Work, Projetos e Codex**

**Seu GitHub está conectado neste chat:** a identidade autenticada retornou `theonlyrafael`. Também houve leitura efetiva do protótipo. A conexão não garante acesso a todos os repositórios. O acesso ao `saga-comparative-study` ainda precisa ser resolvido, pois a consulta retornou 404. Uma conexão no ChatGPT também não configura automaticamente as credenciais do Git no seu computador.

| Recurso | Papel prático |
| --- | --- |
| Chat | Conversar, esclarecer dúvidas, planejar e revisar trechos de conteúdo |
| ChatGPT Work | Executar tarefas de várias etapas com arquivos, ferramentas e entregáveis verificáveis |
| Projeto do ChatGPT | Agrupar conversas, instruções e fontes de um trabalho contínuo, como sua IC |
| Chat fora de Projeto | Conversa sem aquele conjunto de instruções e fontes compartilhadas |
| Codex | Experiência orientada a trabalho com código, diretórios, comandos, revisão de alterações e Git |

Chat e Work podem coexistir em um Projeto. Projeto organiza contexto; não é um modo alternativo de execução. No navegador, Work usa um ambiente de nuvem, sem acesso automático à pasta do seu VS Code. Acesso a arquivos locais depende de recursos e permissões específicos do aplicativo.

Work e Codex têm capacidades sobrepostas. A documentação atual destaca a interface técnica do Codex, incluindo diffs, revisão e recursos de pull request quando disponíveis. No seu caso, continuar o planejamento neste Projeto e desenvolver com Codex sobre o clone aberto no VS Code é um fluxo possível.

**É possível continuar o trabalho no Codex usando o mesmo repositório.** O caminho mais confiável é versionar a especificação, as decisões e o progresso, abrir o clone no ambiente do Codex e indicar a branch e o próximo objetivo. Não depender de que todo o histórico deste chat seja transferido automaticamente: a documentação distingue o histórico de Work do de Codex. Algumas interfaces permitem adicionar uma conversa ChatGPT a um chat Codex, mas isso depende da experiência disponível.

Para uma passagem entre ambientes, registrar em `docs/progress.md`: commit de referência, branch atual, o que existe, o que foi testado, limitações conhecidas e próximo passo. Um `AGENTS.md` na raiz pode consolidar instruções para agentes, como nomes em inglês, leitura obrigatória da especificação e proibição de alterar contratos sem documentar. Ele pode ser criado como documentação quando adotarmos o fluxo com Codex.

Não é preciso duplicar o repositório para mudar de ferramenta. Se usar Codex localmente, abra a pasta clonada. Se usar uma execução em nuvem, confira a autorização do repositório e a branch. Sincronize as alterações por commits e Git, verificando o estado antes de trabalhar no mesmo arquivo em dois lugares.

**Fontes consultadas**

Documento principal: `especificacao_definitiva_saga_orquestrada(2).pdf`, fornecido nesta solicitação, 8 páginas, datado de 29/10/2026. A decisão de um PostgreSQL local com bancos e usuários separados, as portas, as versões e a convenção de branches são propostas deste guia dentro dos limites do documento.

- [Protótipo: POM agregador](https://github.com/theonlyrafael/saga-financial-transactions-api/blob/main/pom.xml)
- [Protótipo: POM do módulo conta](https://github.com/theonlyrafael/saga-financial-transactions-api/blob/main/conta-service/pom.xml)
- [Protótipo: README](https://github.com/theonlyrafael/saga-financial-transactions-api/blob/main/README.md)
- [Spring Boot: requisitos](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot: migrações e inicialização de banco](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Spring Initializr: documentação](https://docs.spring.io/initializr/docs/current/reference/html/)
- [Maven: distribuição e versões](https://maven.apache.org/download.cgi)
- [Docker Desktop: instalação Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- [PostgreSQL: política de versões](https://www.postgresql.org/support/versioning/)
- [RabbitMQ: versão anunciada no site oficial](https://www.rabbitmq.com/)
- [GitHub flow](https://docs.github.com/en/get-started/using-github/github-flow)
- [OpenAI: começar com ChatGPT Work](https://learn.chatgpt.com/docs/get-started-with-work)
- [OpenAI: uso do ChatGPT e comparação com Codex](https://learn.chatgpt.com/docs/use-chatgpt)
- [OpenAI: Projetos e conversas](https://learn.chatgpt.com/docs/projects)
