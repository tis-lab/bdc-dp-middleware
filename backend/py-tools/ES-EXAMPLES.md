# Elasticsearch query examples

Queries for the participant index, ready to paste into Kibana's Dev Tools
console. They assume the index created by `es-recreate-index` and loaded by
`es-load-participants` (see [README.md](README.md)). Replace `participant_1`
with your index or alias name. For how they translate to Azure AI Search, see
[README-AZURE.md](README-AZURE.md).

The examples use `_conditions`, the keyword array added by the
`add_condition_keys` post-processor. It holds one
`condition_status|relationship_to_participant|condition_concept` string per
condition, for example `PRESENT|ONESELF|MONDO:0005148`. Later examples also use
`member_of_research_study`, the ResearchStudy inlined in each document (from
`harmonized-add-studies`), and `age_at_index` (from `harmonized-add-ages`).

All examples set `"size": 0`, so the response has no documents, only the
aggregations and `hits.total.value`, the number of matching participants. It's
exact when the example sets `"track_total_hits": true`.

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
  least one condition with that concept. See [Understanding `reverse_nested`](#understanding-reverse_nested).
- To group by status, relationship and concept like `_conditions` does,
  nest `terms` aggregations on `condition.condition_status` and
  `condition.relationship_to_participant` inside `by_concept`.

## Filter by condition, then break down by study, age, demography and observation type

Finds the participants with one condition. For each study they belong to, it
counts them by age range, and counts their `_conditions` keywords, the
`ethnicity`, `race` and `sex` of their `demography` records, and the
`observation_type` of their `measurement_observation` records.

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "bool": {
      "filter": [
        { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" } }
      ]
    }
  },
  "aggs": {
    "by_study": {
      "terms": { "field": "member_of_research_study.id", "size": 100 },
      "aggs": {
        "age_ranges": {
          "range": {
            "field": "age_at_index",
            "keyed": true,
            "ranges": [
              { "key": "45-55", "from": 16437, "to": 20089 },
              { "key": "55-65", "from": 20089, "to": 23742 },
              { "key": "65-80", "from": 23742, "to": 29220 }
            ]
          }
        },
        "conditions": {
          "terms": { "field": "_conditions", "size": 100 }
        },
        "demography": {
          "nested": { "path": "demography" },
          "aggs": {
            "ethnicity": { "terms": { "field": "demography.ethnicity", "size": 100 } },
            "race": { "terms": { "field": "demography.race", "size": 100 } },
            "sex": { "terms": { "field": "demography.sex", "size": 100 } }
          }
        },
        "measurement_observation": {
          "nested": { "path": "measurement_observation" },
          "aggs": {
            "observation_type": {
              "terms": { "field": "measurement_observation.observation_type", "size": 100 },
              "aggs": {
                "participants": { "reverse_nested": {} }
              }
            }
          }
        }
      }
    }
  }
}
```

The response, shortened to one study and the first buckets:

```json
{
  "hits": { "total": { "value": 170, "relation": "eq" } },
  "aggregations": {
    "by_study": {
      "buckets": [
        {
          "key": "56fb5011-8e17-5cfa-a4f3-068837ea4b50",
          "doc_count": 88,
          "age_ranges": {
            "buckets": {
              "45-55": { "from": 16437.0, "to": 20089.0, "doc_count": 9 },
              "55-65": { "from": 20089.0, "to": 23742.0, "doc_count": 9 },
              "65-80": { "from": 23742.0, "to": 29220.0, "doc_count": 14 }
            }
          },
          "conditions": {
            "buckets": [
              { "key": "PRESENT|ONESELF|MONDO:0005148", "doc_count": 88 },
              { "key": "ABSENT|ONESELF|MONDO:0005044", "doc_count": 72 }
            ]
          },
          "demography": {
            "doc_count": 88,
            "ethnicity": {
              "buckets": [
                { "key": "OMOP:38003564", "doc_count": 84 },
                { "key": "OMOP:38003563", "doc_count": 4 }
              ]
            },
            "race": {
              "buckets": [
                { "key": "OMOP:8527", "doc_count": 53 },
                { "key": "OMOP:8516", "doc_count": 18 }
              ]
            },
            "sex": {
              "buckets": [
                { "key": "OMOP:8532", "doc_count": 54 },
                { "key": "OMOP:8507", "doc_count": 34 }
              ]
            }
          },
          "measurement_observation": {
            "doc_count": 4180,
            "observation_type": {
              "buckets": [
                { "key": "OMOP:3000905", "doc_count": 418, "participants": { "doc_count": 88 } },
                { "key": "OMOP:3007070", "doc_count": 418, "participants": { "doc_count": 88 } }
              ]
            }
          }
        }
      ]
    }
  }
}
```

- `query.bool.filter` selects the participants, and every aggregation counts
  only those. To require several conditions, add more `term` clauses to the
  array. A filter doesn't calculate relevance scores, which aren't needed here.
- `by_study` makes one bucket per study. Its `doc_count` is the number of the
  selected participants in that study. `member_of_research_study` is a plain
  object, not nested, so `terms` can use its fields directly. For readable
  bucket keys, use `member_of_research_study.name` instead of `.id`.
- `age_ranges` runs within each study bucket, and counts the participants whose
  `age_at_index` is in each range. The field is in days, so the ages are converted
  with `ceil(years * 365.25)`: 45 is 16437, 55 is 20089, 65 is 23742 and 80 is
  29220.
- `from` is included and `to` isn't, so the ranges don't overlap: someone aged
  exactly 55 is only in `55-65`. In years, the ranges are 45 to under 55, 55 to
  under 65, and 65 to under 80. To include people aged 80, set the last `to` to
  29586, the first day of age 81.
- Participants outside all the ranges aren't in any bucket, so the buckets can add
  up to less than the study's `doc_count`, as they do above (32 of 88).
  `"keyed": true` returns the buckets as an object named by `key`; without it,
  they're an array.
- `age_at_index` is on the participant document, so `age_ranges` needs no `nested`
  step. The values come from `harmonized-add-ages`: they're made up, and the data
  must be loaded and indexed after that tool has run. Otherwise every bucket is 0.
- `conditions` runs within each study bucket. It lists every condition keyword
  of those participants, including the one filtered on, whose `doc_count` equals
  the study's. Add `"exclude": ["PRESENT|ONESELF|MONDO:0005148"]` inside `terms`
  to leave it out.
- `demography` is needed because that field is mapped as `nested`: without the
  `nested` step, the `terms` aggregations under it find no values. `conditions`
  stays outside that step, because `_conditions` is on the participant document.
- Inside `demography`, a `doc_count` counts demography records, not
  participants. Each participant has one such record in this data, so the two
  numbers are the same. If a participant could have several, add
  `"aggs": { "participants": { "reverse_nested": {} } }` inside a `terms`
  aggregation to count distinct participants, as in
[Count conditions rather than participants](#count-conditions-rather-than-participants).
- `race` is multivalued in the schema, so a record with two races would count
  in both buckets, and the `race` buckets could add up to more than the
  `doc_count`. In this data, no record has more than one.
- `measurement_observation` also needs the `nested` step, and it runs within each
  study bucket. Its `doc_count` is the number of those participants' observations,
  and a type's `doc_count` is the number of observations of that type.
- A participant has many observations, so `reverse_nested` is included here:
  `participants.doc_count` is the number of distinct participants with at least
  one observation of that type. Above, 88 participants have 418 observations of
  `OMOP:3000905`. See [Understanding `reverse_nested`](#understanding-reverse_nested).
- `size` limits how many buckets each `terms` returns, not how many documents
  are counted, so a bucket's `doc_count` is complete even with more than 100
  participants. If `sum_other_doc_count` is above 0, some values didn't fit;
  raise `size`.
- The `ethnicity`, `race` and `sex` values are stored as they are in the data,
  as CURIEs such as `OMOP:8507`.

## Combine conditions and observation types with AND and OR

The same breakdown as the previous example, with a more complex filter. It selects
participants who match this expression:

```
    PRESENT|ONESELF|MONDO:0005148
