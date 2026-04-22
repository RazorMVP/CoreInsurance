-- Create the Keycloak database alongside the default 'cia' database.
-- PostgreSQL runs all .sql files in /docker-entrypoint-initdb.d/ on first boot.
-- The 'cia' database is already created by POSTGRES_DB; this script adds 'keycloak'.

SELECT 'CREATE DATABASE keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec

GRANT ALL PRIVILEGES ON DATABASE keycloak TO cia;
