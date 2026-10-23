# Backend services

A local Docker Compose stack with:

| Service  | Image                                                    | URL                   |
|----------|----------------------------------------------------------|-----------------------|
| `es`     | Custom image built from the official Elasticsearch image | http://localhost:9200 |
| `kibana` | Official Kibana image                                    | http://localhost:5601 |

Both use the Elastic Stack version set in `.env` (`ES_VERSION`).

> **Local development only.** Security is disabled: there is no authentication
> and no TLS. Don't expose these ports outside your machine.

## Requirements

- Docker with the Compose v2 plugin (`docker compose`)
- Bash (the scripts in `bin/` are Bash scripts)
- About 4 GB of memory for Docker (Elasticsearch has a 2 GB heap, plus Kibana)

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

Then open Kibana at http://localhost:5601. It starts once Elasticsearch reports
healthy, which can take a minute.

## Changing the Elastic version

Edit `ES_VERSION` in `.env`, then:

```bash
bin/compose.sh up -d --build
```

Elastic does not publish a `latest` tag, so the version is always pinned.
Downgrading over existing data usually fails. Start clean with `bin/clean-es.sh`.

## Ports

| Port | Purpose                     |
|------|-----------------------------|
| 9200 | Elasticsearch HTTP API      |
| 9300 | Elasticsearch transport     |
| 5601 | Kibana                      |

## Troubleshooting

- **ES exits with `max virtual memory areas vm.max_map_count [65530] is too low`**
  (Linux/WSL2 only; Docker Desktop on macOS already sets this): run
  `sudo sysctl -w vm.max_map_count=262144`. On WSL2, run it inside the WSL
  distro, or set it in `%UserProfile%\.wslconfig` under `[wsl2]` with
  `kernelCommandLine = sysctl.vm.max_map_count=262144`.
- **ES or Kibana exits with code 137, or the machine slows to a crawl**: Docker
  doesn't have enough memory. On macOS, raise the Docker Desktop memory limit.
- **Permission errors on `data/`**: make sure you started the stack with
  `bin/compose.sh`, and that `data/es` and `data/kibana` are owned by you
  (`ls -ln data`). Fix with `sudo chown -R $(id -u):$(id -g) data`.
- **Port already in use**: something else is listening on 9200, 9300 or 5601.
  Stop it, or change the host side of the port mapping in `docker-compose.yml`.