AND (PRESENT|ONESELF|MONDO:0005009 OR PRESENT|ONESELF|MONDO:0005252)
AND (an observation of type OMOP:4152194 OR of type OMOP:4154790)
AND an observation of type OMOP:4184637
```

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "bool": {
      "filter": [
        { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" } },
        {
          "bool": {
            "should": [
              { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005009" } },
              { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005252" } }
            ],
            "minimum_should_match": 1
          }
        },
        {
          "nested": {
            "path": "measurement_observation",
            "query": {
              "terms": { "measurement_observation.observation_type": ["OMOP:4152194", "OMOP:4154790"] }
            }
          }
        },
        {
          "nested": {
            "path": "measurement_observation",
            "query": {
              "term": { "measurement_observation.observation_type": "OMOP:4184637" }
            }
          }
        }
      ]
    }
  },
  "aggs": {
    "by_study": {
      "terms": { "field": "member_of_research_study.id", "size": 100 },
      "aggs": {
        "age_ranges": {
          "range": {
            "field": "age_at_index",
            "keyed": true,
            "ranges": [
              { "key": "45-55", "from": 16437, "to": 20089 },
              { "key": "55-65", "from": 20089, "to": 23742 },
              { "key": "65-80", "from": 23742, "to": 29220 }
            ]
          }
        },
        "conditions": {
          "terms": { "field": "_conditions", "size": 100 }
        },
        "demography": {
          "nested": { "path": "demography" },
          "aggs": {
            "ethnicity": { "terms": { "field": "demography.ethnicity", "size": 100 } },
            "race": { "terms": { "field": "demography.race", "size": 100 } },
            "sex": { "terms": { "field": "demography.sex", "size": 100 } }
          }
        },
        "measurement_observation": {
          "nested": { "path": "measurement_observation" },
          "aggs": {
            "observation_type": {
              "terms": { "field": "measurement_observation.observation_type", "size": 100 },
              "aggs": {
                "participants": { "reverse_nested": {} }
              }
            }
          }
        }
      }
    }
  }
}
```

