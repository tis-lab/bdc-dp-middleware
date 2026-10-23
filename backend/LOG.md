# Backend change log

Newest first. Each section covers one set of development changes.

## 2026-10-01 06:53 PDT: Pre-commit documentation cleanup

- README: Requirements now say the scripts in `bin/` are Bash scripts; tables
  aligned; downgrade advice points to `bin/clean-es.sh`.
- DEV.md: added the docs to the folder tree; the Bash 3.2/BSD rule now covers
  all of `bin/`; split a merged bullet; moved the "new services" paragraph
  above the macOS section; log heading format now matches LOG.md.
- CLAUDE.md: the Bash 3.2/BSD rule now covers all of `bin/`.

## 2026-10-01 06:51 PDT: Verified consistent UID/GID across scripts

- Confirmed that `clean-es.sh` gives its services the same user and group as
  `compose.sh`: both its `down` and `up` steps go through `bin/compose.sh`.
  Tested as a different user (1234) with a stand-in `docker`, and checked the
  live containers (`uid=1000 gid=986 groups=986,0`).
- DEV.md and CLAUDE.md now state the rule: scripts in `bin/` call compose only
  through `bin/compose.sh`.

## 2026-10-01 06:50 PDT: Scripts moved to bin/

- Moved `compose.sh` and `clean-es.sh` to `bin/`. Both now locate the project
  directory as the parent of `bin/`, and `clean-es.sh` calls `bin/compose.sh`.
- Updated paths in README, DEV.md, CLAUDE.md, and the `run via bin/compose.sh`
  message in `docker-compose.yml`.

## 2026-10-01 06:48 PDT: clean-es.sh

- Added `clean-es.sh`. It asks for confirmation, runs `./compose.sh down`,
  empties `data/es` (keeping `.gitkeep`), then runs `./compose.sh up -d --wait`.
- `-y`/`--yes` skips the prompt. Without a terminal and without `-y`, it
  refuses to run. Other arguments go to `up`.
- `data/kibana` is not wiped, because all Kibana state lives in ES indices.
- Verified:
  - refuses without a terminal; aborts when the answer is "n"
  - `-y` from another directory wipes the data (a test index was gone), ES
    comes back green, and Kibana returns 200
  - works under Bash 3.2, with and without extra arguments
- Updated README, DEV.md and CLAUDE.md.

## 2026-10-01 06:42 PDT: macOS compatibility

- `compose.sh` now finds its directory with
  `cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd` instead of `readlink -f`.
  Before macOS 12.3, `readlink -f` failed and the script quietly used the
  current directory as the project folder.
- Checked:
  - the script works under Bash 3.2 (the version macOS ships)
  - both Elastic images have arm64 builds
  - ES and Kibana run as a typical macOS uid:gid (501:20)
- README: macOS requirements (at least 4 GB memory for the Docker VM),
  `vm.max_map_count` marked as Linux/WSL2 only, and a tip for out-of-memory
  exits (code 137).
- DEV.md: the Bash 3.2 rule for `compose.sh`, and macOS notes (Docker Desktop
  maps file ownership to the Mac user, so the ownership check only proves
  something on Linux).
- CLAUDE.md: Linux and macOS support is now a stated requirement.

## 2026-10-01 06:35 PDT: Documentation

- Added `README.md` (usage), `DEV.md` (how it works and what breaks easily),
  this `LOG.md`, and `CLAUDE.md` (guidance for Claude Code).

## 2026-10-01 06:30 PDT: Run containers as the invoking user

- Added `compose.sh`, which exports `UID` and `GID` (from `id -u` / `id -g`)
  and runs `docker compose --project-directory backend "$@"`. All
  commands and options pass through unchanged.
- `es` and `kibana` now run as `${UID}:${GID}` with group 0 added as a
  supplementary group. Plain `docker compose` now fails with
  `run via ./compose.sh`.
- Added a Kibana data bind mount at `data/kibana`.
- `.gitignore` now covers `data/*/*` and keeps each `.gitkeep`.
- Wiped the earlier ES test data.
- Verified:
  - all files under `data/` are owned by the invoking user's uid:gid
  - the ES image also starts as an arbitrary uid (1234) when group 0 is added

## 2026-10-01 06:25 PDT: Initial Elasticsearch + Kibana stack

- Created the `backend/` compose project. Its `es` service builds from `es/`:
  `FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}` followed
  by `COPY root/ /`.
- `ES_VERSION=9.5.3` in `.env`. Elastic publishes no `latest` tag, so the
  version is pinned and shared with Kibana.
- `es/root/usr/share/elasticsearch/config/elasticsearch.yml`: single node,
  security and TLS disabled.
- Compose settings for `es`:
  - 2 GB heap
  - ports 9200 and 9300
  - memlock/nofile ulimits
  - a cluster-health healthcheck
  - data at `data/es`
- Added a `kibana` service (port 5601) that waits for `es` to be healthy.
- Fixed a startup failure. `COPY root/ /` reset `config/` to root:755, so ES
  couldn't write its keystore. The Dockerfile now restores the ownership and
  mode after the copy.
