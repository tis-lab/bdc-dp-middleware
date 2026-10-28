# Backend services

A local Docker Compose stack with:

| Service  | Image                                                            | URL / address         |
|----------|------------------------------------------------------------------|-----------------------|
| `es`     | Custom image built from the official Elasticsearch image         | http://localhost:9200 |
| `kibana` | Official Kibana image                                            | http://localhost:5601 |
| `pg`     | Custom image built from the official PostgreSQL image + pgvector | `localhost:5432`      |

`es` and `kibana` use the Elastic Stack version set in `.env` (`ES_VERSION`).
`pg` uses `PG_VERSION` from the same file.

> **Local development only.** Security is disabled: there is no authentication
> and no TLS. PostgreSQL accepts any role without a password (`trust`). Don't expose these ports outside your machine.

## Requirements

- Docker with the Compose v2 plugin (`docker compose`)
- Bash (the scripts in `bin/` are Bash scripts)
- About 4 GB of memory for Docker (Elasticsearch has a 2 GB heap, plus Kibana
  and PostgreSQL)

Works on Linux (including WSL2) and macOS (Intel and Apple Silicon). On
macOS, use Docker Desktop (or OrbStack, Colima, etc.) and check that its VM
has at least 4 GB of memory in Settings → Resources.

## Always use `bin/compose.sh`

Run every compose command through `bin/compose.sh` instead of `docker compose`
(the examples here are run from `backend/`):

```bash
bin/compose.sh up -d          # build (if needed) and start everything
bin/compose.sh ps
bin/compose.sh logs -f es
bin/compose.sh down           # stop and remove containers (data is kept)
```

The script runs the containers as **you** (your uid and primary gid), so all
files written under `data/` belong to you and can be edited or deleted without
`sudo`. It passes every argument straight through to `docker compose`, so any
compose command or option works the same way. You can run it from any directory,
e.g. `backend/bin/compose.sh up -d` from the repository root.

Running `docker compose` directly fails on purpose:

```
required variable UID is missing a value: run via bin/compose.sh
```

## Data

| Path           | Contents                          |
|----------------|-----------------------------------|
| `data/es/`     | Elasticsearch indices and state   |
| `data/kibana/` | Kibana's local state (uuid, etc.) |
| `data/pg/`     | PostgreSQL cluster (`18/docker/`) |

Data survives `down`/`up`.

### Starting with an empty Elasticsearch

```bash
bin/clean-es.sh               # asks for confirmation
bin/clean-es.sh -y            # no prompt (for scripts)
bin/clean-es.sh -y --build    # other arguments go to `docker compose up`
```

This stops the stack, deletes everything in `data/es`, and starts the stack
again with `up -d --wait`, as if the volume were new. Kibana stores its
dashboards, data views and settings in Elasticsearch, so those are reset too.
`data/kibana` is kept because nothing in it affects a clean start. Without a
terminal, the script refuses to run unless you pass `-y`.

## PostgreSQL

On first start (an empty `data/pg`), the image creates the cluster and runs
`pg/root/docker-entrypoint-initdb.d/10-dp.sql`, which creates:

- role `dp` (login, not a superuser)
- database `dp`, owned by `dp`, with the `vector` (pgvector) extension

The `postgres` superuser is still there for admin work. No password is needed
for either role:

```bash
psql -h localhost -U dp dp                    # from the host, if psql is installed
bin/compose.sh exec pg psql -U dp dp          # from inside the container
```

JDBC URL: `jdbc:postgresql://localhost:5432/dp` (user `dp`, no password).

### Your own init SQL: `pg/init-sql/`

Put `*.sql` files in `pg/init-sql/` with a zero-padded numeric prefix, e.g.
`01-schema.sql`, `02-seed.sql`. When the cluster is created, they run right
after `10-dp.sql`:

- in filename order (byte order, so `02-` sorts before `10-`; use the same
  number of digits throughout)
- as role `dp`, connected to database `dp`, so the objects they create belong
  to `dp`
- once each, and the first error stops everything (`ON_ERROR_STOP`)

Other files in the folder (e.g. `.gitkeep`) are ignored. The folder is mounted
into the container, so changing a file needs no image rebuild, only a reset.

Init scripts run only when the cluster is created. To run them again (after
adding or changing a file in `pg/init-sql/`, or after changing `10-dp.sql`),
start pg with an empty data directory:

```bash
bin/clean-pg.sh               # asks for confirmation
bin/clean-pg.sh -y            # no prompt (for scripts)
bin/clean-pg.sh -y --build    # other arguments go to `docker compose up`
```

This stops and removes only the `pg` container, deletes everything in
`data/pg` (all databases, roles and data), and starts `pg` again with
`up -d --wait`. `es` and `kibana` keep running. They aren't restarted, even
though `es` depends on `pg`. Changes to `10-dp.sql` also
need `--build`; changes to `pg/init-sql/` don't.

**If an init SQL file fails**, `pg` exits and `up --wait` reports it as
unhealthy (`bin/compose.sh logs pg` shows the error). The cluster is then only
partly set up, and a plain restart won't retry the scripts. Fix the SQL, then
run `bin/clean-pg.sh` again.

### Resetting all data

To also wipe Kibana's local state:

