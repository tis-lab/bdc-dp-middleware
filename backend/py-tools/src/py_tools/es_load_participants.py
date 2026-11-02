"""Load participant JSON documents into an Elasticsearch index.

Reads the files written by ``pg-export-participants`` (default: every
``data/participants/*.json``) and bulk-indexes them into an existing index
(default ``participant_1``), with each document's ``id`` (the participant id)
as the document ``_id``. A document that's already there is replaced, so
loading again is safe.

The index must exist, created by ``es-recreate-index`` from the generated
definition: with ``dynamic: strict``, a document that doesn't match the mapping
is rejected, and the loader reports it rather than letting Elasticsearch
create an index with guessed mappings.

Options:
- ``--delete-missing``: afterwards, delete documents whose id isn't among the
  files (e.g. participants no longer in the database).
- ``--alias NAME``: if every document loaded, point the alias at the index.

Exit status: 0 if every document loaded, 1 if any failed, 2 if the run
couldn't start (no files, no index, can't connect), 130 if interrupted.
"""

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator

from elasticsearch import ApiError, Elasticsearch, TransportError, helpers

from py_tools.es_recreate_index import DEFAULT_INDEX, Log, add_es_options, client, log_folder, new_log_path, point_alias
from py_tools.pg_export_participants import DEFAULT_OUT_DIR

EXIT_FAILED = 1
EXIT_SETUP = 2
EXIT_INTERRUPTED = 130


@dataclass
class Failure:
    doc_id: str | None
    path: Path
    error: str


def collect_files(args: list[Path]) -> list[Path] | str:
    files: list[Path] = []
    for arg in args or [DEFAULT_OUT_DIR]:
        path = arg.resolve()
        if path.is_dir():
            found = sorted(path.glob("*.json"))
            if not found:
                return f"no *.json files in {path}"
            files += found
        elif path.is_file():
            files.append(path)
        else:
            return f"not a file or directory: {path}"
    return list(dict.fromkeys(files))


def error_text(error: Any) -> str:
    """An Elasticsearch bulk item error, with its cause, on one line."""
    if not isinstance(error, dict):
        return str(error)
    text = f"{error.get('type')}: {error.get('reason')}"
    cause = error.get("caused_by")
    while isinstance(cause, dict):
        text += f" | caused by {cause.get('type')}: {cause.get('reason')}"
        cause = cause.get("caused_by")
    return text


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Bulk-load participant JSON documents into an existing Elasticsearch index, "
            "with the participant id as the document id."
        ),
    )
    parser.add_argument(
        "participants",
        type=Path,
        nargs="*",
        help=f"participant .json files, or directories of them (default: {DEFAULT_OUT_DIR})",
    )
    parser.add_argument("--index", default=DEFAULT_INDEX, help=f"index (or alias) to load into (default: {DEFAULT_INDEX})")
    parser.add_argument(
        "--delete-missing",
        action="store_true",
        help="afterwards, delete documents whose id isn't among the files (default: keep them)",
    )
    parser.add_argument(
        "--alias",
        default=None,
        help="if every document loaded, point this alias at the index, removing it from any other (default: none)",
    )
    parser.add_argument("--chunk-size", type=int, default=500, help="documents per bulk request (default: 500)")
    add_es_options(parser)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    sys.stdout.reconfigure(line_buffering=True)
    sys.exit(run(parse_args(argv)))


