#!/usr/bin/env bash
set -euo pipefail

# Executado pela imagem oficial somente em um volume PostgreSQL vazio.
# psql lê as senhas do ambiente sem interpolá-las em comandos shell ou logs.
psql -X --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv orders_password ORDERS_DB_PASSWORD
\getenv accounts_password ACCOUNTS_DB_PASSWORD
\getenv custody_password CUSTODY_DB_PASSWORD
\getenv settlements_password SETTLEMENTS_DB_PASSWORD
\getenv orchestrator_password ORCHESTRATOR_DB_PASSWORD

CREATE ROLE orders_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'orders_password';
CREATE ROLE accounts_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'accounts_password';
CREATE ROLE custody_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'custody_password';
CREATE ROLE settlements_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'settlements_password';
CREATE ROLE orchestrator_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'orchestrator_password';

REVOKE ALL ON DATABASE postgres, template0, template1 FROM PUBLIC;
CREATE DATABASE orders_db;
CREATE DATABASE accounts_db;
CREATE DATABASE custody_db;
CREATE DATABASE settlements_db;
CREATE DATABASE orchestrator_db;
REVOKE ALL ON DATABASE orders_db, accounts_db, custody_db, settlements_db, orchestrator_db FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE orders_db TO orders_user;
GRANT CONNECT, TEMPORARY ON DATABASE accounts_db TO accounts_user;
GRANT CONNECT, TEMPORARY ON DATABASE custody_db TO custody_user;
GRANT CONNECT, TEMPORARY ON DATABASE settlements_db TO settlements_user;
GRANT CONNECT, TEMPORARY ON DATABASE orchestrator_db TO orchestrator_user;
SQL

for service in orders accounts custody settlements orchestrator; do
    psql -X --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
        --dbname "${service}_db" --set=app_user="${service}_user" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"app_user" REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
SQL
done
