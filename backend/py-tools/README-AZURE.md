# Migrating to Azure AI Search

What changes if the participant index moves from Elasticsearch (ES) to Azure AI Search,
with a focus on the queries in [ES-EXAMPLES.md](ES-EXAMPLES.md), and on whether they can
return the same aggregation values.

**Status of this document.** Nothing here has been run on an Azure service. The Azure
behavior comes from Microsoft's documentation, read on 2026-11-05 (see
[Sources](#sources)). The "expected" numbers are what `participant_1` returns today in
ES, with 1,000 participants. Azure queries in this document are untested translations, so
run the [checks](#what-to-test-on-a-real-service) before relying on them. At the time of
writing, the current stable REST API is `2026-04-01`, and the current preview is
`2026-08-01-preview`.

## Summary

**There's one real blocker for the aggregation values: counts of nested records.**
Azure facets always count the parent document, so a facet on an observation field counts
participants, never observations. Azure has no equivalent of ES's `nested` aggregation,
which counts each nested record. No facet setting changes this, in the stable or the
preview API. These numbers need a workaround outside a single facet query:
[The blocker: counts of nested records](#the-blocker-counts-of-nested-records).

Every number that counts **participants** can be reproduced exactly:

| Need | In Azure | API |
|---|---|---|
| Filters with AND, OR, NOT, including conditions on the same nested record | `filter`, with `any` | Stable |
| Total matching participants | `count: true` | Stable |
| Participants per value of a field, including a nested field | Facets, with `count:0` | Stable |
| Participants per age range | Facets, with `values` | Stable |
| Breakdown under each study | One request per study | Stable |
| Breakdown under each study, in one request | Hierarchical facets | Preview only |
| `reverse_nested` counts | Not needed: that's what a facet on a nested field already counts | Stable |

Two more things must change before anything can run:

- **`_conditions` isn't a valid Azure field name**, because a name must start with a letter.
- **The full mapping has 53 nested fields, and Azure allows 40 complex collections.** The
  data uses only 7 of them today, so the index can be cut down. See
  [Index and schema issues](#index-and-schema-issues).

## Which aggregation values can be reproduced

This goes through the numbers in
[Filter by condition, then break down by study, age, demography and observation type](ES-EXAMPLES.md#filter-by-condition-then-break-down-by-study-age-demography-and-observation-type),
for `PRESENT|ONESELF|MONDO:0005148`, using `Example Study Two`.

| ES number | Counts | Expected | Azure | How |
|---|---|---|---|---|
| `hits.total.value` | Participants | 170 | **Same** | `@odata.count` with `count: true` |
| `by_study` bucket `doc_count` | Participants | 88 | **Same** | Facet on `member_of_research_study/id` |
| `age_ranges` buckets | Participants | 9, 9, 14 | **Same** | Facet with `values`, under a filter on the study |
| `conditions` buckets | Participants | 41 keywords, first 88 | **Same** | Facet on the renamed field, with `count:0` |
| `ethnicity`, `race`, `sex` buckets | Demography records | `sex`: 54 and 34 | **Same for this data only** | A facet counts participants. Each participant has one demography record, so the numbers agree. |
| `demography.doc_count` | Demography records | 88 | **Same for this data only** | The same reason: use the study's participant count |
| `observation_type` bucket `doc_count` | Observations | 418 | **Blocked** | See [the blocker](#the-blocker-counts-of-nested-records) |
| `participants.doc_count` (`reverse_nested`) | Participants | 88 | **Same** | Facet on `measurement_observation/observation_type` |
| `measurement_observation.doc_count` | Observations | 4,180 | **Blocked** | See [the blocker](#the-blocker-counts-of-nested-records) |

"Same for this data only" matters. If a participant ever has two demography records with
different values, ES counts two records, and Azure counts one participant in each bucket.

The bucket's meaning also changes. In ES, `observation_type` buckets count observations and
need `reverse_nested` for people. In Azure, the plain facet already gives people, so
porting the query field by field without noticing would label 88 as if it were 418.

## The blocker: counts of nested records

### What's blocked

Any `doc_count` under a `nested` aggregation that counts records rather than participants:

| In ES-EXAMPLES.md | Example | Expected |
|---|---|---|
| Count conditions rather than participants | Conditions per concept | Varies by concept |
| Observation-type breakdown | Observations of one type, in Study Two, for one condition | 418 |
| Observation-type breakdown | All observations, in Study Two, for one condition | 4,180 |
| Understanding `reverse_nested` | Observations with a value of at least 6.5, over all participants | 651 |

The Microsoft documentation says this directly: "The document counts returned in the facet
results are calculated for the parent document (a hotel), not the subdocuments in a complex
collection (rooms)". For a hotel with 20 suites, a facet on room type counts 1, not 20.

### Why the preview features don't remove it

- **Hierarchical facets** still count the parent. The documentation says so in its
  hierarchy examples.
- **`metric: sum`** on a collection subfield adds up that subfield's values over the parent
  documents. It sums numeric values. It doesn't count the records of one type.
- **`metric: cardinality`** returns an approximate count of distinct values, using
  HyperLogLog. It doesn't count records either, and it isn't exact.

### Workarounds

All of these can give the exact numbers. None was tested.

| Option | How it works | Exact? | Suits | Costs |
|---|---|---|---|---|
| **A. Count in the application** | Run the filter, page through all matching participants (up to 1,000 per page), select only the fields needed, and count in code. | Yes | Small data, or occasional queries | Time grows with matching participants. Slow for interactive use on large data. |
| **B. Count in PostgreSQL** | `pg-load-data` already holds the same data in tables. Use SQL `GROUP BY` for these numbers, and Azure for search. | Yes | Reporting, and anything with many breakdowns | The application needs a database, and the two stores must be kept in sync. |
| **C. A second index, one document per record** | For example, one document per observation, with the participant id and study copied onto it. Facets there count observations. | Yes | High query volume on record counts | About 38,000 more documents for observations alone, plus more code. There are no joins, so a filter on participants must first fetch their ids from the main index, and pass them with `search.in`. |
| **D. Precomputed count fields** | Add a numeric field per type to each participant, such as `obs_count_omop_4184637`, and add them up with `metric: sum`. | Yes, if the types are known in advance | A small, fixed set of types: there are 10 here | Preview only. It needs a schema change for each new type. It can't count records selected by value (651), unless that field is also precomputed. |

**Recommendation:** decide first whether you need the record counts at all. Participant counts
are often what a cohort search needs. If you do need them, start with **A** for this data size, and move to
**B** when many users or larger data make that slow. Avoid building the design on **D**,
because it depends on a preview feature.

## Other differences that affect the values

### No sub-aggregations in the stable API

ES nests aggregations under `by_study`. In the stable API "facets are one layer deep", so you
send one request per study:

1. Facet on the study to get the studies and their participant counts.
2. For each study, add it to the filter and request the other facets.

This gives the same values, but costs one more request per study. Requests are independent,
so a change to the index between them could make the numbers disagree slightly.

**Preview alternative.** Hierarchical facets can express it in one request:
`member_of_research_study/id > (age_at_index,values:16437|20089|23742|29220 ; condition_keys,count:0 ; demography/sex,count:0)`.
Preview features have no service-level agreement, may change, and aren't recommended for
production. Microsoft has published no date for general availability, according to a Microsoft
moderator's answer from March 2026. The moderator's advice was to keep facet query building
behind your own code, so that it can be changed.

### Set `count:0`, or the numbers can be wrong

This is the easiest mistake to miss, because nothing reports it. The default is 10 buckets.
Each Azure index is split into shards, and each shard reports only its top values, so
Microsoft warns that a smaller `count` "may not be accurate", and that documents can be missed
or under-counted. `count:0` means no limit, and gives accurate counts at the cost of latency.

| ES response field | In Azure |
|---|---|
| `sum_other_doc_count` | No equivalent, so you can't detect cut-off values. Use `count:0`. |
| `doc_count_error_upper_bound` | No equivalent. Use `count:0`. |

The ES examples use `"size": 100`, which isn't a safe translation here: `count:100` is only
exact if there are at most 100 distinct values. This index uses one ES shard, so ES counts
are exact today. In Azure, you don't choose to have a single shard.

### Age ranges work, with different buckets

`age_at_index,values:16437|20089|23742|29220` uses the same boundaries as ES: each range
includes its lower bound and excludes its upper bound, so ranges don't overlap. The
differences:

- There are five buckets, not three: Azure adds one below 16437 and one from 29220 up. Ignore
  those two.
- The ranges can't be named `45-55`. Buckets have only `from`, `to` and `count`, so the
  application adds the labels.
- Ranges must be contiguous. Separate ranges with gaps between them need extra boundaries,
  and the unwanted buckets ignored.
- `values` can't be combined with `count` or `sort`.

### `include` and `exclude` on buckets

| ES | Stable API | Preview |
|---|---|---|
| `"exclude": ["X"]` | Request with `count:0`, and remove it in code | `excludeTermFilter` |
| `"include": "regex"` | Request with `count:0`, and filter in code | `includeTermFilter` |
| `filter` aggregation inside `nested` (`selected_types`) | Request all types, and keep the ones wanted | `includeTermFilter`, with the same result |

The values are the same either way, because the buckets' counts don't change.

### Keyword duplicates

ES counts a participant once for a keyword, even when `_conditions` repeats it. Azure's
documentation says facets count parent documents, and describes string collections as an
inverted index, so the same result is expected. It isn't stated explicitly for duplicates.
See [What to test](#what-to-test-on-a-real-service).

## The examples, one by one

The translations use `condition_keys` as the new name for `_conditions`, and the exact-match
`filter` form. `search: "*"` and `top: 0` give counts with no documents.

### Filter translations

These should return the same participants, because Azure correlates conditions inside one
`any`, the same way ES does inside one `nested`.

| ES | Azure `filter` |
|---|---|
| `term` on `_conditions` | `condition_keys/any(k: k eq 'PRESENT\|ONESELF\|MONDO:0005148')` |
| `terms` (OR on one field) | `condition_keys/any(k: search.in(k, 'A,B', ','))`, or `k eq 'A' or k eq 'B'` |
| `bool.filter` (AND) | `A and B` |
| `bool.should` with `minimum_should_match: 1` (OR) | `A or B` |
| `must_not` (NOT) | `not condition_keys/any(k: k eq 'A')` |
| `nested` with one type | `measurement_observation/any(o: o/observation_type eq 'OMOP:4184637')` |
| `nested` with type and value, on the same record | `measurement_observation/any(o: o/observation_type eq 'OMOP:4184637' and o/value_quantity/value_decimal ge 6.5)` |
| `minimum_should_match: 2` of 3 | No equivalent. Write it out: `(A and B) or (A and C) or (B and C)` |

The [AND and OR example](ES-EXAMPLES.md#combine-conditions-and-observation-types-with-and-and-or)
becomes the following filter. It should match 23 participants, 10 and 13 per study:

```
condition_keys/any(k: k eq 'PRESENT|ONESELF|MONDO:0005148')
and condition_keys/any(k: k eq 'PRESENT|ONESELF|MONDO:0005009' or k eq 'PRESENT|ONESELF|MONDO:0005252')
and measurement_observation/any(o: o/observation_type eq 'OMOP:4152194' or o/observation_type eq 'OMOP:4154790')
and measurement_observation/any(o: o/observation_type eq 'OMOP:4184637')
```

| Customized filter in ES-EXAMPLES.md | Expected | Azure |
|---|---|---|
| Conditions AND a type with a value of at least 6.5 | 18 | Same pattern as above |
| One condition OR an observation of at least 6.5 | 443 | `A or measurement_observation/any(...)` |
| Two observations with values, both required | 202 | Two separate `any` clauses |
| At least 2 of 3 conditions | 5 | Written out |
| One condition AND NOT another | 160 | `A and not ...` |

The "two types in one `nested`" mistake carries over: two `eq` on one range variable, joined
with `and`, match no record.

Rules that differ:

- Inside `any`, a filter can only refer to that collection's own fields (no "free variables").
  Put conditions on the participant outside it.
- On a string collection, `any` allows only `eq`, `search.in`, and `or`. So
  `k eq 'A' and k eq 'B'` is rejected: write two `any` clauses.
- Inside `any` on a complex collection, nearly anything is allowed, including ranges.
- `eq` is exact and case-sensitive, like `term` on a `keyword` field.

### Example by example

| Section of ES-EXAMPLES.md | Can the values be reproduced? | Notes |
|---|---|---|
| Count the other conditions | **Yes** | Filter, facet with `count:0`, and remove the keyword in code. |
| Count within a group, using a prefix | **Yes** | See [patterns](#wildcard-prefix-and-regular-expression-queries). |
| Count every condition keyword | **Yes** | Facet with `count:0`. Add `sort:value` for alphabetical order. |
| Match keywords with parts left out | **Yes, with changes** | See [patterns](#wildcard-prefix-and-regular-expression-queries). |
| Regular expressions | **Yes, with changes** | See [patterns](#wildcard-prefix-and-regular-expression-queries). |
| Count conditions rather than participants | **Partly** | Participants: yes, 1 facet. Conditions per concept: [blocked](#the-blocker-counts-of-nested-records). |
| Break down by study, age, demography and type | **Partly** | Everything except observation counts: see the [table above](#which-aggregation-values-can-be-reproduced). |
| AND and OR filters | **Yes** | The filters translate. The breakdown has the same limits as the row above. |
| Common mistakes | Doesn't apply | `minimum_should_match` has no equivalent, so that mistake can't happen. The `selected_types` counts are blocked, because they count observations. |
| Understanding `reverse_nested` | **Partly** | 415 participants: yes. 651 observations, 80, and 8: blocked. The feature itself isn't needed. |
| Duplicate keywords | **Expected yes** | To be tested. |
| `_count` compared with `_cat/indices` | Doesn't apply | `@odata.count` counts participants. |

### The main example in Azure, with the stable API

First, the total and the studies:

```
POST /indexes/participant_1/docs/search?api-version=2026-04-01
{
  "search": "*",
  "top": 0,
  "count": true,
  "filter": "condition_keys/any(k: k eq 'PRESENT|ONESELF|MONDO:0005148')",
  "facets": ["member_of_research_study/id,count:0"]
}
```

Expected: `@odata.count` 170, with buckets of 88 and 82.

Then, once per study:

```
POST /indexes/participant_1/docs/search?api-version=2026-04-01
{
  "search": "*",
  "top": 0,
  "count": true,
  "filter": "condition_keys/any(k: k eq 'PRESENT|ONESELF|MONDO:0005148') and member_of_research_study/id eq '56fb5011-8e17-5cfa-a4f3-068837ea4b50'",
  "facets": [
    "age_at_index,values:16437|20089|23742|29220",
    "condition_keys,count:0",
    "demography/ethnicity,count:0",
    "demography/race,count:0",
    "demography/sex,count:0",
    "measurement_observation/observation_type,count:0"
  ]
}
```

Expected for this study: `@odata.count` 88; age buckets of 9, 9 and 14 between the
boundaries; and `measurement_observation/observation_type` with 88 for each of the 10 types.
That 88 corresponds to ES's `participants.doc_count`, not its `doc_count` of 418.

### Wildcard, prefix and regular expression queries

These need the most change, because Azure's filter has no pattern matching. There are two ways.

**Prefer filters on the condition's own fields, where the pattern is only leaving parts out.**
`condition` is already in the document, with each part as its own field, so it needs no
pattern matching, and `*` can't cross a part boundary:

| ES pattern on `_conditions` | Azure `filter` |
|---|---|
| `*\|ONESELF\|MONDO:0005148` | `condition/any(c: c/relationship_to_participant eq 'ONESELF' and c/condition_concept eq 'MONDO:0005148')` |
| `PRESENT\|ONESELF\|*` | `condition/any(c: c/condition_status eq 'PRESENT' and c/relationship_to_participant eq 'ONESELF')` |
| `*\|*\|MONDO:0006809` | `condition/any(c: c/condition_concept eq 'MONDO:0006809')` |

**Use a full Lucene query for real patterns,** such as part of a code. It's a different
mechanism, with several traps:

- The field must be `searchable`, with an analyzer that keeps the whole keyword as one token.
  The default analyzer would split it at `|`.
- Pattern queries skip query analysis and match the stored tokens exactly. Microsoft's rules say
  a regular expression is "Lower-case only", so the analyzer should also lowercase, and queries
  must be written in lowercase. `PRESENT` becomes `present`.
- `|` and `:` are special characters that may need escaping.
- A leading `*` isn't allowed outside a regular expression. `*|ONESELF|...` must be written as
  `/.*\|oneself\|.../`.
- `search.ismatch` can be used in a filter, but not inside `any`. That's fine for `condition_keys`,
  because each keyword holds all three parts of one condition.
- Wildcards and regular expressions are limited to 1,000 expansions, and are slow.

```
search.ismatch('/(present|historical)\\|oneself\\|mondo:00051[0-9]+/', 'condition_keys', 'full', 'any')
```

The JSON and Lucene escaping here are the likeliest thing in this document to need adjusting.

Facet buckets are a separate matter: this filter selects participants, and the facet still lists
all their keywords, so narrow the buckets as in [`include` and `exclude`](#include-and-exclude-on-buckets).

## Index and schema issues

Measured from the current mapping (`schema/es/20261029-022946__participant.json`) and the 1,000
exported documents:

| Item | This project | Azure limit | Result |
|---|---|---|---|
| Field names starting with `_` | `_conditions` is in use. 17 `_class`-style fields are in the mapping. | "First character must be a letter" | **Must change.** Rename them in the exporter and mapping. |
| Nested fields | 53 in the mapping; 7 with data | 40 complex collections per index, on every tier | **Must change.** Leave out unused classes, and generate only what's needed. |
| Fields | 854, not counting ES's 157 `.text` subfields | 1,000 (100 on Basic services created before April 3, 2024) | Fits. 68 have data. |
| Depth | 7 | 10 | Fits |
| Records in complex collections, per document | At most 68 | 3,000 | Fits, with room for more data |
| Document size | 8 to 27 KB | About 16 MB | Fits |
| Document key | UUIDs | Letters, numbers, `-`, `_`, `=` | Fits |
| Index name | `participant_1` | Lowercase letters, numbers, `-`, `_` | Fits |
| Total size | 21.4 MB of JSON | Free tier: 50 MB of index | **Too tight.** The index is usually larger than the source, so plan on Basic or higher. |

The Serverless tier is in preview, has a 1 GB index limit and no aliases, so it isn't a
production option.

Other mapping differences:

| ES mapping | Azure | Effect |
|---|---|---|
| `nested` | `Collection(Edm.ComplexType)` | Filters are correlated. Facets count the parent. |
| `object` | `Edm.ComplexType` | Direct |
| `keyword` | `Edm.String`, `filterable`, `facetable`, not `searchable` | Direct |
| `keyword` plus `.text` | One field with both `searchable` and `filterable` | Simpler: no subfield. Remove `.text` from queries. |
| Multivalued `keyword` | `Collection(Edm.String)` | Must be declared as a collection in advance. |
| `integer`, `double`, `boolean` | `Edm.Int32`, `Edm.Double`, `Edm.Boolean` | Direct |
| `date` | `Edm.DateTimeOffset` | **Must have a time zone** (`...Z` or `+hh:mm`). No current data uses these 18 fields, but the exporter's dates would need checking. |
| `dynamic: strict` | Fields are fixed by the schema | Similar |
| `dynamic: false` on 15 polymorphic slots | No equivalent | Every field sent must be defined, so the exporter must strip undeclared fields. No current data uses these slots. |
| `_source` | `retrievable` | Only defined fields come back. |

- **Set attributes deliberately.** `filterable`, `facetable` and `sortable` are set when a field is
  created, and changing them requires a rebuild. Each one uses storage, and in REST strings are
  filterable and facetable by default, so turn them off for fields like `id` and `description`.
- **Fielded text search isn't correlated.** `Rooms/Type:deluxe AND Rooms/Description:view` can match
  two different records. Only `filter` with `any` is correlated. ES queries using `nested` with
  text matching on `.text` lose this.

## Tools and workflow

Nothing in the ES tools can be reused against Azure, because the API, index format and client
differ. Everything up to the participant documents stays.

| Tool | Change |
|---|---|
| `linkml-pg-ddl`, `pg-*`, `harmonized-*` | None |
| `pg-export-participants` | Small: rename `_`-prefixed fields, and format dates if they're ever used. |
| `participant_postprocess.py` | Rename `_conditions`, and replace `es_properties` with Azure field definitions. |
| `es-participant-mapping` | Replace. Generate an Azure index definition, leaving out unused classes. |
| `es-recreate-index` | Replace, with the `azure-search-documents` client or REST. Aliases are generally available since `2026-04-01`. |
| `es-load-participants` | Replace. A batch holds at most 1,000 documents and 16 MB. Today's 500 documents of at most 27 KB fit. |
| `--delete-missing` | Reimplement by paging through the keys. |
| `bin/full-reload.sh` | Update the last two steps. |
| `bin/kibana-start.sh`, `bin/kibana-stop.sh`, [DOCKER-VPN.md](DOCKER-VPN.md) | Not needed for Azure. The portal's Search Explorer runs queries, but there's no equivalent of Kibana's dashboards. |
| [ES-EXAMPLES.md](ES-EXAMPLES.md) | Rewrite for Azure, once the design is settled. |

- **Local development.** ES runs in Docker today. I found no official local emulator for Azure AI
  Search, so tests would need a real service.
- **Authentication.** `ES_USERNAME` and `ES_API_KEY` become an Azure admin key, query key or
  Microsoft Entra ID.

## What to test on a real service

Load the same 1,000 documents, and compare with ES:

1. **The main example:** 170, then 88 and 82; the age buckets; and each study's facets, using both
   `count:0` and the default `count`, to see how the default behaves.
2. **The filters:** 23, 18, 443, 202, 5 and 160.
3. **Duplicate keywords:** a participant with the same keyword twice should count once.
4. **Facets on nested fields:** `demography/race` and `measurement_observation/observation_type`
   should count each participant once. This is documented, but the design depends on it.
5. **Pattern queries:** the analyzer, case handling and escaping, using `search.ismatch`.
6. **Hierarchical facets,** if you consider them: whether `count:0` under a parent gives exact
   counts, which the documentation doesn't state, and how they behave with several child facets.
7. **The record-count option you choose:** 418, 4,180 and 651, compared with ES.
8. **Latency** of `count:0` on a realistic data size, because Microsoft warns about it.

## Sources

Microsoft Learn, read on 2026-11-05:

- [Add facets to a query](https://learn.microsoft.com/en-us/azure/search/search-faceted-navigation): facet parameters, shards and accuracy
- [Faceted navigation examples](https://learn.microsoft.com/en-us/azure/search/search-faceted-navigation-examples): hierarchies, aggregations and facet filters (preview)
- [Model complex data types](https://learn.microsoft.com/en-us/azure/search/search-howto-complex-data-types): facets count the parent document
- [OData collection filters](https://learn.microsoft.com/en-us/azure/search/search-query-understand-collection-filters): correlation
- [Troubleshooting collection filters](https://learn.microsoft.com/en-us/azure/search/search-query-troubleshoot-collection-filters): what `any` allows
- [Full-text search functions](https://learn.microsoft.com/en-us/azure/search/search-query-odata-full-text-search-functions): `search.ismatch`
- [Lucene query syntax](https://learn.microsoft.com/en-us/azure/search/query-lucene-syntax): wildcards and regular expressions
- [Service limits](https://learn.microsoft.com/en-us/azure/search/search-limits-quotas-capacity)
- [Naming rules](https://learn.microsoft.com/en-us/rest/api/searchservice/naming-rules)
- [Supported data types](https://learn.microsoft.com/en-us/rest/api/searchservice/supported-data-types)
- [What's new](https://learn.microsoft.com/en-us/azure/search/whats-new): API versions and feature status
- [Timeline for hierarchical facets GA (Microsoft Q&A, March 2026)](https://learn.microsoft.com/en-us/answers/questions/5806902/timeline-for-hierarchical-facets-ga-in-azure-ai-se): not an official roadmap