With the example data, 23 participants match: 10 in `Example Study One` and 13 in
`Example Study Two`. The `aggs` are unchanged from the previous example, so only the
`query` is explained here.

### How the filter maps to the expression

| In the expression | In the query |
|---|---|
| `AND` between the four lines | The four clauses of the outer `bool.filter` array. A document must match them all. |
| `A OR B`, on one field | A `bool` with both in `should`, and `"minimum_should_match": 1` (line 2), or `terms` with a list of values (line 3). |
| A condition | `term` on `_conditions`, which is on the participant document. |
| An observation of a type | `nested` on `measurement_observation`, because each observation is its own nested document. |
| Parentheses | Another `bool`, placed as one clause of the `bool` around it. |

### Building your own filter

1. Write the expression first, with parentheses, as above.
2. Start from `{ "bool": { "filter": [ ] } }`, and add one clause for each part joined
   by `AND` at the outermost level.
3. Write each part with these pieces. They can go inside each other to any depth:

| You want | Use |
|---|---|
| Every clause must match (`AND`) | `{ "bool": { "filter": [ A, B ] } }` |
| At least one must match (`OR`) | `{ "bool": { "should": [ A, B ], "minimum_should_match": 1 } }` |
| At least N must match | The same, with `"minimum_should_match": N` |
| One of several values of a field (`OR`) | `{ "terms": { "_conditions": [ "X", "Y" ] } }`, which is shorter than `should` |
| None may match (`NOT`) | `"must_not": [ A ]`, next to `filter` in a `bool` |
| A condition | `{ "term": { "_conditions": "PRESENT\|ONESELF\|MONDO:0005148" } }`, or `wildcard` or `prefix` as in the sections above |
| An observation | `{ "nested": { "path": "measurement_observation", "query": ... } }` |

### Customized filters

Each of these replaces the `query` of the example, and the `aggs` stay as they are. The
count is the number of matching participants in the example data.

All 1,000 participants in the example data have an observation of all 10 types, so
**filtering on the type alone doesn't narrow the results here**: the observation lines
above don't change the count. Adding a value does, because it's checked on the same
observation as the type. This selects:
`MONDO:0005148 AND (MONDO:0005009 OR MONDO:0005252) AND (an OMOP:4184637 observation with a value of at least 6.5)`,
18 participants:

```json
{
  "bool": {
    "filter": [
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" } },
      { "terms": { "_conditions": ["PRESENT|ONESELF|MONDO:0005009", "PRESENT|ONESELF|MONDO:0005252"] } },
      {
        "nested": {
          "path": "measurement_observation",
          "query": {
            "bool": {
              "filter": [
                { "term": { "measurement_observation.observation_type": "OMOP:4184637" } },
                { "range": { "measurement_observation.value_quantity.value_decimal": { "gte": 6.5 } } }
              ]
            }
          }
        }
      }
    ]
  }
}
```

