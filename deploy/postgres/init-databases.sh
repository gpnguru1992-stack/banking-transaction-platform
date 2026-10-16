#!/bin/sh
# Local/dev only: one PostgreSQL server, one database and one owner per service.
# In production each service gets its own managed instance (e.g. Amazon RDS).
set -e
for svc in customer account payment transaction notification; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE USER ${svc}_svc WITH PASSWORD '${svc}_svc';
CREATE DATABASE ${svc}_db OWNER ${svc}_svc;
SQL
done
