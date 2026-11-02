# Elasticsearch query examples

Queries for the participant index, ready to paste into Kibana's Dev Tools
console. They assume the index created by `es-recreate-index` and loaded by
`es-load-participants` (see [README.md](README.md)). Replace `participant_1`
with your index or alias name.

The examples use `_conditions`, the keyword array added by the
`add_condition_keys` post-processor. It holds one
`condition_status|relationship_to_participant|condition_concept` string per
condition, for example `PRESENT|ONESELF|MONDO:0005148`.

All examples set `"size": 0`, so the response has no documents, only the
aggregations and `hits.total.value` (the number of matching participants,
exact because of `"track_total_hits": true`).

To count the participants in the index, use `GET participant_1/_count`. The
document count that `GET _cat/indices` shows is much higher, because it also
counts every nested document (each condition, visit, observation, ...).

## Count the other conditions of participants with one condition

Finds the participants whose `_conditions` contains one exact keyword, and
counts the other keywords those participants have.

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" }
  },
  "aggs": {
    "other_conditions": {
      "terms": {
        "field": "_conditions",
        "size": 100,
        "exclude": ["PRESENT|ONESELF|MONDO:0005148"]
      }
    }
  }
}
```

- `query.term` matches the exact keyword, so only participants with that
  condition are counted. `hits.total.value` is how many there are.
- `aggs.other_conditions` is a `terms` aggregation: one bucket per
  `_conditions` value found among those participants, most frequent first.
  A bucket's `doc_count` is the number of those participants who have that
  keyword.
- `exclude` leaves out the keyword searched for. Without it, it would be the
  first bucket, with `doc_count` equal to the total.
- `size` is the maximum number of buckets. If `sum_other_doc_count` in the
  response is above 0, some keywords didn't fit; raise it.

## Count conditions within a group, using a prefix

The same idea, but the participants are selected by a prefix, here everyone
with any condition that is `PRESENT` for themselves (`ONESELF`), and only
keywords with that prefix are counted.

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "prefix": { "_conditions": "PRESENT|ONESELF|" }
  },
  "aggs": {
    "conditions": {
      "terms": {
        "field": "_conditions",
        "size": 100,
        "include": "PRESENT\\|ONESELF\\|.*"
      }
    }
  }
}
```

- `query.prefix` matches any keyword starting with `PRESENT|ONESELF|`. In
  queries, `|` is an ordinary character.
- `include` is a regular expression applied to the bucket keys, so `|` must
  be escaped. In the JSON string, `\\|` is the regular expression `\|`.
  Without `include`, the buckets would also list the participants' other
  conditions (`ABSENT|...`, family history, ...).
- Each bucket's `doc_count` is the number of participants with that
  `PRESENT|ONESELF|<concept>` condition.

## Count every condition keyword

Counts all `_conditions` keywords over all participants. There's no `query`,
so every document is included.

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "aggs": {
    "all_conditions": {
      "terms": {
        "field": "_conditions",
        "size": 100
      }
    }
  }
}
```

- `hits.total.value` is the number of participants in the index.
- Each bucket's `doc_count` is the number of participants who have that
  keyword.
- To list the buckets alphabetically instead of by count, add
  `"order": { "_key": "asc" }` inside `terms`.

## Match keywords with any part left out (wildcards)

`_conditions` is a `keyword` field, so the `wildcard` query works on it: `*`
matches any sequence of characters (including none) and `?` exactly one. Put
`*` where a part should be ignored:

| Ignore | Pattern |
|---|---|
| the status | `*\|ONESELF\|MONDO:0005148` |
| the relationship | `PRESENT\|*\|MONDO:0005148` |
| the concept | `PRESENT\|ONESELF\|*` (same as the prefix `PRESENT\|ONESELF\|`) |
| status and relationship | `*\|*\|MONDO:0005148` |

In query strings, `|` is an ordinary character and needs no escaping (the
backslashes in this table are only for the Markdown table).

This finds participants with `MONDO:0005148` for themselves, whatever the
status, and shows which statuses they have:

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "wildcard": { "_conditions": "*|ONESELF|MONDO:0005148" }
  },
  "aggs": {
    "by_status": {
      "terms": {
        "field": "_conditions",
        "size": 100,
        "include": "[^|]*\\|ONESELF\\|MONDO:0005148"
      }
    }
  }
}
```

- `query.wildcard` matches participants with any keyword ending in
  `|ONESELF|MONDO:0005148`.
