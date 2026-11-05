# Docker networking on WSL2 with a VPN, and `oc port-forward`

This document describes a problem found while testing `bin/kibana-start.sh`,
how Docker was reconfigured to fix it, and how to reach an Elasticsearch in an
OpenShift cluster through `oc port-forward` with that configuration. It
applies to any container started with `docker run` that needs to reach the
WSL machine or another container, not only Kibana.

## The setup

- Windows with WSL2 in **mirrored** networking mode: WSL shares Windows'
  network interfaces, including a VPN's. Check with `wslinfo
  --networking-mode`.
- **Docker Engine installed inside WSL** (Ubuntu, managed by systemd), not
  Docker Desktop. Its default bridge network is `docker0`; containers started
  with `docker run` get addresses on it, and published ports (`-p`) are
  forwarded to them.
- A VPN client on Windows (here the campus VPN, `search unc.edu`).

## Symptoms

- `bin/kibana-start.sh` couldn't reach Elasticsearch, or Kibana never came
  up, and the wait ran into its timeout.
- From WSL, every connection to a container timed out: its published port
  (`curl localhost:59201`) and its container IP (`curl 172.17.0.2:9200`).
- Inside the container, Elasticsearch answered normally
  (`docker exec <container> curl localhost:9200` gave 200).
- Connections didn't fail with "connection refused"; they hung until a
  timeout, even to ports where nothing listened.
- The same commands had worked earlier the same day.

## Diagnosis

```sh
ip route get 172.17.0.2         # which interface traffic to a container uses
ip -4 addr show                 # the addresses of each interface
ip route                        # all routes
wslinfo --networking-mode       # mirrored or nat
```

`ip route get 172.17.0.2` answered `dev eth0`, not `dev docker0`, and
`ip -4 addr` showed why:

| Interface | Address | What it is |
|---|---|---|
| `docker0` | `172.17.0.1/16` | Docker's default bridge (Docker's default range) |
| `eth0` | `172.17.21.33/18`, gateway `172.17.0.1` | The VPN, mirrored into WSL |

The VPN had been given addresses in Docker's own range, and its gateway had
the same address as `docker0`. The routing table had, among others:

```
172.16.0.0/12 via 172.17.0.1 dev eth0      # VPN: all of 172.16-172.31
172.17.0.0/18 dev eth0                     # VPN: its own subnet
172.17.0.0/16 dev docker0                  # Docker's bridge
```

Linux picks the most specific matching route. For a container at
`172.17.0.2`, the VPN's `/18` is more specific than Docker's `/16`, so the
traffic went into the VPN, which dropped it. That explains every symptom:

- Published ports failed too, because Docker forwards them to the container's
  bridge address, which was routed into the VPN.
- `host.docker.internal`, which `kibana-start.sh` maps to Docker's bridge
  gateway, was `172.17.0.1`, the same address as the VPN gateway.
- Connections hung instead of being refused because the VPN silently drops
  packets it doesn't expect.
- It had worked earlier because the VPN connected later.

The backend compose stack wasn't affected: compose creates its own networks
(`172.18.0.0/16` and up). The VPN routes all of `172.16.0.0/12`, but each
compose network's own `/16` route is more specific, so it still won. Only
the default bridge collided, through the VPN's `/18`.

## The fix: move Docker's networks out of the VPN's ranges

Docker's address ranges are set in `/etc/docker/daemon.json`:

```json
{
  "bip": "192.168.200.1/24",
  "default-address-pools": [ { "base": "192.168.208.0/20", "size": 24 } ]
}
```

- `bip` (bridge IP) is the address and size of `docker0`, the default bridge
  used by `docker run`. Containers get `192.168.200.2`, `.3`, ..., and
  `host.docker.internal` becomes `192.168.200.1`.
- `default-address-pools` is where new networks come from, such as the ones
  `docker compose` creates: `192.168.208.0/24`, `192.168.209.0/24`, ... (16
  networks of 254 addresses). Without it, they come from `172.18-31.x`,
  which the VPN's `172.16.0.0/12` also covers; they work today only because
  their routes are more specific, and a VPN pushing narrower routes would
  break them too.
- The ranges must not be used by the VPN or your local network. Check with
  `ip route` while the VPN is connected: here the VPN uses `10.0.0.0/8`,
  `172.16.0.0/12` and some public addresses, and the home network
  `192.168.0.0/24`, so `192.168.200.0/24` and `192.168.208.0/20` are free.

Apply it (this needs `sudo`):

```sh
printf '%s\n' '{' \
  '  "bip": "192.168.200.1/24",' \
  '  "default-address-pools": [ { "base": "192.168.208.0/20", "size": 24 } ]' \
  '}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
