# BDC Data Portal - Summary Integration

Date: 10/23/2026

## 1. Purpose and release boundary

This guide extends Version 1's term-search scope with autocomplete, harmonized-variable search results, and the summary API. It defines how the UI should request and interpret the available information without presenting precomputed examples as dynamically calculated cohorts.

The middleware supports three different kinds of information:

| Source                                    | Information returned                                                                       | What it does not establish                                                        |
|-------------------------------------------|--------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| Monarch Knowledge Graph                   | Biomedical concept identifiers, labels, categories, descriptions and synonyms              | BDC participant counts or data availability                                       |
| Bundled RTI transformation specifications | Harmonized-variable definitions, associated concept identifiers and study mapping metadata | Accessible datasets, participant counts or completed harmonization of actual data |
| Bundled summary documents                 | Precomputed, study-level aggregate counts and breakdowns                                   | Newly calculated counts for arbitrary include/exclude combinations                |

All business operations use **POST `/graphql`**.

## 2. Term search

### Request

`terms` trims the input, accepts 1-200 Unicode code points, and validates `limit` from 1-100 and `offset` from 0-10,000. Defaults are 20 and 0.

Version 2 returns a union: each item is either a `BdcVariable` or a `Term`. The Version 1 selection `items { id label ... }` is therefore deprecated.

This section describes the currently deployed term schema. A pending change replaces BdcVariable with BdcConcept, keyed on the ontology CURIE rather than the transformation-spec file name: name becomes conceptType, label becomes conceptLabel, domain becomes bdchmType, and bdcVariableCount becomes bdcConceptCount. The summary API is unaffected. This document will be updated when that lands.
```graphql
query ResolveTerms($query: String!, $limit: Int!, $offset: Int!) {
  terms(query: $query, limit: $limit, offset: $offset) {
    items {
      __typename
      ... on BdcVariable {
        name
        label
        domain
        studyCount
        studies
        concepts
      }
      ... on Term {
        id
        label
        category
        description
        synonyms
        displayLabel
        bdc { studyCount studies variableNames }
      }
    }
    total
    limit
    offset
    hasMore
    bdcVariableCount
  }
}
```

Variables:

```json
{"query":"Hypertension","limit":20,"offset":0}
```

### Result interpretation

| Field                        | Meaning                                                                                              |
|------------------------------|------------------------------------------------------------------------------------------------------|
| `__typename`                 | Distinguishes `BdcVariable` from `Term`                                                              |
| `Term.id`                    | Stable concept CURIE; the value retained for concept selection                                       |
| `Term.label`, `displayLabel` | Preferred name; display label also includes the identifier                                           |
| `Term.category`              | Provider category, such as a Biolink category                                                        |
| `description`                | Optional description; null means unavailable                                                         |
| `synonyms`                   | Optional list; preserve the distinction between null and an empty list                               |
| `Term.bdc.studyCount`        | Number of distinct studies with specifications assigning the exact concept                           |
| `Term.bdc.variableNames`     | Related harmonized-variable names; not a promise that each full variable appears on the current page |
| `BdcVariable.name`           | Variable key; not a concept CURIE                                                                    |
| `BdcVariable.concepts`       | Concept CURIEs assigned by its specifications                                                        |
| `BdcVariable.studyCount`     | Number of studies represented in that variable's mapping specifications                              |
| `total`                      | Matching harmonized variables plus the provider's matching concept total                             |
| `bdcVariableCount`           | Number of matching variables in the complete merged result set                                       |
| `hasMore`                    | Whether another page is available                                                                    |

Variables appear first in the overall result set, followed by Monarch concepts. They are not pinned again on every page.

Use a composite UI key such as `Term:<id>` or `BdcVariable:<name>`. A suitable count label is **“Studies with mapping specifications”**, not “Studies with available participant data.” Zero mapping coverage can also occur when the mapping index is disabled; it is not proof that BDC has no data.

## 3. Autocomplete

Autocomplete returns Monarch suggestions only. It does not return harmonized variables or BDC coverage, and it does not accept `limit` or `offset`.

```graphql
query SuggestTerms($query: String!) {
  autocomplete(query: $query) {
    items { id label category description synonyms displayLabel }
    total
  }
}
```

```json
{"query":"Hyperten"}
```

Descriptions may be null. The returned total is the provider's total when supplied, otherwise the number of suggestions returned.

## 4. Discover summary sources and supported concepts

```graphql
query SummaryOptions {
  summarySources
  summaryConcepts(sourceId: "t2d") {
    curie
    label
    vocabulary
    parents
  }
}
```

Only the two bundled summary documents are used:

| `sourceId`   | Document          | Study one | Study two | Sum of study counts |
|--------------|-------------------|----------:|----------:|--------------------:|
| `t2d`        | `t2d.yaml`        |       480 |       460 |                 940 |
| `t2d-female` | `t2d-female.yaml` |       230 |       250 |                 480 |

