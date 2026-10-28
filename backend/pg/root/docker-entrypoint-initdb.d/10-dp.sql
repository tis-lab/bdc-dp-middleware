-- Runs once, when initdb creates a new cluster in an empty data/pg.
CREATE ROLE dp LOGIN;
CREATE DATABASE dp OWNER dp;

\connect dp
ALTER SCHEMA public OWNER TO dp;
CREATE EXTENSION vector;
