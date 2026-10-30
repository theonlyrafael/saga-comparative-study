# 0002 — Isolamento local de dados

Uma instância PostgreSQL de desenvolvimento hospeda cinco bancos:

| Serviço | Banco | Usuário |
| --- | --- | --- |
| order | orders_db | orders_user |
| account | accounts_db | accounts_user |
| custody | custody_db | custody_user |
| settlement | settlements_db | settlements_user |
| orchestrator | orchestrator_db | orchestrator_user |

Os usuários não são superusuários, não criam bancos nem roles e não pertencem uns
aos outros. A inicialização revoga permissões de PUBLIC nos bancos e no schema
public; cada usuário recebe apenas acesso ao próprio banco e schema. Flyway controla
o esquema; Hibernate apenas valida. Sem H2 ou recriação automática de tabelas.

Uma falha da instância afeta todos os bancos. Experimentos de falha individual
exigem instâncias separadas e topologia comparável entre modalidades. Isolamento
de acesso não equivale a isolamento de falha. Volumes persistentes sobrevivem ao
encerramento normal; sua exclusão é uma operação destrutiva separada.
