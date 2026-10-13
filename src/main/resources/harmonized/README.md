# Vendored data — do not edit by hand

Copied from <https://github.com/RTIInternational/NHLBI-BDC-DMC-HV> (`main`).

| Path here | Source in that repository |
| --- | --- |
| `<STUDY>-ingest/*.yaml` | `priority_variables_transform/<STUDY>-ingest/` |
| `harmonized_vars.tsv` | `transform_assessment/harmonized_qaqc/harmonized_vars.tsv` |

As vendored: 826 ingest specs across 11 studies, folding into **179 distinct variables**, of which
168 carry at least one ontology concept and 130 have a label in the QA table.

The ingest specs are LinkML-Map transformation rules, not a catalogue. `HarmonizedVariableLibrary`
extracts five things from them — study, BDCHM class, ontology CURIE, `pht` and `phv` accessions —
and ignores the mapping machinery. They are nevertheless the required source, because they are the
only place MONDO and HP identifiers appear, and those are what Monarch returns.

`harmonized_vars.tsv` is an optional enrichment supplying label, definition, domain, data type and
unit. If it is absent the variables still load with those fields null.

## Refreshing

```powershell
git clone --depth 1 https://github.com/RTIInternational/NHLBI-BDC-DMC-HV.git
Copy-Item -Recurse -Force NHLBI-BDC-DMC-HV\priority_variables_transform\*-ingest `
  src\main\resources\harmonized\
Copy-Item -Force NHLBI-BDC-DMC-HV\transform_assessment\harmonized_qaqc\harmonized_vars.tsv `
  src\main\resources\harmonized\
mvn test
```

`HarmonizedVariableLibraryTest` asserts the variable count and the shape of several known
variables, so a refresh that changes the repository's structure fails the build rather than
silently returning nothing. Expect the count assertion to need updating when variables are
genuinely added.

## When the repository gets an API

It is in development and expected to grow one. When it does, replace the loader inside
`HarmonizedVariableLibrary` and delete this directory. `HarmonizedVariable`, `VariableMatch`,
`TermService` and the GraphQL schema do not change — the matching rules and the response contract
are independent of where the data is read from.

## If startup time or image size becomes a problem

Parsing 826 files at boot to produce 179 small records is wasteful but keeps a single source of
truth with no generated artifact to drift. The next step, if needed, is a build-time extraction to
one JSON index; because the loader is isolated behind this one class, that is a drop-in change.
