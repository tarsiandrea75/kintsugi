-- One PostgreSQL instance for the lab, one database per service (ADR-007).
-- Runs once, on the first start with an empty data volume, as the POSTGRES_USER superuser (lab).
CREATE DATABASE orders;
CREATE DATABASE payments;
CREATE DATABASE inventory;
