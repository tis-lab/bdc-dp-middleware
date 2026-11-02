# Developing the backend compose setup

Read [README.md](README.md) first. It covers day-to-day usage. This file explains
how the setup works and the things that break easily when you change it.

## Layout

```
backend/
├── .env                  # ES_VERSION (es build arg, kibana image tag), PG_VERSION (pg build arg)
├── .gitignore            # ignores data/*/* but keeps data/*/.gitkeep
├── README.md             # for users of the stack
├── DEV.md                # this file
├── LOG.md                # change log, newest first
├── CLAUDE.md             # guidance for Claude Code
├── bin/
│   ├── compose.sh        # wrapper: exports UID/GID, then exec docker compose "$@"
│   ├── clean-es.sh       # confirm, compose down, empty data/es, compose up -d --wait
│   ├── clean-pg.sh       # confirm, rm -sf pg, empty data/pg, compose up -d --wait pg
│   └── check-model-drift.sh  # regenerate model sources, fail if they differ from git
├── docker-compose.yml
├── data/
│   ├── es/.gitkeep       # bind mount -> /usr/share/elasticsearch/data
│   ├── kibana/.gitkeep   # bind mount -> /usr/share/kibana/data
│   └── pg/.gitkeep       # bind mount -> /var/lib/postgresql
├── model/                # GraphQL data model, a standalone Maven project (not a service)
│   ├── mvnw, .mvn/       # Maven wrapper
│   ├── pom.xml           # codegen only in the `generate` profile
│   └── src/
│       ├── main/resources/graphql/model/  # common/query/response .graphqls (source of truth)
│       ├── generated/java/                # generated, committed
│       └── test/java/                     # ModelSchemaTest
├── py-tools/             # uv project for Python tools (not a service)
│   ├── pyproject.toml    # uv_build backend; tools go under [project.scripts]
│   ├── .python-version   # 3.14
│   ├── uv.lock           # committed
│   └── src/py_tools/     # the package (empty)
├── es/                   # build context for the es service
│   ├── Dockerfile        # used by compose
│   ├── Dockerfile-OpenShift  # used by an OpenShift BuildConfig (no compose)
│   ├── root/             # copied onto / of the image (COPY root/ /)
│   │   └── usr/share/elasticsearch/config/elasticsearch.yml
│   └── root-openshift/   # copied after root/, by Dockerfile-OpenShift only
│       └── usr/share/elasticsearch/config/elasticsearch.yml
└── pg/                   # build context for the pg service
    ├── Dockerfile
    └── root/
        └── .gitkeep      # nothing to overlay yet; no init scripts (see "The pg image")
```

Each service that needs a custom image gets its own directory, named after the
service (`es/` for `es`). That directory is the service's build context, and
its `root/` subdirectory mirrors the image filesystem.

## The `es` image

- `FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}`. The
  `ARG` default in the Dockerfile is only a fallback; compose always passes
  `ES_VERSION` from `.env`. When you bump the version, update both places.
- `COPY root/ /` puts everything under `es/root` into the image.
- **Watch out:** `COPY` sets every directory it touches, including ones that
  already exist, to the host's owner and mode (root, 755). That broke ES
  startup once with `unable to create temporary keystore ... write permissions
  required for [/usr/share/elasticsearch/config]`. The `RUN` step after the
  `COPY` restores what the base image expects:
  - `config/` owned by `elasticsearch:root`
  - mode 775 on the ES home and `config/`
  - group permissions equal to user permissions inside `config/`

  If you add files under `es/root` that land in other existing directories ES
  writes to, restore those directories in the same way. Keep the `RUN` step
  targeted. A recursive chmod over all of `/usr/share/elasticsearch` copies the
  whole tree into a new layer.
- Configuration lives in `elasticsearch.yml` (in `root/`), not in environment
  variables. The exception is `ES_JAVA_OPTS`, which is set in compose (for
  OpenShift, see the next section). This
  file replaces the base image's `elasticsearch.yml`, so it must keep
  `network.host: 0.0.0.0`.

## The `es` image for OpenShift

`es/Dockerfile-OpenShift` builds the same image for an OpenShift BuildConfig
(`dockerStrategy`, `contextDir: backend/es`,
`dockerfilePath: Dockerfile-OpenShift`). There is no compose there, so the
settings that compose adds must be in the image or in the OpenShift resources:

