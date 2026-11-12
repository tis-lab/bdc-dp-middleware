# Schema provenance

- `upstream/summary.yaml`: `schema/summary.yaml` from study-palette
- `summary.yaml`: identical to that pinned upstream snapshot; uses optional multivalued
  `focus` in place of `seed`. No local schema deviations.
- `terms.yaml`: existing checked-in term schema.
- `../synthetic/bdchm.yaml`: RTIInternational/NHLBI-BDC-DMC-HM
- `summary-validation-pins.json`: SHA-256 hashes for the pinned dependencies.

The GraphQL SDL retains a deprecated seed field returning null for compatibility. This is
transport-only; actual mapper payloads serialize focus, never seed. CI validates full mapper
payloads, not arbitrary GraphQL field selections.