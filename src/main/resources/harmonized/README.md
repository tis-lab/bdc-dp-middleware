# harmonized-index.json

A pre-built index of BDC harmonized variables, read once at startup by
`HarmonizedVariableLibrary`. Generated using Claude, not hand-edited.

## Where it comes from

`tools/build_harmonized_index.py` reads the transformation specs in
[RTIInternational/NHLBI-BDC-DMC-HV](https://github.com/RTIInternational/NHLBI-BDC-DMC-HV)
and folds them into two views:

| Key         | One record per                                 | Fields                                                         |
|-------------|------------------------------------------------|----------------------------------------------------------------|
| `variables` | variable name, folded across study directories | `name`, `label`, `domain`, `studyCount`, `studies`, `concepts` |
| `concepts`  | ontology CURIE, inverted from the above        | `id`, `studyCount`, `studies`, `variableNames`                 |

`counts` and `generated` at the top of the file record what the run produced and when.

## Refreshing it

```bash
git clone --depth 1 https://github.com/RTIInternational/NHLBI-BDC-DMC-HV.git
python tools/build_harmonized_index.py \
    --repo ./NHLBI-BDC-DMC-HV \
    --out src/main/resources/harmonized/harmonized-index.json
mvn test
```

Diff the JSON before committing. The counts moving is normal as harmonization work
continues; a count collapsing to zero means the repository changed shape and the script
needs updating. `HarmonizedVariableLibraryTest` asserts the totals, so a structural change
fails the build rather than silently returning nothing.

## Why an index rather than the spec files

The middleware previously read all 826 YAML files at startup: 7.8 MB in the jar, parsed on
every boot. The index is about 100 KB and parsed once. The trade-off is a generated
artifact that can drift from its source, which is why the script is checked in beside it
and the tests assert the counts.

## The one rule worth knowing

**A concept counts only the studies whose own file assigns it.** Several studies can share
a variable file name while assigning different codes — `hyperten` is harmonized in seven
studies, but only one of them assigns `MONDO:0005149`. Crediting a code with every study
that has a file of that name overstated 41% of rows when the extraction was first written
that way.

So `variables[].studyCount` and `concepts[].studyCount` answer different questions and will
differ. The first is "how widely is this variable harmonized"; the second is "how many
studies assign this code".

## What is deliberately not here

dbGaP `pht`/`phv` accessions, BDCHM classes, data types, units and prose definitions all
exist in the source and are excluded to keep the first version small. Adding any of them is
a change to the script and the `BdcVariable` record; it breaks no client, since adding a
field to a GraphQL type is backward compatible.
