I want a python script that generates DDL for postgresql from a linkml schema.

The generated DDL should be in separate files for each type of DDL. Create tables in one file,
primary keys in another file, FKs in another, and any remaining constraints in the last file. Make
the file names, table names, constraint names, etc. as self-descriptive as possible based on the
linkml schema to help with relating the DDL to the schema.

I want to optionally indicate if enum field should be treated as varchar columns and the enums are
represented in the DDL.

The script should also take a path to the linkml schema, relative from current directory or full
path.

The generated DDL should go into a folder named after the schema file name and this folder should be
under schema/pg-ddl.

Ask me any questions related to this before implementing the script. I am assuming the script can be
generic and used with different schemas. If that makes it more difficult, you can assume that the
script, for now, will only work with the schema/linkml/bdchm.yaml schema.

- Use table per concrete class.
- Some slots point to an abstract class or a class with subclasses (Condition.associated_evidence →
  Entity, ObservationSet.observations → Observation, which may be a MeasurementObservation). Skip
  FK, add comment.
- Multivalued slots should be stored with Arrays + join tables
- For enum slots, Default: `CREATE TYPE ... AS ENUM` per enum (in its own file, before tables). With
  --enums-as-varchar: varchar columns plus CHECK (col IN (...)) constraints in the constraints file.
- names should be snake_case, unquoted 
- use the default search_path for postresql schema name
- Add the not null constraints to a separate constraint file.