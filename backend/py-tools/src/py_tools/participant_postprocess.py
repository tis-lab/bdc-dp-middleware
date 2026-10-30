"""Post-processing of participant documents before they're saved.

``pg-export-participants`` builds each participant's JSON object, then passes it
through the post-processors in ``POST_PROCESSORS`` (in order), then through any
given with ``--post-processor module:name``, and saves what comes out. Use them
to derive extra top-level fields for indexing, for example.

A post-processor is any callable ``(doc, ctx) -> dict | None``:

- ``doc`` is the participant document (a ``dict`` that will become JSON).
- ``ctx`` is a ``Context`` with the participant id, the schema and the rows the
  document was built from.
- Return ``None`` after changing ``doc`` in place, or return a new ``dict`` to
  replace it.

It can be a plain function, or a class: a class is instantiated once, with no
arguments, before the first participant, so it can set up state (lookups,
counters) in ``__init__`` and is then called like a function through
``__call__``. If it also has a ``finish(ctx_summary)`` method, that's called once
after the last participant.

Example, to add here or in a module of your own:

    def add_condition_count(doc, ctx):
        doc["condition_count"] = len(doc.get("condition", []))

    POST_PROCESSORS = [add_condition_count]

or, from another module without editing this file:

    uv run pg-export-participants --post-processor my_module:add_condition_count

A post-processor that adds fields must declare their Elasticsearch mappings in
an ``es_properties`` attribute (field name -> mapping), because the index is
``dynamic: strict``: ``es-participant-mapping`` adds them to the mapping, and a
document with an undeclared field is rejected by Elasticsearch:

    add_condition_count.es_properties = {"condition_count": {"type": "integer"}}

(on a class, a class attribute). Start added field names with ``_`` so they
can't clash with slot names.

Post-processors must produce JSON-serializable values. If one raises, that
participant's file isn't written, the error is logged, and the export goes on
with the next participant.
"""

import importlib
from dataclasses import dataclass, field
from typing import Any, Callable

from linkml_runtime import SchemaView

PostProcessor = Callable[[dict, "Context"], dict | None]


@dataclass
class Context:
    """What a post-processor gets besides the document."""

    participant_id: str
    schema_view: SchemaView
    # table name -> {id -> row}: every row the export read, for lookups that
    # go beyond the document.
    rows: dict[str, dict[Any, dict]]
    # Free-form space a post-processor can use to pass things to later ones
    # for the same participant.
    extra: dict[str, Any] = field(default_factory=dict)


CONDITION_KEY_FIELDS = ("condition_status", "relationship_to_participant", "condition_concept")
CONDITION_KEY_SEPARATOR = "|"


def add_condition_keys(doc: dict, ctx: Context) -> None:
    """``_conditions``: one ``status|relationship|concept`` string per condition.

    For keyword indexing, e.g. a terms query for ``PRESENT|ONESELF|MONDO:0005148``,
    or a prefix query for ``PRESENT|ONESELF|``. One entry per condition, in the
    order of ``condition`` (duplicates kept). A missing field is an empty part,
    so each part stays in its position. Always present: ``[]`` if there are no
    conditions.
    """
    doc["_conditions"] = [
        CONDITION_KEY_SEPARATOR.join("" if c.get(f) is None else str(c[f]) for f in CONDITION_KEY_FIELDS)
        for c in doc.get("condition", [])
    ]


add_condition_keys.es_properties = {"_conditions": {"type": "keyword"}}


# Built-in post-processors, run in this order for every participant.
POST_PROCESSORS: list[PostProcessor | type] = [add_condition_keys]


def instantiate(item: PostProcessor | type) -> PostProcessor:
    return item() if isinstance(item, type) else item


def load(spec: str) -> PostProcessor:
    """``module.path:name`` -> the post-processor it names (classes instantiated)."""
    module_name, sep, attr = spec.partition(":")
    if not sep or not module_name or not attr:
        raise ValueError(f"post-processor must be given as module:name, not {spec!r}")
    obj = importlib.import_module(module_name)
    for part in attr.split("."):
        obj = getattr(obj, part)
    if not callable(obj):
        raise ValueError(f"{spec} isn't callable")
    return instantiate(obj)


def run(doc: dict, ctx: Context, processors: list[PostProcessor]) -> dict:
    for processor in processors:
        result = processor(doc, ctx)
        if result is not None:
            if not isinstance(result, dict):
                raise TypeError(f"{name_of(processor)} returned {type(result).__name__}, not a dict or None")
            doc = result
    return doc


def finish(processors: list[PostProcessor], summary: dict[str, Any]) -> None:
    for processor in processors:
        done = getattr(processor, "finish", None)
        if callable(done):
            done(summary)


def name_of(processor: PostProcessor) -> str:
    return getattr(processor, "__qualname__", None) or type(processor).__qualname__
