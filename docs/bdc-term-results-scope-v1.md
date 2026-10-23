# BDC Data Portal - Term Search Response Scope, Version 1
**Scope:** Initial concept discovery, before harmonized-variable or study-data enrichment


## 1. Purpose

Define the information the middleware returns to the interface when a user searches for a biomedical term, such as “Hypertension.” The response must help the user distinguish candidate concepts and select the intended concept by its stable identifier.

This release resolves search text into candidate concepts. It does not determine whether BDC has participant data for those concepts, execute a cohort definition, or automatically choose a clinical interpretation for the user.

This is a proposed response contract and acceptance scope, not a claim that every requirement is already implemented in the middleware.


## 2. Request flow and source responsibility

**Interface → POST /graphql → middleware `terms` query → Knowledge Graph search → normalized concept results → interface.**

Monarch is the initial verified upstream API. A future BDC Knowledge Graph may replace or supplement it after its endpoint, schema, content and access requirements are confirmed. Do not assume Monarch and BDC currently provide identical fields or search behavior. Version 1 uses one configured provider; cross-provider merging is deferred.

The provider supplies biomedical concept metadata. The middleware validates requests, calls the provider, maps the response to a stable GraphQL contract, and reports failures. The interface presents candidates and retains the selected concept ID.

The provider (“Monarch”) and an ontology namespace (“MONDO” or “HP”) are different things: the namespace identifies the vocabulary of the concept, not which service answered the request.


## 3. Required response information

“Required” below refers to the proposed contract. Optional content must remain null when it was not supplied.

| Field | GraphQL type | Requirement and UI purpose |
|---|---|---|
| `id` | `ID!` | Required stable concept identifier, including its namespace, e.g. `HP:0000822`. Use as the selection key; never identify a concept by label alone. |
| `label` | `String!` | Required preferred name from the provider. Primary result title. |
| `category` | `String!` | Required provider category, e.g. a Biolink category. Display as a readable type badge without changing the raw value in the API. |
| `description` | `String` | Definition/description when supplied. Helps users distinguish similar concepts; otherwise null. |
| `synonyms` | `[String!]` | Synonyms when supplied. Null means unavailable; an explicitly empty list means no synonyms supplied in that list. Preserve source wording. |

### Result envelope

| Field | Type | Meaning |
|---|---|---|
| `items` | `[Term!]!` | Candidates in the current page, in relevance order. |
| `total` | `Int!` | Provider-reported total matching concepts. Not the current page size, a study count or a participant count. |
| `limit` | `Int!` | Requested, validated maximum page size. |
| `offset` | `Int!` | Requested, validated starting position. |
| `hasMore` | `Boolean!` | Whether additional candidates exist beyond this page. |


## 4. GraphQL request and contract

Proposed defaults and limits: trim query text; accept 1-200 Unicode code points; default `limit` 20 with allowed range 1-100; default `offset` 0 with allowed range 0-10,000. Reject blank input and out-of-range arguments with a structured error. These are middleware limits, not a claim about upstream limits.

```graphql
type Query {
  terms(query: String!, limit: Int! = 20, offset: Int! = 0): TermResults
}

type TermResults {
  items: [Term!]!
  total: Int!
  limit: Int!
  offset: Int!
  hasMore: Boolean!
}

type Term {
  id: ID!
  label: String!
  category: String!
  description: String
  synonyms: [String!]
}
```

The nullable `terms` field allows an upstream failure to be represented as `data.terms: null` with GraphQL errors. Client must check `errors` even when HTTP status is 200.

```graphql
query ResolveTerms($query: String!, $limit: Int!, $offset: Int!) {
  terms(query: $query, limit: $limit, offset: $offset) {
    items { id label category description synonyms }
    total limit offset hasMore
  }
}
```

```json
{"query":"Hypertension","limit":20,"offset":0}
```

**Illustrative success response - not a captured live response:

```json
{
  "data": {
    "terms": {
      "items": [{
        "id": "HP:0000822",
        "label": "Hypertension",
        "category": "biolink:PhenotypicFeature",
        "description": null,
        "synonyms": null
      }],
      "total": 1,
      "limit": 20,
      "offset": 0,
      "hasMore": false
    }
  }
}
```


## 5. Empty results and failures

A successful search with no matches returns `items: []`, `total: 0`, `hasMore: false`, and the requested `limit`/`offset`. An offset beyond the last page can also return an empty page with a nonzero total.

Proposed error codes, to be aligned with the middleware implementation:

| Code | Condition |
|---|---|
| `BAD_USER_INPUT` | Blank query, invalid length or pagination |
| `UPSTREAM_TIMEOUT` | Provider exceeded the configured timeout |
| `UPSTREAM_UNAVAILABLE` | Connection failure or provider service failure |
| `UPSTREAM_RATE_LIMITED` | Provider rejects excessive requests |
| `UPSTREAM_INVALID_RESPONSE` | Required fields or response structure invalid |

**Illustrative failure:**

```json
{
  "data": {"terms": null},
  "errors": [{
    "message": "Concept search is temporarily unavailable.",
    "path": ["terms"],
    "extensions": {"code": "UPSTREAM_UNAVAILABLE"}
  }]
}
```


## References

- [Monarch API documentation](https://api.monarchinitiative.org/v3/docs)
- [Monarch application repository](https://github.com/monarch-initiative/monarch-app/)