| Compose                         | OpenShift                                                        |
|---------------------------------|------------------------------------------------------------------|
| `build.args: ES_VERSION`        | The `ARG` default (or `buildArgs` in the BuildConfig). Keep it in sync with `.env`. |
| `user` + `group_add: ["0"]`     | OpenShift runs a random uid in group 0. The image ends with a numeric `USER 1000:0` so `runAsNonRoot` can be checked. |
| `ES_JAVA_OPTS`                  | Not set. See below.                                              |
| `ulimits`                       | Not needed: the default nofile limit is high enough, and memlock matters only with `bootstrap.memory_lock`, which is off. |
| `ports`                         | `EXPOSE 9200 9300`, repeated from the base image because the console's Import from Git form reads ports only from the Dockerfile text. 9200 comes first, so it is the default target port. Publish with a Service; 9300 (transport) isn't needed for a single node. |
| `./data/es` bind mount          | A PVC at `/usr/share/elasticsearch/data`.                        |
| `depends_on: pg`                | Dropped. ES doesn't use pg.                                      |
| `healthcheck`                   | Kubernetes ignores `HEALTHCHECK`. Use a readiness probe on `/_cluster/health`. |

- **`root-openshift/`** is copied after `root/` and replaces
  `elasticsearch.yml` with a copy that adds `node.store.allow_mmap: false`.
  OpenShift nodes usually keep `vm.max_map_count` at 65530 and pods can't
  change it. Elasticsearch doesn't refuse to start because of it
  (`single-node` skips the bootstrap checks), but mmap-backed indices can fail
  later. ES has a single `elasticsearch.yml`, so shared settings are
  duplicated: **keep both copies in sync.** Both `COPY`s come before the
  restore `RUN` step, so it covers both.
- **Heap.** `ES_JAVA_OPTS` is deliberately not in the image. Without it, ES
  sizes the heap from the container's memory limit (half of it for a
  single node with all roles), so set the pod's memory limit to 4Gi to get
  the same 2 GB heap as compose. To fix the heap explicitly, set
  `ES_JAVA_OPTS` in the Deployment's `env` (`-Xms`/`-Xmx` equal, at most
  about half the limit). It overrides the automatic sizing.
- Tested with `docker run --user 1000770000:0 --memory 2g`: green, 1 GB heap,
  `allow_mmap: false`, keystore written to `config/`.

## The `pg` image

- `FROM postgres:${PG_VERSION}` (Debian). As with `es`, keep the `ARG` default
  in sync with `.env`.
- pgvector is installed with apt as `postgresql-${PG_MAJOR}-pgvector` from the
  PGDG repo that the base image already configures. `PG_MAJOR` is set by the
  base image. The pgvector version isn't pinned; it's whatever PGDG has at
  build time.
- `ENV POSTGRES_HOST_AUTH_METHOD=trust` is set in the Dockerfile. It's the one
  setting that isn't a file, because the entrypoint reads it only from the
  environment. It writes `host all all all trust` into `pg_hba.conf` at initdb
  time, so changing it later has no effect on an existing cluster.
