"""Drop and recreate an Elasticsearch index from a generated index definition.

Deletes the index if it exists (after confirmation, unless ``-y``), then creates
it from a file written by ``es-participant-mapping`` (default: the newest
``schema/es/*__participant.json``), with the number of shards and replicas
added to its settings. Optionally points an alias at the new index.

Connection defaults: ``http://localhost:9200``, no authentication, overridable
by options or the ``ES_*`` environment variables listed in ``--help``.

Exit status: 0 on success, 1 if not confirmed, 2 on any error.
"""

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, TextIO

from elasticsearch import ApiError, Elasticsearch, TransportError

from py_tools.pg_apply_ddl import DEFAULT_LOG_DIR, safe_part

PROJECT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_MAPPING_DIR = PROJECT_DIR / "schema" / "es"
MAPPING_GLOB = "*__participant.json"
DEFAULT_INDEX = "participant_1"

EXIT_NOT_CONFIRMED = 1
EXIT_ERROR = 2


class Log:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.out: TextIO = path.open("w", encoding="utf-8")

    def __call__(self, message: str, echo: bool = True) -> None:
        if echo:
            print(message)
        self.out.write(message + "\n")
        self.out.flush()

    def close(self) -> None:
        self.out.close()


def log_folder(args: argparse.Namespace, action: str) -> Path:
    """logs/<host>-<port>/es/<index>/<action>/"""
    return args.log_dir.joinpath(safe_part(f"{args.host}-{args.port}"), "es", safe_part(args.index), action)


def new_log_path(folder: Path) -> Path:
    stamp = f"{datetime.now():%Y%m%d-%H%M%S}"
    path, n = folder / f"{stamp}.log", 2
    while path.exists():  # two runs in the same second
        path, n = folder / f"{stamp}-{n}.log", n + 1
    return path


def newest_mapping(folder: Path) -> Path | None:
    files = sorted(folder.glob(MAPPING_GLOB))  # names start with the generation time
    return files[-1] if files else None


def client(args: argparse.Namespace) -> Elasticsearch:
    options: dict[str, Any] = {"request_timeout": 60}
    if args.api_key:
        options["api_key"] = args.api_key
    elif args.username:
        options["basic_auth"] = (args.username, args.password or "")
    if args.scheme == "https":
        if args.insecure:
            options["verify_certs"] = False
            options["ssl_show_warn"] = False
        elif args.ca_cert:
            options["ca_certs"] = str(args.ca_cert)
    return Elasticsearch(f"{args.scheme}://{args.host}:{args.port}", **options)


def point_alias(es: Elasticsearch, alias: str, index: str) -> list[str]:
    """Point the alias at the index only, in one atomic step. Returns the indexes it left."""
    others = sorted(es.indices.get_alias(name=alias)) if es.indices.exists_alias(name=alias) else []
    others = [o for o in others if o != index]
    actions = [{"remove": {"index": o, "alias": alias}} for o in others]
    actions.append({"add": {"index": index, "alias": alias}})
    es.indices.update_aliases(actions=actions)
    return others


def confirm(index: str, docs: int, aliases: list[str], assume_yes: bool) -> bool:
    if assume_yes:
        return True
    if not sys.stdin.isatty():
        print(f"es-recreate-index: index {index} exists; no terminal to confirm on. Re-run with -y.", file=sys.stderr)
        return False
    alias_note = f", aliases: {', '.join(aliases)}" if aliases else ""
    answer = input(f"Delete index {index} ({docs} documents{alias_note}) and create it again? [y/N] ")
    return answer.strip().lower() in ("y", "yes")


def add_es_options(parser: argparse.ArgumentParser) -> None:
    """Connection options and --log-dir shared by the Elasticsearch tools."""
    env = os.environ
    conn = parser.add_argument_group("connection")
    conn.add_argument("--host", default=env.get("ES_HOST", "localhost"), help="default: $ES_HOST or localhost")
    conn.add_argument("--port", type=int, default=int(env.get("ES_PORT", "9200")), help="default: $ES_PORT or 9200")
    conn.add_argument(
        "--scheme",
        choices=["http", "https"],
        default=env.get("ES_SCHEME", "http"),
        help="default: $ES_SCHEME or http",
    )
    conn.add_argument("--username", default=env.get("ES_USERNAME"), help="basic auth user (default: $ES_USERNAME)")
    conn.add_argument("--password", default=env.get("ES_PASSWORD"), help="basic auth password (default: $ES_PASSWORD)")
    conn.add_argument("--api-key", default=env.get("ES_API_KEY"), help="API key, instead of a user (default: $ES_API_KEY)")
    conn.add_argument(
        "--ca-cert",
        type=Path,
        default=env.get("ES_CA_CERT"),
        help="CA certificate for https (default: $ES_CA_CERT, else the system's)",
    )
    conn.add_argument("--insecure", action="store_true", help="https without verifying the certificate")
    parser.add_argument(
        "--log-dir",
        type=Path,
        default=DEFAULT_LOG_DIR,
        help=f"root folder for the logs (default: {DEFAULT_LOG_DIR})",
    )


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Drop and recreate an Elasticsearch index from a generated index definition "
            "(settings + mappings), optionally pointing an alias at it."
        ),
    )
    parser.add_argument("--index", default=DEFAULT_INDEX, help=f"index to recreate (default: {DEFAULT_INDEX})")
    parser.add_argument(
        "--mapping",
        type=Path,
        default=None,
        help=f"index definition file (default: the newest {MAPPING_GLOB} in {DEFAULT_MAPPING_DIR})",
    )
    parser.add_argument("--shards", type=int, default=1, help="number_of_shards (default: 1)")
    parser.add_argument(
        "--replicas",
        type=int,
        default=0,
        help="number_of_replicas (default: 0, so a single-node cluster is green)",
    )
    parser.add_argument(
        "--alias",
        default=None,
        help="also point this alias at the new index, removing it from any other index (default: none)",
    )
    parser.add_argument("-y", "--yes", action="store_true", help="don't ask before deleting an existing index")
    add_es_options(parser)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    sys.stdout.reconfigure(line_buffering=True)
    args = parse_args(argv)
    sys.exit(run(args))


