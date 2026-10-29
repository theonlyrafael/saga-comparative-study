# Ambiente e versões verificadas

Consulta às fontes oficiais e execução em 29/10/2026. Configuração ajustada posteriormente para Java 17 e Boot 4.1.0 a pedido do autor; os artefatos foram obtidos com TLS e checksums habilitados.

| Componente | Versão fixada / verificada | Fonte |
| --- | --- | --- |
| Java | Alvo 17; execução Temurin **17.0.7+7**, Linux x64 | [Release oficial](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.7%2B7) |
| Spring Boot | **4.1.0**, parent dos módulos | [POM publicado](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/4.1.0/spring-boot-starter-parent-4.1.0.pom) |
| Maven | **3.10.0**, Wrapper **3.3.4**, tipo only-script | [Distribuição oficial](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/) |
| PostgreSQL | **17.11-bookworm** | [Catálogo oficial de imagens](https://github.com/docker-library/official-images/blob/master/library/postgres) |
| RabbitMQ | **4.4.0-management** | [Catálogo oficial de imagens](https://github.com/docker-library/official-images/blob/master/library/rabbitmq) |
| Docker Engine / Compose | **28.4.0 / 2.40.3**, ferramentas disponíveis no executor | `docker version` / `docker compose version` |

Os [requisitos oficiais do Boot 4.1.0](https://github.com/spring-projects/spring-boot/blob/v4.1.0/documentation/spring-boot-docs/src/docs/antora/modules/ROOT/pages/system-requirements.adoc)
exigem Java 17 ou superior e Maven 3.6.3 ou posterior. O projeto agora compila
com `release=17`, compatível com o JDK 17.0.7 do autor. As versões de Flyway,
Hibernate, PostgreSQL JDBC e Micrometer são gerenciadas pelo BOM do Boot 4.1.0.
A versão SNAPSHOT identifica apenas o projeto local em desenvolvimento.

Digests das imagens estão fixados no Compose:

- PostgreSQL: `sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5`.
- RabbitMQ: `sha256:af6f50d38098d8bd52fef7f3fa85015dd8b2bf6def58e995bf584ae9ba0f6407`.
- Distribuição Maven ZIP: SHA-256 `1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`, conferida contra o SHA-512 oficial e registrada no Wrapper.
- Temurin 21 Linux x64 (validação original): SHA-256 `d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c`, conferido contra o arquivo do fornecedor.
- Temurin 17.0.7+7 Linux x64: SHA-256 `e9458b38e97358850902c2936a1bb5f35f6cffc59da9fcd28c63eab8dbbfbc3b`, verificado contra o checksum oficial.
- PDF original: SHA-256 `4c26d88863399196aa33355798e8fcb2af1beb82130103b01054aadbc36c0d6b`.

## Configuração local

As JVMs executam no host; apenas PostgreSQL e RabbitMQ executam no Compose. Java
17 precisa incluir `javac`, e `JAVA_HOME` deve selecionar o mesmo JDK usado no PATH.
A configuração de exemplo é `infra/.env.example`. O arquivo `infra/.env` é local,
ignorado pelo Git e compartilhado apenas pelas ferramentas locais de inicialização.
Use valores distintos para as seis senhas PostgreSQL e a senha RabbitMQ.
Mantenha o arquivo compatível com Bash e Compose, com linhas LF; senhas aleatórias
hexadecimais evitam diferenças de interpolação entre as ferramentas.

`run-service.sh` passa somente a senha do banco selecionado e as credenciais AMQP
para a JVM; remove as senhas de administração e dos outros bancos. O nome do banco
e usuário são específicos em cada `application.properties`.

Portas de aplicação: 8081–8085 (order, account, custody, settlement, orchestrator).
PostgreSQL: 5433 no host / 5432 no contêiner. RabbitMQ: 5672 e painel 15672.
Aplicações e portas publicadas escutam apenas em loopback para desenvolvimento.
Se futuramente executar JVMs em contêineres, configure `DB_HOST=postgres`,
`POSTGRES_PORT=5432`, `RABBITMQ_HOST=rabbitmq` e endereço HTTP apropriado.

Cada aplicação aceita `SERVER_PORT`, `SERVER_ADDRESS`, `DB_HOST`, `POSTGRES_PORT`,
`DB_PASSWORD`, `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`, `DB_CONNECTION_TIMEOUT`,
`RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`,
`RABBITMQ_VHOST` e `RABBITMQ_PREFETCH`. Pool padrão 1–5 conexões, timeout 5000 ms,
prefetch 10: escolhas de desenvolvimento, não parâmetros experimentais.
`SERVER_PORT` deve ser configurado por processo. O verificador de saúde usa as
cinco portas convencionais; consulte endpoints diretamente se personalizá-las.

## Ajustes específicos do executor de nuvem

A imagem inicial continha o runtime Debian 21.0.12.1, mas não um JDK completo.
Inicialmente foi instalado Temurin 21; para a configuração atual foi instalado
Temurin em `/workspace/tools/jdk-17.0.7+7`. O Maven usa o proxy da
plataforma por `/workspace/tools/maven-settings.xml`, com repositório local fora do
checkout. O truststore fornecido pela plataforma permite verificar TLS no JDK
recém-instalado. Comandos usados neste executor:

```bash
cd /workspace/saga-comparative-study/orquestrada
JAVA_HOME=/workspace/tools/jdk-17.0.7+7 \
MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
./mvnw -B -ntp -s /workspace/tools/maven-settings.xml clean verify
```

Esses caminhos são específicos desta instância, não requisitos no Windows. Não
copiar configurações do proxy, certificados ou credenciais da sessão para o Git.
O checkout existente já é isolado; não é necessário criar outro clone ou worktree
para continuar nesta nuvem. Em nova instância, conferir JDK, proxy, Docker e
credenciais locais antes de reutilizar os comandos; processos vivos não persistem.

## JDK 17 existente no Windows

O projeto usa Java 17 e Spring Boot 4.1.0, como o protótipo. O JDK 17.0.7 já
instalado pode ser usado; não é necessário instalar o JDK 21. Confira no terminal:

```bash
java -version
javac -version
cd orquestrada
./mvnw -version
```

Os três comandos devem apontar para Java 17. Se necessário, configure `JAVA_HOME`
para a pasta do JDK 17 existente e adicione seu `bin` ao PATH. Spring Boot é obtido
pelo Maven a partir do parent, sem instalação manual. O Wrapper e a infraestrutura
mantêm suas versões; não é preciso instalar Maven globalmente.
