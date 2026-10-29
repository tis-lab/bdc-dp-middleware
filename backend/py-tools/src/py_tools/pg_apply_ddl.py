"""Apply DDL files to a PostgreSQL database and schema, one statement at a time.

Creates the database and the schema first if they don't exist. Each statement
runs in its own transaction (autocommit), so a failing statement doesn't stop
or roll back the others.

Every statement's outcome is logged, one log file per DDL file per run:

    logs/<host>-<port>/<db>/<schema>/<ddl-set>/<mode>/<ddl-file>/<timestamp>.log

where ``<ddl-set>/<mode>`` are the DDL file's grandparent and parent folder
names (``bdchm/enum-types`` for linkml-pg-ddl output). Outcomes:

    OK       ran and changed something, or had nothing to report
    SKIPPED  ran, but the object already existed (an "already exists,
             skipping" notice)
    FAILED   raised an error; details at the end of the log

The DDL's names are expected to be unqualified (as ``linkml-pg-ddl`` writes
them): the session's ``search_path`` is set to the target schema, so objects are
created there.

Exit status: 0 if no statement failed, 1 if any failed, 2 if the run couldn't
start (bad file, no connection, database or schema not creatable), 130 if
interrupted with Ctrl-C.
"""

import argparse
import os
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import TextIO

import psycopg
from psycopg import errors, sql

PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_LOG_DIR = PROJECT_DIR / "logs"

# SQLSTATEs of "already exists, skipping" notices: duplicate_database, _schema,
# _table, _column, _object (also used by linkml-pg-ddl's DO blocks).
SKIP_SQLSTATES = {"42P04", "42P06", "42P07", "42701", "42710"}

EXIT_FAILED = 1
EXIT_SETUP = 2
EXIT_INTERRUPTED = 130


@dataclass
class Statement:
    sql: str
    line: int  # 1-based line where the statement starts


@dataclass
class Failure:
    statement: Statement
    sqlstate: str | None
    message: str
    detail: str | None
    hint: str | None


@dataclass
class FileResult:
    ok: int = 0
    skipped: int = 0
    failures: list[Failure] = field(default_factory=list)

    def counts(self) -> str:
        return f"{self.ok} ok, {self.skipped} skipped, {len(self.failures)} failed"


def split_sql(text: str) -> list[Statement]:
    """Split SQL text into statements at top-level semicolons.

    Semicolons inside quoted strings, quoted identifiers, dollar-quoted bodies
    (``DO $tag$ ... $tag$``) and comments don't end a statement. Comments
    between statements are dropped.
    """
    statements: list[Statement] = []
    n = len(text)
    i = 0
    start = None  # index of the current statement's first significant character

    def begin(at: int) -> None:
        nonlocal start
        if start is None:
            start = at

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "-" and nxt == "-":  # line comment
            end = text.find("\n", i)
            i = n if end < 0 else end + 1
        elif ch == "/" and nxt == "*":  # block comment; PostgreSQL nests them
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
        elif ch == "'":  # string; '' is a quote, and E'...' also allows \'
            begin(i)
            escapes = i > 0 and text[i - 1] in "eE"
            i += 1
            while i < n:
                if escapes and text[i] == "\\":
                    i += 2
                elif text[i] == "'":
                    if i + 1 < n and text[i + 1] == "'":
                        i += 2
                    else:
                        i += 1
                        break
                else:
                    i += 1
        elif ch == '"':  # quoted identifier; "" is a quote
            begin(i)
            i += 1
            while i < n:
                if text[i] == '"':
                    if i + 1 < n and text[i + 1] == '"':
                        i += 2
                    else:
                        i += 1
                        break
                else:
                    i += 1
        elif ch == "$" and (tag := dollar_tag(text, i)) is not None:
            begin(i)
            end = text.find(tag, i + len(tag))
            i = n if end < 0 else end + len(tag)
        elif ch == ";":
            if start is not None:
                statements.append(Statement(text[start : i + 1], text.count("\n", 0, start) + 1))
                start = None
            i += 1
        else:
            if not ch.isspace():
                begin(i)
            i += 1

    if start is not None and text[start:].strip():
        statements.append(Statement(text[start:].rstrip(), text.count("\n", 0, start) + 1))
    return statements


def dollar_tag(text: str, i: int) -> str | None:
    """The ``$tag$`` opening a dollar-quoted string at ``i``, or None (e.g. for ``$1``)."""
    j = i + 1
    while j < len(text) and (text[j].isalnum() or text[j] == "_"):
        j += 1
    if j < len(text) and text[j] == "$" and not text[i + 1 : i + 2].isdigit():
        return text[i : j + 1]
    return None


def summary(statement: str, width: int = 150) -> str:
    """One line describing a statement: for a DO block, the statement it guards."""
    m = re.search(r"\bTHEN\s+(.*?;)\s+(?:ELSE|END IF)", statement, re.DOTALL)
    text = " ".join((m.group(1) if statement.lstrip().upper().startswith("DO") and m else statement).split())
    return text if len(text) <= width else text[: width - 3] + "..."