def run(args: argparse.Namespace) -> int:
    mapping_path = args.mapping or newest_mapping(DEFAULT_MAPPING_DIR)
    if mapping_path is None or not mapping_path.is_file():
        print(f"es-recreate-index: no index definition file: {mapping_path or DEFAULT_MAPPING_DIR / MAPPING_GLOB}", file=sys.stderr)
        return EXIT_ERROR
    try:
        definition = json.loads(mapping_path.read_text(encoding="utf-8"))
        mappings = definition["mappings"]
    except (json.JSONDecodeError, KeyError, TypeError) as e:
        print(f"es-recreate-index: {mapping_path} isn't an index definition ({e})", file=sys.stderr)
        return EXIT_ERROR
    settings = {
        **definition.get("settings", {}),
        "index.number_of_shards": args.shards,
        "index.number_of_replicas": args.replicas,
    }
    if args.alias == args.index:
        print("es-recreate-index: the alias can't have the index's name", file=sys.stderr)
        return EXIT_ERROR

    started = datetime.now()
    folder = log_folder(args, "recreate")
    log = Log(new_log_path(folder))
    log(f"es-recreate-index {args.index} at {args.scheme}://{args.host}:{args.port}", echo=False)
    log(f"started: {started.isoformat(timespec='seconds')}", echo=False)
    meta = mappings.get("_meta", {})
    log(f"Index definition: {mapping_path}")
    if meta:
        log(f"  generated {meta.get('generated')}, LinkML schema {meta.get('linkml_schema', {}).get('name')}, "
            f"{meta.get('fields')} fields, post-processors: {', '.join(meta.get('post_processors', [])) or 'none'}")
    try:
        es = client(args)
        info = es.info()
        log(f"Elasticsearch {info['version']['number']} at {args.scheme}://{args.host}:{args.port} "
            f"(cluster {info['cluster_name']})")

        if es.indices.exists_alias(name=args.index):
            log(f"es-recreate-index: {args.index} is an alias, not an index; give the index name", echo=False)
            print(f"es-recreate-index: {args.index} is an alias, not an index; give the index name", file=sys.stderr)
            return EXIT_ERROR
        if args.alias and es.indices.exists(index=args.alias) and not es.indices.exists_alias(name=args.alias):
            message = f"es-recreate-index: {args.alias} is an index, so it can't be used as an alias"
            log(message, echo=False)
            print(message, file=sys.stderr)
            return EXIT_ERROR

        if es.indices.exists(index=args.index):
            docs = es.count(index=args.index)["count"]
            aliases = sorted(es.indices.get_alias(index=args.index)[args.index]["aliases"])
            log(f"Index {args.index} exists: {docs} documents" + (f", aliases {', '.join(aliases)}" if aliases else ""))
            if not confirm(args.index, docs, aliases, args.yes):
                log("Not confirmed; nothing changed.")
                return EXIT_NOT_CONFIRMED
            es.indices.delete(index=args.index)
            log(f"Deleted {args.index}")
        else:
            log(f"Index {args.index} doesn't exist yet")

        es.indices.create(index=args.index, settings=settings, mappings=mappings)
        log(f"Created {args.index} ({args.shards} shard(s), {args.replicas} replica(s))")
        wait_for = "green" if args.replicas == 0 else "yellow"
        health = es.cluster.health(index=args.index, wait_for_status=wait_for, timeout="30s")
        log(f"  health: {health['status']}" + (" (timed out waiting)" if health.get("timed_out") else ""))

        if args.alias:
            moved = point_alias(es, args.alias, args.index)
            log(f"Alias {args.alias} -> {args.index}" + (f" (removed from {', '.join(moved)})" if moved else ""))
    except (ApiError, TransportError) as e:
        message = f"es-recreate-index: {e}"
        log(message, echo=False)
        print(message, file=sys.stderr)
        return EXIT_ERROR
    finally:
        log(f"Log: {log.path}", echo=True)
        log.close()
    return 0


if __name__ == "__main__":
    main()
