# Infraestrutura local

Compose usa PostgreSQL 17.11 e RabbitMQ 4.4.0-management, com tags completas e
digests fixados. PostgreSQL publica 5433; AMQP 5672; painel 15672. Todas as portas
são publicadas apenas no loopback. O hostname estável do RabbitMQ preserva a
identidade do nó sobre o volume persistente.

Na raiz do repositório, copie `infra/.env.example` para `infra/.env` e substitua os
marcadores por senhas locais distintas (não versionadas). O Compose exige valores
não vazios. Não use o exemplo como credencial de produção.

```bash
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up -d --wait
bash infra/scripts/check-database-isolation.sh
docker compose --env-file infra/.env -f infra/compose.yaml down
```

`down` preserva os volumes. Não use `down -v` no encerramento normal: apaga bancos
e dados do broker. O script de bancos só roda na primeira criação do volume;
alterar `.env` depois disso não altera senhas já persistidas. Faça rotação explícita
por administração do PostgreSQL/RabbitMQ quando necessário. Nunca apague volumes
existentes para corrigir automaticamente uma inicialização parcial.

O usuário administrativo fica apenas no contêiner PostgreSQL. Aplicações recebem
usuários sem privilégios administrativos; bancos são propriedade do administrador,
e cada usuário pode criar objetos apenas no schema public do próprio banco.
O verificador testa conexões TCP autenticadas, permissões, senha inválida e as vinte
combinações proibidas. Sua tabela de diagnóstico é revertida na mesma transação.

A instância única compartilha o domínio de falha; veja
[decisão de isolamento](../docs/decisions/0002-local-data-isolation.md).