- `include` keeps only the matching keywords in the buckets, so you see one
  bucket per status (`ABSENT|...`, `PRESENT|...`, `UNKNOWN|...`,
  `HISTORICAL|...`). It's a regular expression, not a wildcard pattern: write
  `[^|]*` for "any part" and `\\|` for a literal `|` (in JSON, `\\|` is the
  regular expression `\|`). Without `include`, the buckets would list every
  condition of those participants.
- A participant with two of these keywords (e.g. `ABSENT` and `UNKNOWN`) is
  counted once in `hits.total.value` and once in each of its buckets, so the
  buckets can add up to more than the total.

The other parts work the same way. Ignoring the relationship:

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "wildcard": { "_conditions": "ABSENT|*|MONDO:0006809" }
  },
  "aggs": {
    "by_relationship": {
      "terms": {
        "field": "_conditions",
        "size": 100,
        "include": "ABSENT\\|[^|]*\\|MONDO:0006809"
      }
    }
  }
}
```

Ignoring both the status and the relationship, to see every combination for
one concept:

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "wildcard": { "_conditions": "*|*|MONDO:0006809" }
  },
  "aggs": {
    "combinations": {
      "terms": {
        "field": "_conditions",
        "size": 100,
        "include": "[^|]*\\|[^|]*\\|MONDO:0006809"
      }
    }
  }
}
```

Other options:

- **Case-insensitive**: `"wildcard": { "_conditions": { "value": "present|oneself|*", "case_insensitive": true } }`.
- **`*` crosses part boundaries.** `PRESENT*5148` matches
  `PRESENT|ONESELF|MONDO:0005148`: the `*` covers `|ONESELF|MONDO:000`. Keep
  the `|` separators in the pattern so each `*` stays within one part. For
  strict part boundaries, use `regexp` (below).
- **Speed.** A pattern that starts with `*` or `?` has to check every
  distinct `_conditions` value. With tens of distinct values that's
  instant; with millions it gets slow. Clusters that set
  `search.allow_expensive_queries: false` reject such queries.

### Regular expressions

The `regexp` query takes the same regular expressions as `include`, which
allows strict parts and alternatives. Participants with `PRESENT` or
`HISTORICAL` conditions, for themselves, for any `MONDO:00051…` concept:

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "regexp": { "_conditions": "(PRESENT|HISTORICAL)\\|ONESELF\\|MONDO:00051[0-9]+" }
  },
  "aggs": {
    "matches": {
      "terms": {
        "field": "_conditions",
        "size": 100,
        "include": "(PRESENT|HISTORICAL)\\|ONESELF\\|MONDO:00051[0-9]+"
      }
    }
  }
}
```

- In a regular expression, `|` means "or", so a literal `|` is `\\|` in JSON.
  `(PRESENT|HISTORICAL)` is an alternative; `[^|]*` is one part, never
  crossing a separator.
- A `regexp` query must match the whole keyword. There's no need for `^` or
  `$` (they aren't supported anyway).

## Count conditions rather than participants

The examples above count participants. To count the conditions themselves
(how many condition records there are per concept), aggregate over the
nested `condition` objects. Each condition is its own nested document.

```
GET participant_1/_search
{
  "size": 0,
  "aggs": {
    "conditions": {
      "nested": { "path": "condition" },
      "aggs": {
        "by_concept": {
          "terms": { "field": "condition.condition_concept", "size": 100 },
          "aggs": {
            "participants": { "reverse_nested": {} }
          }
        }
      }
    }
  }
}
```

- `nested` switches the aggregation to the nested `condition` documents.
  Its `doc_count` is the total number of conditions.
- `by_concept` groups the conditions by `condition_concept`. A bucket's
  `doc_count` is the number of conditions with that concept.
- `reverse_nested` goes back to the participant documents, so
  `participants.doc_count` is the number of distinct participants with at
  least one condition with that concept.
- To group by status, relationship and concept like `_conditions` does,
  nest `terms` aggregations on `condition.condition_status` and
  `condition.relationship_to_participant` inside `by_concept`.

## Duplicate keywords in `_conditions`

If a participant has the same keyword twice in `_conditions` (two conditions
with the same status, relationship and concept), it still counts once:

- `terms` aggregations count documents, so `doc_count` is the number of
  participants with the keyword, not the number of occurrences.
- Elasticsearch stores a `keyword` field's values for aggregations as a sorted
  set per document, so `["A", "A", "B"]` is aggregated as `{A, B}`.
  `value_count` and `cardinality` see the de-duplicated values too.
- `keyword` fields don't record how often a term occurs in a document, so a
  duplicate doesn't change matching or scores either.
- The duplicates are still in `_source`, the JSON returned with each hit.

To count occurrences, use the nested `condition` aggregation above.