def connect(args: argparse.Namespace, dbname: str) -> psycopg.Connection:
    return psycopg.connect(
        host=args.host,
        port=args.port,
        dbname=dbname,
        user=args.user,
        # None lets libpq use PGPASSWORD or the password file.
        password=args.password,
        autocommit=True,
        connect_timeout=10,
    )


def ensure_database(args: argparse.Namespace) -> psycopg.Connection:
    """Connect to the target database, creating it first if it doesn't exist."""
    try:
        return connect(args, args.dbname)
    except psycopg.OperationalError as e:
        # 3D000 is invalid_catalog_name. Older libpq versions don't pass the SQLSTATE
        # of a connection error on, so the (English) message is checked too.
        missing = e.sqlstate == "3D000" or f'database "{args.dbname}" does not exist' in str(e)
        if not missing:
            raise
    print(f"Database {args.dbname} doesn't exist; creating it (via {args.maintenance_db}).")
    with connect(args, args.maintenance_db) as admin:
        try:
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(args.dbname)))
        except errors.DuplicateDatabase:
            pass  # created by someone else in the meantime
    return connect(args, args.dbname)


def ensure_schema(conn: psycopg.Connection, schema: str) -> None:
    """Create the schema if it doesn't exist, then make it the only one on the search_path."""
    exists = conn.execute("SELECT 1 FROM pg_namespace WHERE nspname = %s", [schema]).fetchone()
    if not exists:
        # Checked first because CREATE SCHEMA IF NOT EXISTS needs the CREATE
        # privilege on the database even when the schema exists.
        print(f"Schema {schema} doesn't exist; creating it.")
        conn.execute(sql.SQL("CREATE SCHEMA IF NOT EXISTS {}").format(sql.Identifier(schema)))
    conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
    # NOTICEs are what tell a skipped statement from one that did something.
    conn.execute("SET client_min_messages TO notice")


def safe_part(name: str) -> str:
    """A path component that is valid on Windows too."""
    return re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .") or "_"


def log_path_for(log_root: Path, args: argparse.Namespace, ddl: Path, started: datetime) -> Path:
    folder = log_root.joinpath(
        safe_part(f"{args.host}-{args.port}"),
        safe_part(args.dbname),
        safe_part(args.schema),
        safe_part(ddl.parent.parent.name),
        safe_part(ddl.parent.name),
        safe_part(ddl.stem),
    )
    stamp = f"{started:%Y%m%d-%H%M%S}"
    path, n = folder / f"{stamp}.log", 2
    while path.exists():  # two runs in the same second
        path, n = folder / f"{stamp}-{n}.log", n + 1
    return path


class Log:
    """The log of one DDL file's run, written as it goes so it's useful even if interrupted."""

    def __init__(self, path: Path, args: argparse.Namespace, ddl: Path, started: datetime):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.out: TextIO = path.open("w", encoding="utf-8")
        self.write(
            f"pg-apply-ddl {ddl}",
            f"target:  {args.user}@{args.host}:{args.port}/{args.dbname}, schema {args.schema}",
            f"started: {started.isoformat(timespec='seconds')}",
            "",
            "STATUS   LINE   SECONDS  STATEMENT",
        )

    def write(self, *lines: str) -> None:
        self.out.write("\n".join(lines) + "\n")
        self.out.flush()

    def statement(self, status: str, st: Statement, seconds: float, extra: str = "") -> None:
        line = f"{status:<8} {st.line:>6} {seconds:>8.3f}  {summary(st.sql)}"
        self.write(line + (f"\n{'':>25}{extra}" if extra else ""))

    def finish(self, result: FileResult, ended: str) -> None:
        self.write("", f"{ended}: {result.counts()}")
        if result.failures:
            self.write("", "=" * 30 + " failures " + "=" * 30)
        for f in result.failures:
            self.write(
                "",
                f"line {f.statement.line}: SQLSTATE {f.sqlstate}: {f.message}",
                *([f"DETAIL: {f.detail}"] if f.detail else []),
                *([f"HINT: {f.hint}"] if f.hint else []),
                "",
                f.statement.sql,
                "-" * 70,
            )
        self.out.close()


