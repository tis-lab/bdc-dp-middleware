#!/usr/bin/env bash
# Checks that the committed Java in model/src/generated matches the SDL in
# model/src/main/resources/graphql/model. It regenerates the sources (the same
# as `./mvnw -Pgenerate generate-sources` in model/), then fails if they differ
# from what git has committed, including new or deleted files.
#
# Usage: bin/check-model-drift.sh
#   Exit 0: no drift. Exit 1: drift (the regenerated files are left in place,
#   ready to review and commit). Exit 2: generating failed.
#
# Codegen doesn't validate the schemas (it accepts an unknown type name);
# ModelSchemaTest, run by `./mvnw verify`, does.
#
# Regenerating overwrites model/src/generated in the working tree. Anything
# uncommitted there is reported as drift.
#
# Keep Bash 3.2 compatible (macOS).
set -euo pipefail

bin_dir="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
model_dir="$(cd -P "$bin_dir/../model" && pwd)"
generated_rel="src/generated"

cd "$model_dir"
if ! ./mvnw -q -B -Pgenerate generate-sources; then
  echo "check-model-drift.sh: generating sources failed." >&2
  exit 2
fi

# Shows modified, deleted and untracked files compared with HEAD.
changes="$(git status --porcelain --untracked-files=all -- "$generated_rel")"
if [ -n "$changes" ]; then
  echo "check-model-drift.sh: generated sources don't match the committed ones:" >&2
  echo "$changes" >&2
  echo "Review with: git -C \"$model_dir\" diff -- $generated_rel" >&2
  echo "then commit the regenerated files with the schema change." >&2
  exit 1
fi

echo "No drift: $model_dir/$generated_rel matches the schemas."
