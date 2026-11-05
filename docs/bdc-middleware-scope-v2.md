# BDC Data Portal - Summary Integration

Updated: 2026-11-03

## 1. Purpose and release boundary

This guide extends Version 1's term-search scope with autocomplete, harmonized-variable search results, and the summary API. It defines how the UI should request and interpret the available information using live Elasticsearch summaries.

The middleware supports three different kinds of information:

| Source                                    | Information returned                                                                       | What it does not establish                                                        |
|-------------------------------------------|--------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| Monarch Knowledge Graph                   | Biomedical concept identifiers, labels, categories, descriptions and synonyms              | BDC participant counts or data availability                                       |
| Bundled RTI transformation specifications | Harmonized-variable definitions, associated concept identifiers and study mapping metadata | Accessible datasets, participant counts or completed harmonization of actual data |
| Elasticsearch participant index | Live study-level counts and supported breakdowns | Cross-study unique-person counts; unsupported measurement/age facets |

All business operations use **POST `/cb/query`**.

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

## 4. Errors and client request handling

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