`OR` across different fields needs `should`, because `terms` only covers one field.
`MONDO:0005068 OR (an OMOP:4184637 observation of at least 6.5)`, 443 participants:

```json
{
  "bool": {
    "should": [
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005068" } },
      {
        "nested": {
          "path": "measurement_observation",
          "query": {
            "bool": {
              "filter": [
                { "term": { "measurement_observation.observation_type": "OMOP:4184637" } },
                { "range": { "measurement_observation.value_quantity.value_decimal": { "gte": 6.5 } } }
              ]
            }
          }
        }
      }
    ],
    "minimum_should_match": 1
  }
}
```

Two different observations, both required, need two `nested` clauses.
`(an OMOP:4184637 of at least 6.5) AND (an OMOP:4152194 of at least 140)`, 202
participants. They can be from different visits:

```json
{
  "bool": {
    "filter": [
      {
        "nested": {
          "path": "measurement_observation",
          "query": {
            "bool": {
              "filter": [
                { "term": { "measurement_observation.observation_type": "OMOP:4184637" } },
                { "range": { "measurement_observation.value_quantity.value_decimal": { "gte": 6.5 } } }
              ]
            }
          }
        }
      },
      {
        "nested": {
          "path": "measurement_observation",
          "query": {
            "bool": {
              "filter": [
                { "term": { "measurement_observation.observation_type": "OMOP:4152194" } },
                { "range": { "measurement_observation.value_quantity.value_decimal": { "gte": 140 } } }
              ]
            }
          }
        }
      }
    ]
  }
}
```

At least two of three conditions, 5 participants:

```json
{
  "bool": {
    "should": [
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005009" } },
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005252" } },
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005068" } }
    ],
    "minimum_should_match": 2
  }
}
```

`MONDO:0005148 AND NOT MONDO:0005009`, 160 participants:

```json
{
  "bool": {
    "filter": [
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" } }
    ],
    "must_not": [
      { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005009" } }
    ]
  }
}
```

### Common mistakes

- **Requiring two types inside one `nested`.** Everything inside one `nested` must match
  the same observation, and an observation has one type. This matches 0 participants,
  although all of them have both types. Use one `nested` per type, as in the example:

  ```json
  {
    "nested": {
      "path": "measurement_observation",
      "query": {
        "bool": {
          "filter": [
            { "term": { "measurement_observation.observation_type": "OMOP:4152194" } },
            { "term": { "measurement_observation.observation_type": "OMOP:4184637" } }
          ]
        }
      }
    }
  }
  ```

- **Leaving out `minimum_should_match`.** Next to `filter` in the same `bool`, `should`
  then becomes optional, and only affects scores. This silently matches all 170
  participants with `MONDO:0005148`, instead of the 23 with the `OR` applied. Always set
  it:

  ```json
  {
    "bool": {
      "filter": [
        { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" } }
      ],
      "should": [
        { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005009" } },
        { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005252" } }
      ]
    }
  }
  ```

- **Expecting the filter to limit the buckets.** The filter selects participants. The
  aggregations then count everything those participants have, so `observation_type` still
  lists all 10 types, and `conditions` lists all their conditions. To count only some
  types, put a `filter` aggregation inside the `nested` step:

  ```json
  "measurement_observation": {
    "nested": { "path": "measurement_observation" },
    "aggs": {
      "selected_types": {
        "filter": {
          "terms": { "measurement_observation.observation_type": ["OMOP:4152194", "OMOP:4154790"] }
        },
        "aggs": {
          "observation_type": {
            "terms": { "field": "measurement_observation.observation_type", "size": 100 },
            "aggs": {
              "participants": { "reverse_nested": {} }
            }
          }
        }
      }
    }
  }
  ```