```bash
bin/compose.sh down
find data -mindepth 2 ! -name .gitkeep -delete
```

Keep the `.gitkeep` files. They make sure the directories already exist, owned
by you, before Docker mounts them. If a directory is missing, Docker creates it
as root.

## Checking that it works

```bash
curl localhost:9200/_cluster/health?pretty   # "status" : "green"
```

Then open Kibana at http://localhost:5601. Services start in order: `pg`,
then `es` once `pg` is healthy, then `kibana` once `es` is healthy. This can
take a minute. Starting a single service also starts the ones it depends on
(e.g. `bin/compose.sh up -d es` starts `pg` too). If `pg` fails (for example
an init SQL error), `es` and `kibana` don't start.

## Changing the PostgreSQL version

Edit `PG_VERSION` in `.env` (and the `ARG` default in `pg/Dockerfile`), then
run `bin/compose.sh up -d --build`. Minor upgrades (18.x → 18.y) work in place.
A new major version refuses to start while the old version's data is in
`data/pg` (it exits with "there appears to be PostgreSQL data in ..."). Dump
what you need, then start clean with `bin/clean-pg.sh`.

## Changing the Elastic version

Edit `ES_VERSION` in `.env`, then:

```bash
bin/compose.sh up -d --build
```

Elastic does not publish a `latest` tag, so the version is always pinned.
Downgrading over existing data usually fails. Start clean with `bin/clean-es.sh`.

## Elasticsearch on OpenShift

`es/Dockerfile-OpenShift` builds the `es` image without compose, for an
OpenShift BuildConfig that builds from GitHub:

```yaml
strategy:
  type: Docker
  dockerStrategy:
    dockerfilePath: Dockerfile-OpenShift
source:
  type: Git
  git:
    uri: https://github.com/<org>/<repo>.git
  contextDir: backend/es
```

To build it locally: `docker build -f es/Dockerfile-OpenShift es`.

Differences from the compose setup:

- **Heap:** not set in the image. ES uses half of the pod's memory limit, so a
  4Gi limit gives the same 2 GB heap as compose. To set it explicitly, add
  `ES_JAVA_OPTS` (e.g. `-Xms2g -Xmx2g`) to the Deployment's `env`.
- **mmap is off** (`node.store.allow_mmap: false`), because OpenShift nodes
  usually don't have `vm.max_map_count` raised.
- **Data:** mount a PVC at `/usr/share/elasticsearch/data`. Without one, data
  is lost when the pod restarts.
- **Health:** use a readiness probe on
  `/_cluster/health?wait_for_status=yellow&timeout=5s` (port 9200).
- **Security is still disabled.** Don't expose it through a Route.

## Ports

| Port | Purpose                     |
|------|-----------------------------|
| 9200 | Elasticsearch HTTP API      |
| 9300 | Elasticsearch transport     |
| 5601 | Kibana                      |
| 5432 | PostgreSQL                  |

## Data model: `model/`

`model/` is a separate Maven project (with its own wrapper) for the GraphQL data
model: `common`, `query` and `response` schemas and the Java types generated
from them. It doesn't use the compose stack. See [model/README.md](model/README.md).

The generated Java is committed under `model/src/generated/java`. A normal
build only compiles it:

```bash
cd model
./mvnw verify                         # build and test, no codegen
./mvnw -Pgenerate generate-sources    # after editing a schema: regenerate, then commit
```

`bin/check-model-drift.sh` regenerates the sources and checks them against git:

| Exit | Meaning |
|------|---------|
| 0    | The committed sources match the schemas. |
| 1    | Drift: the listed files changed. They are left regenerated, ready to review and commit. |
| 2    | Generating failed (for example, a schema syntax error). |

It overwrites `model/src/generated` in your working tree, and counts anything
uncommitted there as drift.

## Python tools: `py-tools/`

`py-tools/` is a packaged [uv](https://docs.astral.sh/uv/) project (Python 3.14)
for command-line and other tools. It starts empty. See
[py-tools/README.md](py-tools/README.md).

```bash
cd py-tools
uv sync          # create the venv and install the project
uv run <tool>    # run a tool registered under [project.scripts]
```

## Troubleshooting

- **ES exits with `max virtual memory areas vm.max_map_count [65530] is too low`**
  (Linux/WSL2 only; Docker Desktop on macOS already sets this): run
  `sudo sysctl -w vm.max_map_count=262144`. On WSL2, run it inside the WSL
  distro, or set it in `%UserProfile%\.wslconfig` under `[wsl2]` with
  `kernelCommandLine = sysctl.vm.max_map_count=262144`.
- **ES or Kibana exits with code 137, or the machine slows to a crawl**: Docker
  doesn't have enough memory. On macOS, raise the Docker Desktop memory limit.
- **Permission errors on `data/`**: make sure you started the stack with
  `bin/compose.sh`, and that `data/es`, `data/kibana` and `data/pg` are owned by you
  (`ls -ln data`). Fix with `sudo chown -R $(id -u):$(id -g) data`.
- **Port already in use**: something else is listening on 9200, 9300, 5601 or 5432
  (for example a PostgreSQL installed on the host).
  Stop it, or change the host side of the port mapping in `docker-compose.yml`.