def run(args: argparse.Namespace) -> int:
    files = collect_files(args.participants)
    if isinstance(files, str):
        print(f"es-load-participants: {files}", file=sys.stderr)
        return EXIT_SETUP
    if args.alias == args.index:
        print("es-load-participants: the alias can't have the index's name", file=sys.stderr)
        return EXIT_SETUP

    started = datetime.now()
    log = Log(new_log_path(log_folder(args, "load")))
    log(f"es-load-participants into {args.index} at {args.scheme}://{args.host}:{args.port}", echo=False)
    log(f"started: {started.isoformat(timespec='seconds')}", echo=False)
    log(f"Files: {len(files)}" + (f" in {files[0].parent}" if len({f.parent for f in files}) == 1 else ""))

    failures: list[Failure] = []
    path_of: dict[str, Path] = {}
    loaded = 0
    try:
        es: Elasticsearch = client(args)
        info = es.info()
        log(f"Elasticsearch {info['version']['number']} at {args.scheme}://{args.host}:{args.port}")
        if not es.indices.exists(index=args.index):
            message = (
                f"es-load-participants: index {args.index} doesn't exist; create it first with "
                f"`uv run es-recreate-index --index {args.index}`"
            )
            log(message, echo=False)
            print(message, file=sys.stderr)
            return EXIT_SETUP

        def actions() -> Iterator[dict]:
            for path in files:
                try:
                    doc = json.loads(path.read_text(encoding="utf-8"))
                except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
                    failures.append(Failure(None, path, f"can't read: {e}"))
                    continue
                doc_id = doc.get("id") if isinstance(doc, dict) else None
                if not isinstance(doc_id, str) or not doc_id:
                    failures.append(Failure(None, path, "no string 'id' field"))
                    continue
                if doc_id in path_of:
                    failures.append(Failure(doc_id, path, f"same id as {path_of[doc_id].name}; loaded the later file"))
                path_of[doc_id] = path
                yield {"_op_type": "index", "_index": args.index, "_id": doc_id, "_source": doc}

        log(f"Loading into {args.index} ...")
        for ok, item in helpers.streaming_bulk(
            es,
            actions(),
            chunk_size=args.chunk_size,
            raise_on_error=False,
            max_retries=3,  # retries 429 (too many requests) with back-off
        ):
            result = item.get("index", {})
            if ok:
                loaded += 1
            else:
                doc_id = result.get("_id")
                failures.append(Failure(doc_id, path_of.get(doc_id, Path("?")), error_text(result.get("error"))))
                print(f"  FAILED {doc_id}: {error_text(result.get('error'))[:200]}", file=sys.stderr)
        es.indices.refresh(index=args.index)
        log(f"  {loaded} loaded, {len(failures)} failed")

        if args.delete_missing:
            wanted = set(path_of)
            stale = [
                hit["_id"]
                for hit in helpers.scan(es, index=args.index, query={"query": {"match_all": {}}}, _source=False)
                if hit["_id"] not in wanted
            ]
            if stale:
                helpers.bulk(es, ({"_op_type": "delete", "_index": args.index, "_id": i} for i in stale))
                es.indices.refresh(index=args.index)
            log(f"  {len(stale)} document(s) not among the files deleted" + (f": {', '.join(stale[:10])}" + (" ..." if len(stale) > 10 else "") if stale else ""))

        total = es.count(index=args.index)["count"]
        log(f"  {args.index} now has {total} documents")

        if args.alias:
            if failures:
                log(f"Alias {args.alias} not moved, because {len(failures)} document(s) failed")
            else:
                moved = point_alias(es, args.alias, args.index)
                log(f"Alias {args.alias} -> {args.index}" + (f" (removed from {', '.join(moved)})" if moved else ""))
    except KeyboardInterrupt:
        log(f"\nInterrupted after {loaded} document(s); documents already sent stay in the index.")
        write_failures(log, failures)
        log(f"Log: {log.path}")
        log.close()
        return EXIT_INTERRUPTED
    except (ApiError, TransportError) as e:
        message = f"es-load-participants: {e}"
        log(message, echo=False)
        print(message, file=sys.stderr)
        write_failures(log, failures)
        log(f"Log: {log.path}")
        log.close()
        return EXIT_SETUP

    write_failures(log, failures)
    log(f"Log: {log.path}")
    log.close()
    return EXIT_FAILED if failures else 0


def write_failures(log: Log, failures: list[Failure]) -> None:
    if not failures:
        return
    print(f"{len(failures)} document(s) failed; details in the log.", file=sys.stderr)
    log("", echo=False)
    log("=" * 30 + " failures " + "=" * 30, echo=False)
    for f in failures:
        log(f"{f.doc_id or '-'} ({f.path.name}): {f.error}", echo=False)


if __name__ == "__main__":
    main()
