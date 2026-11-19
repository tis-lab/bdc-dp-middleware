# Portal VarLib schema: sources and derivation

Date: November 19, 2026. Related task: [BDC-Portal #104](https://github.com/tis-lab/BDC-Portal/issues/104).

## Purpose

`portal-varlib.yaml` defines the structure of variable-library metadata supplied to the Data Portal. It extends the upstream BDC Variable Library model, adds the portal's required fields and reference rules, and reuses the shared Study Palette term model. It is not a participant-data schema and does not calculate cohort counts.

The contract was proposed and reviewed in [study-palette PR #72](https://github.com/tis-lab/study-palette/pull/72), which is now merged. The middleware adopted the contract for validating producer catalogs in [middleware PR #27](https://github.com/tis-lab/bdc-dp-middleware/pull/27).

## Where the contract lives

| Location                                                                                                                       | Purpose                                             |
|--------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| [Study Palette contract](https://github.com/tis-lab/study-palette/blob/main/schema/proposals/portal-varlib/portal-varlib.yaml) | Shared reviewed contract and portal extensions      |
| [Middleware contract](https://github.com/tis-lab/bdc-dp-middleware/blob/dev/schema/portal-varlib/portal-varlib.yaml)           | Local copy used by the middleware catalog validator |

## External schema sources

### 1. BDC Variable Library - primary domain model

Repository: [linkml/bdc-variable-library](https://github.com/linkml/bdc-variable-library).

Source file: [src/bdc_variable_library/schema/bdc_variable_library.yaml](https://github.com/linkml/bdc-variable-library/blob/main/src/bdc_variable_library/schema/bdc_variable_library.yaml).

This provides `Entity`, `Variable`, `IntegratedVariable`, `CompoundVariable`, `SingleContinuousVariable`, `SingleCategoricalVariable`, `ResearchStudy`, and their slots and enums. It is the direct basis for the portal subclasses. In particular, `IntegratedVariable.integrates` already links to compound variables, and `Variable.associated_study` already references a research study.

### 2. LinkML Microschema Profile - supporting model

Repository: [linkml/linkml-microschema-profile](https://github.com/linkml/linkml-microschema-profile).

Source file: [src/linkml_microschema_profile/schema/linkml_microschema_profile.yaml](https://github.com/linkml/linkml-microschema-profile/blob/main/src/linkml_microschema_profile/schema/linkml_microschema_profile.yaml).

This defines the supporting microschema profile and composable data-element types.

### 3. Clinical Microschemas - supporting clinical definitions

Repository: [linkml/clinical-microschemas](https://github.com/linkml/clinical-microschemas).

Source file: [src/clinical_microschemas/schema/clinical_microschemas.yaml](https://github.com/linkml/clinical-microschemas/blob/main/src/clinical_microschemas/schema/clinical_microschemas.yaml).

This supplies clinical microschema definitions used by the upstream model.

### 4. Study Palette terms - shared concept representation

Source file: [study-palette/schema/terms.yaml](https://github.com/tis-lab/study-palette/blob/main/schema/terms.yaml).

Middleware copy: [schema/terms.yaml](https://github.com/tis-lab/bdc-dp-middleware/blob/dev/schema/terms.yaml).

The portal catalog's `concepts` collection uses the existing `Term` class. It supplies `curie`, `label`, `vocabulary`, and `parents`. The catalog validator requires concept identities for reference resolution; hierarchy is optional and is not inferred when missing.

### 5. LinkML standard types

All relevant schemas import `linkml:types`, supplied by the installed LinkML tooling. This provides standard scalar types such as strings, integers, and URI/CURIE values.

## How the portal classes were derived

The schema is a deliberate extension using LinkML `is_a` and `slot_usage`, not an inferred schema generated from sample JSON. The producer examples demonstrate the contract; they do not replace the upstream class definitions.

| Portal class                      | Upstream basis              | Portal-specific requirements or additions                                                                                                                                                     |
|-----------------------------------|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PortalIntegratedVariable`        | `IntegratedVariable`        | Requires identity, nonblank variable label, concept, BDCHM type and at least one `integrates` reference; narrows the target to portal compound variables; adds metadata provenance references |
| `PortalCompoundVariable`          | `CompoundVariable`          | Requires identity, nonblank label, concept, BDCHM type and study reference; narrows the study target; adds metadata provenance references                                                     |
| `PortalResearchStudy`             | `ResearchStudy`             | Requires identity and adds required `study_name`; does not add participant counts                                                                                                             |
| `PortalSingleContinuousVariable`  | `SingleContinuousVariable`  | Requires identity, nonblank variable name and study reference; adds provenance                                                                                                                |
| `PortalSingleCategoricalVariable` | `SingleCategoricalVariable` | Same portal identity, naming, study and provenance requirements for categorical variables                                                                                                     |
| `PortalVariableCatalog`           | New portal container        | Groups integrated variables, compounds, studies, concepts, provenance and optional single-variable definitions; requires schema version 3 and a release                                       |
| `PortalMetadataSource`            | New portal provenance class | Records a source identity, GitHub repository, 40-character revision and file path                                                                                                             |

Other source-variable fields, including `source_id`, `file_id`, `variable_name`, `source_variable_description`, `data_type`, and continuous-variable `unit`, come from the upstream VarLib model. They were not invented as portal attributes.

The catalog preserves separate identities for the concept, integrated variable, compound variable, source variable and study. Several integrated variables may represent the same concept. Study coverage is obtained through integrated-variable → compound-variable → study references, rather than treating the inherited singular `associated_study` as a multistudy list.