- **Not checking the condition's other parts.** `PRESENT|ONESELF|MONDO:0005148` means the
  condition is present and is the participant's own. It doesn't match `ABSENT` or a family
  member's condition. To ignore those parts, use a `wildcard` as described in
  [Match keywords with any part left out](#match-keywords-with-any-part-left-out-wildcards).

## Understanding `reverse_nested`

Several examples above have `"participants": { "reverse_nested": {} }`. In short: it
turns a count of **records** into a count of **participants**.

### Why it's needed

Elasticsearch stores each participant as one main document, plus one hidden document
for each nested record: each observation, condition, visit and so on. An aggregation
counts one kind of document at a time, and moves between them like this:

```
participant documents                    observation documents
(_conditions, age_at_index, ...)         (observation_type, value_quantity, ...)

          ------------- "nested" goes down ------------>
          <------- "reverse_nested" comes back ---------
```

So under `nested`, every `doc_count` counts observations. A participant with 5
observations adds 5. `reverse_nested` goes back up from those observations to the
participants they belong to, and counts each participant once.

### A small example

This selects two participants and counts their observations by type:

```
GET participant_1/_search
{
  "size": 0,
  "track_total_hits": true,
  "query": {
    "terms": {
      "id": ["00106202-f0f8-57e6-b1d7-ba1dd8604541", "0189f803-b9ce-5d33-adab-69aac6fc970c"]
    }
  },
  "aggs": {
    "observations": {
      "nested": { "path": "measurement_observation" },
      "aggs": {
        "by_type": {
          "terms": { "field": "measurement_observation.observation_type", "size": 100 },
          "aggs": {
            "participants": { "reverse_nested": {} }
          }
        }
      }
    }
  }
}
```

The first participant has 5 observations of type `OMOP:4184637`, and the second has 3.
The response, shortened to that type:

```json
{
  "hits": { "total": { "value": 2, "relation": "eq" } },
  "aggregations": {
    "observations": {
      "doc_count": 80,
      "by_type": {
        "buckets": [
          { "key": "OMOP:4184637", "doc_count": 8, "participants": { "doc_count": 2 } }
        ]
      }
    }
  }
}
```

| Number | What it counts | Here |
|---|---|---|
| `hits.total.value` | Participants the query selected | 2 |
| `observations.doc_count` | All the observations of those participants | 80 |
| The bucket's `doc_count` | Observations of that type | 8 (5 + 3) |
| `participants.doc_count` | Distinct participants those 8 observations belong to | 2 |

`participants` is just a name chosen in the query. `{}` means "go back to the participant
document".

### What the number means

`participants.doc_count` answers: **among the participants selected so far, how many have
at least one record in this bucket?**

- **It depends on everything above it.** Only participants the query selected, and in the
  enclosing buckets, are counted. Under `by_study`, it's within that study.
- **It doesn't add up across buckets.** A participant with several types is counted once in
  each. In the example, the 10 types' numbers add up to 20, for 2 participants.
- **It doesn't say how many records each has.** 8 observations from 2 participants could be
  5 + 3 or 7 + 1. Dividing gives only an average.
- **It's most useful with a narrower bucket.** When every participant has every type, as
  here, it just equals the number of participants. It says more when only some records
  qualify, as in the next query.

### Counting participants with a qualifying record

This counts observations of `OMOP:4184637` with a value of at least 6.5, and the
participants who have them:

```
GET participant_1/_search
{
  "size": 0,
  "aggs": {
    "observations": {
      "nested": { "path": "measurement_observation" },
      "aggs": {
        "high_values": {
          "filter": {
            "bool": {
              "filter": [
                { "term": { "measurement_observation.observation_type": "OMOP:4184637" } },
                { "range": { "measurement_observation.value_quantity.value_decimal": { "gte": 6.5 } } }
              ]
            }
          },
          "aggs": {
            "participants": {
              "reverse_nested": {},
              "aggs": {
                "by_study": { "terms": { "field": "member_of_research_study.name" } }
              }
            }
          }
        }
      }
    }
  }
}
```

The result is 651 observations from 415 participants: `high_values.doc_count` is 651, and
`participants.doc_count` is 415, of whom 229 are in `Example Study Two` and 186 in
`Example Study One`. So 415 participants had such a value at least once, and many had it
more than once.

`by_study` shows the second use of `reverse_nested`. A field on the participant document,
such as `member_of_research_study.name`, can only be aggregated from the participant
level. Directly under `nested`, the same `terms` returns **no buckets and no error**,
because observation documents don't have that field. If an aggregation under `nested`
comes back empty, check for this.

`reverse_nested` only works inside a `nested` aggregation. Outside one, Elasticsearch
returns an error.

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