def apply_file(conn: psycopg.Connection, path: Path, log: Log, result: FileResult) -> None:
    """Run each statement of the file in its own transaction, logging each outcome.

    Counts go into ``result`` as they happen, so they're right even if interrupted.
    """
    notices: list[tuple[str | None, str]] = []  # (sqlstate, message)

    def on_notice(d: psycopg.errors.Diagnostic) -> None:
        # Copy now: a Diagnostic's fields are only valid during the callback.
        notices.append((d.sqlstate, d.message_primary or ""))

    conn.add_notice_handler(on_notice)
    try:
        for st in split_sql(path.read_text(encoding="utf-8")):
            notices.clear()
            t0 = time.perf_counter()
            try:
                conn.execute(st.sql)
            except psycopg.Error as e:
                seconds = time.perf_counter() - t0
                if conn.broken:
                    raise
                d = e.diag
                failure = Failure(st, e.sqlstate, (d.message_primary or str(e)).strip(), d.message_detail, d.message_hint)
                result.failures.append(failure)
                log.statement("FAILED", st, seconds, f"SQLSTATE {failure.sqlstate}: {failure.message}")
                print(f"  FAILED line {st.line}: {failure.message}  [{summary(st.sql, 80)}]", file=sys.stderr)
                continue
            seconds = time.perf_counter() - t0
            skips = [message for sqlstate, message in notices if sqlstate in SKIP_SQLSTATES]
            if skips:
                result.skipped += 1
                log.statement("SKIPPED", st, seconds, skips[0])
            else:
                result.ok += 1
                log.statement("OK", st, seconds)
    finally:
        conn.remove_notice_handler(on_notice)


def run(files: list[Path], args: argparse.Namespace) -> int:
    """Apply the files in order, one log each. Returns the exit status."""
    files = [p.resolve() for p in files]
    for path in files:
        if not path.is_file():
            print(f"pg-apply-ddl: DDL file not found: {path}", file=sys.stderr)
            return EXIT_SETUP

    print(f"Target: {args.user}@{args.host}:{args.port}/{args.dbname}, schema {args.schema}")
    failed = 0
    log = result = None
    try:
        with ensure_database(args) as conn:
            ensure_schema(conn, args.schema)
            for path in files:
                started = datetime.now()
                log = Log(log_path_for(args.log_dir, args, path, started), args, path, started)
                print(f"Applying {path}")
                result = FileResult()
                apply_file(conn, path, log, result)
                log.finish(result, f"finished {datetime.now().isoformat(timespec='seconds')}")
                print(f"  {result.counts()}\n  Log: {log.path}")
                failed += len(result.failures)
                log = None
    except KeyboardInterrupt:
        if log is not None:
            log.finish(result or FileResult(), "INTERRUPTED (Ctrl-C); counts so far")
            print(f"\nInterrupted. Log: {log.path}", file=sys.stderr)
        return EXIT_INTERRUPTED
    except psycopg.Error as e:
        if log is not None:
            log.finish(result or FileResult(), f"STOPPED: {str(e).strip()}; counts so far")
        print(f"pg-apply-ddl: {str(e).strip()}", file=sys.stderr)
        return EXIT_SETUP

    if failed:
        print(f"{failed} statement(s) failed; see the log(s) above.", file=sys.stderr)
        return EXIT_FAILED
    return 0


def build_parser(description: str, with_files: bool) -> argparse.ArgumentParser:
    env = os.environ
    parser = argparse.ArgumentParser(description=description)
    if with_files:
        parser.add_argument("ddl", type=Path, nargs="+", help="DDL files to apply, in the order given")
    parser.add_argument("--host", default=env.get("PGHOST", "localhost"), help="default: $PGHOST or localhost")
    parser.add_argument("--port", type=int, default=int(env.get("PGPORT", "5432")), help="default: $PGPORT or 5432")
    parser.add_argument("--dbname", default=env.get("PGDATABASE", "dp"), help="default: $PGDATABASE or dp")
    parser.add_argument("--user", default=env.get("PGUSER", "dp"), help="default: $PGUSER or dp")
    parser.add_argument(
        "--password",
        default=None,
        help="default: $PGPASSWORD or the password file; not needed for trust authentication",
    )
    parser.add_argument("--schema", default="bdchm", help="schema to create the objects in (default: bdchm)")
    parser.add_argument(
        "--maintenance-db",
        default="postgres",
        help="database to connect to for creating the target database (default: postgres)",
    )
    parser.add_argument(
        "--log-dir",
        type=Path,
        default=DEFAULT_LOG_DIR,
        help=f"root folder for the logs (default: {DEFAULT_LOG_DIR})",
    )
    return parser


def run_ddl_file(ddl: Path, argv: list[str] | None = None) -> None:
    """Entry point of the per-file scripts that linkml-pg-ddl generates."""
    # Keep progress (stdout) and failures (stderr) in order when piped.
    sys.stdout.reconfigure(line_buffering=True)
    parser = build_parser(
        f"Apply {ddl.name} to a PostgreSQL database and schema, one statement per transaction, "
        "creating the database and schema if needed. Every statement's outcome is logged.",
        with_files=False,
    )
    sys.exit(run([ddl], parser.parse_args(argv)))


def main(argv: list[str] | None = None) -> None:
    sys.stdout.reconfigure(line_buffering=True)
    parser = build_parser(
        "Apply DDL files to a PostgreSQL database and schema, one statement per transaction, "
        "creating the database and schema if needed. Every statement's outcome is logged, "
        "one log per file.",
        with_files=True,
    )
    args = parser.parse_args(argv)
    sys.exit(run(args.ddl, args))


if __name__ == "__main__":
    main()