These are example source-population counts. They are not distinct-person totals across studies, and they are not counts of participants with diabetes marked `PRESENT`. The female source overlaps the base source: do not add their totals.

The `t2d` catalog includes:

| CURIE           | Label                             |
|-----------------|-----------------------------------|
| `MONDO:0005148` | Type 2 diabetes mellitus          |
| `MONDO:0005015` | Diabetes mellitus                 |
| `MONDO:0005267` | Heart disorder                    |
| `MONDO:0024643` | Myocardial disorder               |
| `MONDO:0005068` | Myocardial infarction             |
| `MONDO:0005252` | Heart failure                     |
| `OMOP:4156660`  | Fasting blood glucose measurement |

The female source does not declare every concept in the base catalog.

Measurement sections are keyed by `observation_type`, a local enumeration value such as `FAST_GLUC_BLD`, while the term catalog identifies the same measurement as `OMOP:4156660`. The source documents declare no link between the two, so the middleware maps them internally. Selecting a measurement therefore uses its CURIE, not its `observation_type`. This mapping does not generalise beyond the bundled examples and is expected to be resolved in the source format.

## 5. Include/exclude behavior

**Include/exclude selects which concept summary sections are returned. It does not calculate a new cohort.**

| Input | Current behavior |
|---|---|
| Empty `include` | Start with all available concept sections |
| Multiple includes | Select sections matching any included concept or its local descendants: OR semantics |
| Excludes | Hide sections matching an excluded concept or its local descendants |
| Both lists | Apply inclusion, then remove excluded sections |
| Same ID in both lists | Validation error |
| Unknown ID for selected source | Validation error, not a zero-participant result |

Hierarchy expansion uses only `parents` in the selected summary's term catalog. It does not query Monarch for more descendants. Other relationships, such as disease–biomarker links, are not treated as parent/child relationships.

Selection applies to conditions, procedures, drug exposures and measurements. It leaves the source's study totals, demographics, age bins, term catalog, relations, seed and `filter` metadata unchanged.

Consequences for frontend:

- Including diabetes does not change a study's original `participant_count`.
- Excluding heart failure does not subtract its count from a heart-disorder parent or from the study total.
- Several matching concepts do not imply an intersection of participants.
- An empty concept-section array does not imply zero participants.
- Selecting `MONDO:0005015` can return the same sections as `MONDO:0005148`, because the local hierarchy places type 2 diabetes below diabetes mellitus.


## 6. Summary request and response contract

```graphql
query ReadSummary($sourceId: ID!, $include: [ID!]!, $exclude: [ID!]!) {
  summary(sourceId: $sourceId, include: $include, exclude: $exclude) {
    release
    seed
    filter
    min_count
    terms { curie label vocabulary parents }
    relations { subject predicate object relation_source }
    research_studies { id name research_project_type program principal_investigator }
    studies {
      research_study
      participant_count
      participants {
        id summarized_slot participant_count
        values { id participant_count bin_low bin_high bin_unit }
      }
      demographics {
        id summarized_slot participant_count
        values { id participant_count sex race ethnicity }
      }
      conditions {
        id condition_concept participant_count
        values { id condition_status participant_count record_count }
      }
      procedures {
        id procedure_concept participant_count
        values { id procedure_status participant_count }
      }
      drug_exposures {
        id drug_concept participant_count
        values { id participant_count }
      }
      measurements {
        id observation_type participant_count record_count
        values {
          id participant_count record_count
          bin_low bin_high bin_unit range_low range_high
        }
      }
    }
  }
}
```

```json
{"sourceId":"t2d","include":["MONDO:0005148"],"exclude":["MONDO:0005252"]}
```

The selection above asks for one level of nested `values`. These fields are recursive; request deeper levels if a future source requires them. GraphQL returns only the fields selected by the client. `summaryJson` returns the complete validated object, with the same arguments and field names, but gives up field-level GraphQL selection and typing. `summary` is a preferable operation to use for frontend.

### Relationship to `summary.yaml`

`summary.yaml` defines the source data model. The middleware reads and validates the summary data and exposes the summary-only subset through GraphQL. GraphQL adds the outer `data.summary` envelope; it does not change the meaning of the aggregate fields.

| Response field     | Meaning                                                                                      |
|--------------------|----------------------------------------------------------------------------------------------|
| `release`          | Source release identifier                                                                    |
| `seed`             | Source's seed concept; not an echo of the latest include list                                |
| `filter`           | Original summary IDs defining the source population; not the request's include/exclude lists |
| `min_count`        | Minimum count reported by the source; missing small cells must not be interpreted as zero    |
| `terms`            | Source term catalog, including local parents                                                 |
| `relations`        | Source relationships and their provenance                                                    |
| `research_studies` | Study metadata keyed by `id`                                                                 |
| `studies`          | Aggregate summaries keyed by `research_study`                                                |