```

The restart stops all running containers. Networks that already exist keep
their old ranges; only new ones use the pool. For the backend stack, recreate
its network with `bin/compose.sh down` and `bin/compose.sh up -d` (in
`backend/`); data in `backend/data/` isn't affected.

Check that it worked:

```sh
ip -4 addr show docker0          # inet 192.168.200.1/24
ip route get 192.168.200.2       # ... dev docker0 ...
docker run --rm -d --name net-check -p 59999:80 nginx:alpine >/dev/null
sleep 2; curl -s -o /dev/null -w '%{http_code}\n' localhost:59999   # 200
docker rm -f net-check
```

If the VPN or your network ever starts using these ranges, pick others the
same way and restart Docker again. To undo the change, delete
`/etc/docker/daemon.json` and restart Docker.

## Using `oc port-forward` with this configuration

`oc port-forward` (and `kubectl port-forward`) makes a remote service
reachable on a local port, but by default it listens only on `127.0.0.1` of
WSL. Programs running in WSL, such as the py-tools commands, can use
`localhost:9200` directly. A container can't: inside it, `localhost` is the
container itself, so `kibana-start.sh` points Kibana at
`host.docker.internal`, which is `192.168.200.1`, Docker's bridge, where the
port-forward isn't listening. `kibana-start.sh` then warns that the container
can't reach Elasticsearch.

Make the port-forward also listen on the bridge address:

```sh
# terminal 1 (keep it running)
oc login ...                                   # as usual
oc port-forward svc/<elasticsearch-service> 9200:9200 --address 127.0.0.1,192.168.200.1

# terminal 2
bin/kibana-start.sh                            # ES at localhost:9200 (the default)
```

- `--address` takes a comma-separated list of local addresses to listen on.
  `127.0.0.1` keeps `localhost:9200` working for the py-tools commands;
  `192.168.200.1` makes it reachable from containers.
- `192.168.200.1` exists only while Docker is running (it's `docker0`'s
  address), so start Docker before the port-forward. If Docker is
  restarted, restart the port-forward too.
- Don't use `--address 0.0.0.0` unless you need to: it listens on every
  interface, including your LAN and the VPN, and this Elasticsearch may have
  no authentication.
- `svc/<name>` forwards to a service; `pod/<name>` or a plain pod name forwards
  to one pod. The first `9200` is the local port, the second the remote one.
- If something else already uses local port `9200` (for example the backend
  stack's Elasticsearch), choose another local port and tell the script:

  ```sh
  oc port-forward svc/<elasticsearch-service> 9201:9200 --address 127.0.0.1,192.168.200.1
  bin/kibana-start.sh --es-port 9201 --port 5602
  ```

Check both paths:

```sh
curl -s localhost:9200 | grep number                       # from WSL
curl -s 192.168.200.1:9200 | grep number                   # the address containers use
docker exec py-tools-kibana curl -s -o /dev/null -w '%{http_code}\n' http://host.docker.internal:9200/   # 200, once Kibana runs
```

While Kibana runs, `oc` prints messages such as:

```
E1029 ... portforward.go:404] "Unhandled Error" err="error copying from local connection to remote stream: ... read: connection reset by peer"
Handling connection for 9200
```

Each `Handling connection` is a new connection through the forward; the
`connection reset by peer` errors appear when Kibana closes a connection
before `oc` has finished copying it. Kibana does that routinely (health
checks, retries with timeouts), and they're harmless as long as Kibana stays
available. To hide them, run the port-forward with `2>/dev/null`, or in a
terminal you don't watch.
