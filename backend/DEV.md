# Developing the backend compose setup

Read [README.md](README.md) first. It covers day-to-day usage. This file explains
how the setup works and the things that break easily when you change it.

## Layout

```
backend/
├── .env                  # ES_VERSION, used by the es build arg and the kibana image tag
├── .gitignore            # ignores data/*/* but keeps data/*/.gitkeep
├── README.md             # for users of the stack
├── DEV.md                # this file
├── LOG.md                # change log, newest first
├── CLAUDE.md             # guidance for Claude Code
├── bin/
│   ├── compose.sh        # wrapper: exports UID/GID, then exec docker compose "$@"
│   └── clean-es.sh       # confirm, compose down, empty data/es, compose up -d --wait
├── docker-compose.yml
├── data/
│   ├── es/.gitkeep       # bind mount -> /usr/share/elasticsearch/data
│   └── kibana/.gitkeep   # bind mount -> /usr/share/kibana/data
└── es/                   # build context for the es service
    ├── Dockerfile
    └── root/             # copied onto / of the image (COPY root/ /)
        └── usr/share/elasticsearch/config/elasticsearch.yml
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
  variables. The exception is `ES_JAVA_OPTS`, which is set in compose. This
  file replaces the base image's `elasticsearch.yml`, so it must keep
  `network.host: 0.0.0.0`.

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
- Both images run as uid 1000 by default. A developer whose uid is 1000 won't
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
bin/compose.sh exec es id                    # your uid/gid, plus group 0
find data ! \( -user $(id -u) -group $(id -g) \)   # should print nothing (meaningful on Linux only)
```

## Change log

Record every change to this setup in [LOG.md](LOG.md), newest entry first, with
a `## YYYY-MM-DD HH:MM TZ: <title>` heading.