Join `studies[].research_study` to `research_studies[].id`. Join concept CURIEs to `terms[].curie` for labels, with the raw identifier as a fallback when no catalog entry exists.

Field names in summary results are snake_case, including `participant_count`, `condition_concept`, `condition_status` and `min_count`. Term-search fields retain their declared camelCase names.

### Count interpretation

For the base example, study one has 480 source participants. Its diabetes summary has 470 participants with recorded condition status: 90 `PRESENT` and 380 `ABSENT`. The unreported difference must not be relabeled `ABSENT` or `UNKNOWN`. Study two has 80 `PRESENT`, 365 `ABSENT`, and 15 explicitly `UNKNOWN`.

Therefore, 940 is the sum of the two study population counts. The diabetes-positive counts are 90 and 80, respectively. Even their sum is not established as a count of unique people across studies.

Use a condition's `PRESENT` child count when displaying its positive count. A parent condition count includes recorded statuses and must not be labeled positive. Do not add parent and child counts, overlapping condition counts, or multi-valued demographic categories to derive a cohort size.

Absent arrays are normalized to `[]`; optional scalar values may be null. Display “Not reported” for missing information rather than replacing it with zero. Bins use inclusive lower and exclusive upper boundaries; a missing bound on a bin is open-ended. Parent summaries without boundaries are not themselves open-ended bins. `range_low` and `range_high` are laboratory reference bounds, not bin boundaries.

`Count` and `Decimal` are serialized as JSON numbers. Large counts beyond JavaScript's safe-integer range need a precision-preserving client strategy before they can be used exactly; the bundled example counts do not approach that limit.

## 7. UI test selections

Use the `ReadSummary` operation above. All rows below use `sourceId: "t2d"`.

| Include                             | Exclude             | Expected concept sections                                                     |
|-------------------------------------|---------------------|-------------------------------------------------------------------------------|
| `[]`                                | `[]`                | All available conditions and measurements                                     |
| `["MONDO:0005148"]`                 | `[]`                | Type 2 diabetes in both studies                                               |
| `["MONDO:0005015"]`                 | `[]`                | Same diabetes sections through local descendant expansion                     |
| `["MONDO:0005267"]`                 | `[]`                | Study one: myocardial infarction and heart failure; study two: heart disorder |
| `["MONDO:0005267"]`                 | `["MONDO:0005252"]` | Study one: myocardial infarction; study two: original heart-disorder summary  |
| `["MONDO:0005148","MONDO:0005068"]` | `[]`                | Study one: both concepts; study two: diabetes                                 |
| `[]`                                | `["MONDO:0005267"]` | Diabetes and glucose measurements                                             |
| `["OMOP:4156660"]`                  | `[]`                | Glucose measurement sections only                                             |
| `[]`                                | `["OMOP:4156660"]`  | Condition sections, with glucose measurements hidden                          |

The study counts remain 480 and 460 for every row. Procedures and drug-exposure arrays are empty in these examples. Select `t2d-female` with empty lists to test the other source; its study counts are 230 and 250.

## 8. Errors and client request handling

Always inspect GraphQL `errors`, including responses with HTTP 200. A failed operation is not an empty successful result. If several root fields are requested together, GraphQL may return partial data; the example helper below deliberately rejects the whole request when any errors are present.

| Error code                  | Interpretation                                                                      |
|-----------------------------|-------------------------------------------------------------------------------------|
| `BAD_USER_INPUT`            | Invalid search arguments, unsupported summary identifiers, or conflicting selection |
| `UPSTREAM_TIMEOUT`          | Knowledge Graph request exceeded its timeout                                        |
| `UPSTREAM_UNAVAILABLE`      | Knowledge Graph could not be reached or returned a non-success status               |
| `UPSTREAM_INVALID_RESPONSE` | Knowledge Graph response could not be safely mapped                                 |


Illustrative error, not a captured live response:

```json
{
  "data": {"summary": null},
  "errors": [{
    "message": "The selected concept is not supported by this summary source.",
    "path": ["summary"],
    "extensions": {"code": "BAD_USER_INPUT"}
  }]
}
```

## References

- [Version 1 term-results scope](bdc-term-results-scope-v1.md)
- [Monarch API documentation](https://api.monarchinitiative.org/v3/docs)
- [Monarch application repository](https://github.com/monarch-initiative/monarch-app/)
- [RTI transformation specifications](https://github.com/RTIInternational/NHLBI-BDC-DMC-HV/tree/main/priority_variables_transform)
- Implementation contracts: `src/main/resources/graphql/core/schema.graphqls` and `src/main/resources/graphql/core/summary.graphqls`.
- Summary source contract: `schema/summary.yaml`; deployed GraphQL schema remains authoritative for available operations and selections.
