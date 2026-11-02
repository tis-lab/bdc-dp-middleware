"""Set up a PostgreSQL role, database, extensions and schema for an application.

Connects as an administrative role (default ``postgres``) and creates, only if
missing:

1. a login role (default ``dp``),
2. a database owned by that role (default ``dp``),
3. extensions in that database (default ``vector``),
4. a schema owned by that role (default ``bdchm``).

Existing objects are left as they are, even if they differ (another owner, no
LOGIN). They're reported, not changed, so the command is safe to run any time.
Afterwards ``pg-apply-ddl`` and the generated ``apply_*.py`` scripts can run as
the role, with the same defaults.

Exit status: 0 on success, 2 on any error.
"""

import argparse
import os
import sys

import psycopg
from psycopg import sql


def connect(args: argparse.Namespace, dbname: str) -> psycopg.Connection:
    return psycopg.connect(
        host=args.host,
        port=args.port,
        dbname=dbname,
        user=args.admin_user,
        # None lets libpq use PGPASSWORD or the password file.
        password=args.admin_password,
        autocommit=True,  # CREATE DATABASE can't run in a transaction
        connect_timeout=10,
    )


def report(kind: str, name: str, state: str) -> None:
    print(f"{kind:<10} {name:<24} {state}")


def ensure_role(conn: psycopg.Connection, role: str, password: str | None) -> None:
    row = conn.execute("SELECT rolcanlogin FROM pg_roles WHERE rolname = %s", [role]).fetchone()
    if row is None:
        statement = sql.SQL("CREATE ROLE {} LOGIN").format(sql.Identifier(role))
        if password is not None:
            statement += sql.SQL(" PASSWORD {}").format(sql.Literal(password))
        conn.execute(statement)
        report("role", role, "created (LOGIN)")
    else:
        report("role", role, "exists" + ("" if row[0] else " (NOLOGIN; left as is)"))


def ensure_database(conn: psycopg.Connection, dbname: str, owner: str) -> None:
    row = conn.execute(
        "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = %s", [dbname]
    ).fetchone()
    if row is None:
        conn.execute(
            sql.SQL("CREATE DATABASE {} OWNER {}").format(sql.Identifier(dbname), sql.Identifier(owner))
        )
        report("database", dbname, f"created (owner {owner})")
    else:
        report("database", dbname, f"exists (owner {row[0]})")


def ensure_extensions(conn: psycopg.Connection, extensions: list[str]) -> None:
    for ext in extensions:
        row = conn.execute("SELECT extversion FROM pg_extension WHERE extname = %s", [ext]).fetchone()
        if row is None:
            conn.execute(sql.SQL("CREATE EXTENSION IF NOT EXISTS {}").format(sql.Identifier(ext)))
            version = conn.execute("SELECT extversion FROM pg_extension WHERE extname = %s", [ext]).fetchone()
            report("extension", ext, f"created (version {version[0]})")
        else:
            report("extension", ext, f"exists (version {row[0]})")


def ensure_schema(conn: psycopg.Connection, schema: str, owner: str) -> None:
    row = conn.execute(
        "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = %s", [schema]
    ).fetchone()
    if row is None:
        conn.execute(
            sql.SQL("CREATE SCHEMA {} AUTHORIZATION {}").format(sql.Identifier(schema), sql.Identifier(owner))
        )
        report("schema", schema, f"created (owner {owner})")
    else:
        report("schema", schema, f"exists (owner {row[0]})")


def main(argv: list[str] | None = None) -> None:
    env = os.environ
    parser = argparse.ArgumentParser(
        description=(
            "Create a login role, a database it owns, extensions, and a schema it owns, "
            "each only if missing. Connects as an administrative role."
        ),
    )
    parser.add_argument("--host", default=env.get("PGHOST", "localhost"), help="default: $PGHOST or localhost")
    parser.add_argument("--port", type=int, default=int(env.get("PGPORT", "5432")), help="default: $PGPORT or 5432")
    parser.add_argument(
        "--user",
        default=env.get("PGUSER", "dp"),
        help="role to create; owns the database and schema (default: $PGUSER or dp)",
    )
    parser.add_argument(
        "--password",
        default=None,
        help="password for a newly created role (default: none; not needed for trust authentication)",
    )
    parser.add_argument("--dbname", default=env.get("PGDATABASE", "dp"), help="default: $PGDATABASE or dp")
    parser.add_argument("--schema", default="bdchm", help="schema to create in the database (default: bdchm)")
    parser.add_argument(
        "--extensions",
        default="vector",
        help="comma-separated extensions to create in the database; '' for none (default: vector)",
    )
    parser.add_argument("--admin-user", default="postgres", help="role to connect as (default: postgres)")
    parser.add_argument(
        "--admin-password",
        default=None,
        help="password of the admin role (default: $PGPASSWORD or the password file)",
    )
    parser.add_argument(
        "--admin-db",
        default="postgres",
        help="database to connect to for creating the role and database (default: postgres)",
    )
    args = parser.parse_args(argv)
    extensions = [e.strip() for e in args.extensions.split(",") if e.strip()]

    print(f"Server: {args.host}:{args.port}, as {args.admin_user}")
    try:
        with connect(args, args.admin_db) as conn:
            ensure_role(conn, args.user, args.password)
            ensure_database(conn, args.dbname, args.user)
        with connect(args, args.dbname) as conn:
            ensure_extensions(conn, extensions)
            ensure_schema(conn, args.schema, args.user)
    except psycopg.Error as e:
        print(f"pg-bootstrap: {str(e).strip()}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
