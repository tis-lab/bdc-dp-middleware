# CLAUDE.md (backend/)

A local Docker Compose stack (Elasticsearch + Kibana). Read `DEV.md` for how it
works and `README.md` for how it's used.

## Rules

- Run compose only through `bin/compose.sh`. Never run `docker compose` directly.
  The compose file requires `UID`/`GID`, which the wrapper exports so that
  containers run as the invoking user.
- Other scripts in `bin/` must call compose only through `bin/compose.sh`.
  They must never call `docker compose` or set `UID`/`GID` themselves.
- Use `bin/clean-es.sh` to restart with an empty ES. It asks for confirmation;
  never pass `-y` without the user's approval.
- Each service that needs a custom image has its own directory named after the
  service. That directory is the build context, and its `root/` subdirectory is
  copied onto `/` of the image. Put config files under `root/` at their in-image
  paths rather than using environment variables.
- After any `COPY root/ /`, restore the owner and mode of existing directories
  the service writes to (see the `es/Dockerfile` `RUN` step and DEV.md).
- Every service that bind-mounts data uses `./data/<service>`, has a committed
  `data/<service>/.gitkeep`, and sets the
  `user: "${UID:?...}:${GID:?...}"` + `group_add: ["0"]` block.
- Pin Elastic versions with `ES_VERSION` in `.env`. Elastic publishes no
  `latest` tag. Keep the Dockerfile `ARG` default the same.
- The setup must work on Linux (incl. WSL2) and macOS. Keep every script in
  `bin/` compatible with Bash 3.2 and BSD tools (no `readlink -f`, no GNU-only
  flags).
- Security is intentionally disabled (local development only).
- Data in `data/` belongs to the user, so no `sudo` is needed. Ask before wiping it.

## After changes

- Verify with the checklist in DEV.md, "Verifying changes". This includes
  checking that the stack is healthy and that files under `data/` are owned by
  the user's uid:gid.
- Add an entry at the top of `LOG.md` under a `## YYYY-MM-DD HH:MM TZ: <title>`
  heading (get the time from `date '+%Y-%m-%d %H:%M %Z'`).
- Update README.md (user-facing) and DEV.md (developer-facing) when behavior
  or structure changes.

## Working with the user

- They want to be asked about open design choices before changes are made.