- **No init scripts.** `/docker-entrypoint-initdb.d` is empty, so a new
  cluster has only the `postgres` superuser. The `dp` role, database,
  `vector` extension and `bdchm` schema are created by `pg-bootstrap` in
  `py-tools/`, a Python command run from the host as `postgres` (trust auth).
  Unlike init scripts, it can be run at any time, re-run safely (it only
  creates what's missing), and used from Windows. A failed run doesn't leave a
  half-initialized cluster that needs `clean-pg.sh`. The earlier
  `10-dp.sql`, `20-init-sql.sh` and `pg/init-sql/` bind mount were removed.
- `pg/root/` holds only `.gitkeep`, and `COPY root/ /` is kept for the
  directory convention. It writes nothing the server writes to, so there's no
  restore step.
- **Mount point:** since PostgreSQL 18, the official image puts `PGDATA` in
  `/var/lib/postgresql/<major>/docker` and declares `/var/lib/postgresql` as
  the volume. Compose mounts `./data/pg` there, not at the old
  `/var/lib/postgresql/data`. After a major-version bump, the entrypoint
  finds the old `data/pg/<old major>/docker/PG_VERSION` and exits with
  "there appears to be PostgreSQL data in ..." instead of starting a new,
  empty cluster next to it (`docker_error_old_databases`). The layout is
  meant for `pg_upgrade --link`. For local development, dump what you need
  and run `bin/clean-pg.sh`.
- Running as an arbitrary uid works: the entrypoint creates `PGDATA` inside
  the user-owned mount, and uses `nss_wrapper` when the uid has no
  `/etc/passwd` entry. `group_add: ["0"]` isn't needed by postgres but is kept
  for consistency with the other services.
- The healthcheck runs `pg_isready -h 127.0.0.1` over TCP. While the
  entrypoint initializes a new cluster, its temporary server listens only on
  the Unix socket, so a socket check would report ready before the real
  server has started.

## Start order

`es` depends on `pg` (`condition: service_healthy`) and `kibana` depends on
`es` (`service_healthy`), so `up` starts pg → es → kibana, and each one waits
for the previous one's healthcheck. pg's healthcheck passes only after its init
scripts have run, so a failed init blocks es and kibana. `depends_on` affects
only `up`/`start`: a running es keeps running if pg stops or is recreated.

## Running as the invoking user

`compose.sh` exports `UID` (a Bash shell variable that isn't exported by
default) and `GID=$(id -g)`. Then it runs
`exec docker compose --project-directory <backend dir> "$@"`:

- `exec` passes signals and exit codes through.
- All scripts in `bin/` must stay compatible with **Bash 3.2**, the version
  macOS ships, and with BSD tools. So: no `readlink -f` (missing before
  macOS 12.3), no associative arrays, no `${var,,}`, no `mapfile`, and no
  GNU-only flags. The project directory is found with
  `cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd` (the parent of `bin/`).
  To test under 3.2, run
  `docker run --rm -v "$PWD":/backend:ro bash:3.2 bash /backend/bin/compose.sh ...`
  with a stand-in `docker` placed ahead of the real one on `PATH`.
- `--project-directory` makes compose find `docker-compose.yml` and `.env`
  in `backend/`, whatever directory the script is run from. Arguments are
  not changed, so relative paths in them still resolve from the caller's
  directory.

Each service sets:

```yaml
user: "${UID:?run via bin/compose.sh}:${GID:?run via bin/compose.sh}"
group_add:
  - "0"
```

- The `:?` form makes plain `docker compose` fail with a clear message instead
  of quietly running as the wrong user.
- `group_add: ["0"]` is required. The Elastic images support running as an
  arbitrary uid (the OpenShift convention), but they depend on membership in
  group 0 to write to their group-root-writable directories, such as ES's
  `config/` for the keystore. Making root the *primary* group would also work,
  but files would then get group root. Adding it as a supplementary group
  means files belong to the user's own group.
- The Elastic images run as uid 1000 by default (postgres runs as 999). A developer whose uid is 1000 won't
  notice ownership bugs, so test with another uid, e.g.:

  ```bash
  docker run --rm --user 1234:1234 --group-add 0 \
    --tmpfs /usr/share/elasticsearch/data:uid=1234,gid=1234 \
    -e ES_JAVA_OPTS="-Xms512m -Xmx512m" backend-es:9.5.3   # tag = ES_VERSION
  ```

New services with bind-mounted data should follow the same pattern: add the
`user`/`group_add` block, add `data/<service>/.gitkeep`, and mount
`./data/<service>`.

### macOS

Docker Desktop on macOS (and similar VM-based tools) always maps files in
mounted folders to the Mac user, whatever uid the container runs as. Running
as the invoking user is harmless there (a Mac user is typically 501:20, which
was tested with both images) and still needed on Linux. So the `find data ...`
ownership check below only proves something on Linux. Both Elastic images
have arm64 builds, so Apple Silicon runs them natively. `vm.max_map_count` is
already set in Docker Desktop's VM.

## `clean-es.sh`

- Runs every compose command through `bin/compose.sh` and never calls
  `docker compose` or sets `UID`/`GID` itself. That way its services run with
  exactly the same user, group and project settings as when you use
  `compose.sh` directly. Any future script must follow the same rule.
- It removes `-y`/`--yes` from the arguments (wherever they appear) and passes
  the rest to `up -d --wait`. This means `up`'s own `-y` can't be passed
  through.
- Without `-y`, it needs a terminal for the prompt and exits 1 if there isn't one.
- It deletes the contents of `data/es` except `.gitkeep`. It never deletes the
  directory itself, so the bind-mount source stays owned by the user.
- It deliberately doesn't touch `data/kibana`. That folder holds only Kibana's
  instance `uuid` and scratch space for its headless browser. All Kibana state
  lives in `.kibana*` indices in ES.
- Uses `${up_args[@]+"${up_args[@]}"}` because Bash 3.2 with `set -u` treats an
  empty array as unbound.
- `--wait` waits for `es` to be healthy. Kibana has no healthcheck, so for it
  `--wait` only waits until the container is running.

## `clean-pg.sh`

Follows the same rules as `clean-es.sh` (compose only through `compose.sh`,
`-y` handling, keeps `.gitkeep` and the directory), with two differences:

- It stops and removes only the `pg` container (`rm --stop --force pg`) and
  starts only `pg` (`up -d --wait <args> pg`), because restarting ES would be
  slow for no reason. `es` has `depends_on: pg` (`service_healthy`), but that
  only affects start order: removing pg doesn't stop es, and `up ... pg` only
  touches pg. The dependency has no `restart: true`, so es isn't restarted
  either.
- Emptying `data/pg` removes every major-version subdirectory, so the next
  start runs initdb and the cluster has only `postgres`. The script doesn't
  `exec` the final `up`, so that it can print the `pg-bootstrap` command
  afterwards. It still exits with `up`'s status, through `set -e`.

## `model/` code generation

The Java in `model/src/generated/java` is generated from the SDL and committed.
How the pom keeps the normal build from running codegen:

- `build-helper-maven-plugin` (`add-source`) adds `src/generated/java` as a
  source root in every build.
- The codegen plugin is only in the `generate` profile. In that profile,
  `maven-clean-plugin` first deletes `src/generated/java` (phase `initialize`),
  so classes for removed or renamed types don't linger.
- `onlyGenerateChanged` is `false`. With the default `true`, the plugin's
  change manifest in `target/graphqlcodegen` makes it skip generation after
  the wipe, which leaves the directory empty.
- `autoAddSource` is `false`, because build-helper already adds the directory.
- `addGeneratedAnnotation` with `disableDatesInGeneratedAnnotation` puts a
  marker `@Generated` (codegen also generates the annotation class itself)
  without a timestamp on every class. Regenerating unchanged schemas is
  byte-identical, which the drift check depends on.
- Codegen doesn't validate the schemas: a field of an unknown type is
  generated without complaint. `ModelSchemaTest` builds the merged schema with
  graphql-java, using a stub `Query`, and catches that.

## `check-model-drift.sh`

- Runs `./mvnw -q -B -Pgenerate generate-sources` in `model/`, then
  `git status --porcelain --untracked-files=all -- src/generated`. Any output
  is drift: modified (`M`), deleted (`D`, a removed type) or new (`??`) files.
- Exit codes: 0 no drift, 1 drift, 2 generating failed (Maven's exit status is
  1, so it's mapped to 2 to keep it apart from drift).
- It compares against `HEAD`, so regenerated but uncommitted files count as
  drift. It leaves the regenerated files in place.
- It doesn't use compose, so the `compose.sh` rule doesn't apply.

## Security

Disabled on purpose (`xpack.security.*.enabled: false`) to keep local
development simple. Turning it on means:

- setting `ELASTIC_PASSWORD`
- giving Kibana a service account token (Kibana can't use the `elastic` user)
- updating the healthcheck to authenticate

## Verifying changes

```bash
bin/compose.sh config -q                     # compose file and interpolation are valid
bin/compose.sh up -d --build
docker inspect -f '{{.State.Health.Status}}' backend-es-1   # healthy
curl -s localhost:9200/_cluster/health                       # status green
curl -s -o /dev/null -w '%{http_code}\n' localhost:5601/api/status   # 200
docker inspect -f '{{.State.Health.Status}}' backend-pg-1   # healthy
(cd py-tools && uv run pg-bootstrap)        # role, database, extension, schema exist
bin/compose.sh exec pg psql -U dp dp -Atc "select extversion from pg_extension where extname='vector'"
bin/compose.sh exec es id                    # your uid/gid, plus group 0
bin/compose.sh exec pg id                    # same
find data ! \( -user $(id -u) -group $(id -g) \)   # should print nothing (meaningful on Linux only)
```

## Change log

Record every change to this setup in [LOG.md](LOG.md), newest entry first, with
a `## YYYY-MM-DD HH:MM TZ: <title>` heading.
