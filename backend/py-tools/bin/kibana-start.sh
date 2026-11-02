#!/usr/bin/env bash
# Starts a Kibana container for an Elasticsearch at a given host and port.
# The Kibana version is the Elasticsearch version (asked from ES), unless
# --version is given. Stop and remove it with bin/kibana-stop.sh.
#
# Usage: bin/kibana-start.sh [options]
#   --es-host HOST        ES host or IP (default: $ES_HOST or localhost). localhost
#                         and 127.0.0.1 mean this machine; inside the container
#                         they become host.docker.internal.
#   --es-port PORT        ES port (default: $ES_PORT or 9200)
#   --es-scheme SCHEME    http or https (default: $ES_SCHEME or http)
#   --port PORT           port to publish Kibana on (default: $KIBANA_PORT or 5601)
#   --bind ADDRESS        address to publish on (default: 127.0.0.1, this machine
#                         only; 0.0.0.0 for every interface)
#   --name NAME           container name (default: $KIBANA_CONTAINER or py-tools-kibana)
#   --version VERSION     Kibana version (default: the ES version)
#   --es-username USER    ES user for Kibana, e.g. kibana_system (default: $ES_USERNAME);
#   --es-password PASS      Kibana refuses the elastic superuser (default: $ES_PASSWORD)
#   --service-token TOKEN ES service account token, instead of a user
#                         (default: $ES_SERVICE_TOKEN)
#   --ca-cert FILE        CA certificate for https (default: $ES_CA_CERT)
#   --insecure            https without verifying the ES certificate
#   --timeout SECONDS     how long to wait for Kibana to be ready (default: 180)
#   --no-wait             don't wait for Kibana to be ready
#   -h, --help            show this help
#
set -euo pipefail
# Keep Bash 3.2 compatible (macOS); no GNU-only tools.

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }
die() { echo "kibana-start.sh: $*" >&2; exit 2; }

es_host="${ES_HOST:-localhost}"
es_port="${ES_PORT:-9200}"
es_scheme="${ES_SCHEME:-http}"
port="${KIBANA_PORT:-5601}"
bind="127.0.0.1"
name="${KIBANA_CONTAINER:-py-tools-kibana}"
version=""
es_username="${ES_USERNAME:-}"
es_password="${ES_PASSWORD:-}"
service_token="${ES_SERVICE_TOKEN:-}"
ca_cert="${ES_CA_CERT:-}"
insecure=false
timeout=180
wait_ready=true

while [ $# -gt 0 ]; do
  case "$1" in
    --es-host) es_host="${2:?--es-host needs a value}"; shift 2 ;;
    --es-port) es_port="${2:?--es-port needs a value}"; shift 2 ;;
    --es-scheme) es_scheme="${2:?--es-scheme needs a value}"; shift 2 ;;
    --port) port="${2:?--port needs a value}"; shift 2 ;;
    --bind) bind="${2:?--bind needs a value}"; shift 2 ;;
    --name) name="${2:?--name needs a value}"; shift 2 ;;
    --version) version="${2:?--version needs a value}"; shift 2 ;;
    --es-username) es_username="${2:?--es-username needs a value}"; shift 2 ;;
    --es-password) es_password="${2:?--es-password needs a value}"; shift 2 ;;
    --service-token) service_token="${2:?--service-token needs a value}"; shift 2 ;;
    --ca-cert) ca_cert="${2:?--ca-cert needs a value}"; shift 2 ;;
    --insecure) insecure=true; shift ;;
    --timeout) timeout="${2:?--timeout needs a value}"; shift 2 ;;
    --no-wait) wait_ready=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

case "$es_scheme" in http|https) ;; *) die "--es-scheme must be http or https" ;; esac
case "$es_port$port$timeout" in *[!0-9]*) die "ports and --timeout must be numbers" ;; esac
if [ -n "$service_token" ] && [ -n "$es_username" ]; then
  die "give either --service-token or --es-username, not both"
fi
if [ "$es_username" = "elastic" ]; then
  die "Kibana won't connect as the elastic superuser; use kibana_system or a service token"
fi
if [ -n "$ca_cert" ] && [ ! -f "$ca_cert" ]; then
  die "CA certificate not found: $ca_cert"
fi
command -v docker >/dev/null 2>&1 || die "docker not found"
command -v curl >/dev/null 2>&1 || die "curl not found"

# The container: refuse to replace a running one; clear away a stopped one of ours.
state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
if [ -n "$state" ]; then
  ours="$(docker inspect -f '{{index .Config.Labels "py-tools.kibana"}}' "$name" 2>/dev/null || true)"
  [ "$ours" = "true" ] || die "a container named $name exists and wasn't started by this script; use --name"
  [ "$state" = "running" ] && die "$name is already running; stop it with bin/kibana-stop.sh --name $name"
  docker rm -v "$name" >/dev/null
fi

# The Kibana port must be free. curl exits 7 (couldn't connect) or 28 (timed
# out; on some systems a closed port doesn't answer) when nothing listens.
# Bash's /dev/tcp isn't used: it can hang with no timeout.
check_host="$bind"
[ "$check_host" = "0.0.0.0" ] && check_host="127.0.0.1"
rc=0
curl -s -o /dev/null --connect-timeout 2 --max-time 3 "http://$check_host:$port/" || rc=$?
case "$rc" in
  7|28) ;;
  *) die "port $port is already in use (another Kibana?); use --port, e.g. --port 5602" ;;
esac

# Ask ES for its version; that's also a connectivity check from this machine.
es_url="$es_scheme://$es_host:$es_port"
curl_args=(-s -S --max-time 10)
if [ -n "$service_token" ]; then
  curl_args+=(-H "Authorization: Bearer $service_token")
elif [ -n "$es_username" ]; then
  curl_args+=(-u "$es_username:$es_password")
fi
if [ "$es_scheme" = "https" ]; then
  if $insecure; then curl_args+=(-k); elif [ -n "$ca_cert" ]; then curl_args+=(--cacert "$ca_cert"); fi
fi
if [ -z "$version" ]; then
  if ! info="$(curl "${curl_args[@]}" "$es_url/" 2>&1)"; then
    die "can't reach Elasticsearch at $es_url: $info"
  fi
  version="$(printf '%s' "$info" | tr -d '\n ' | sed -n 's/.*"version":{"number":"\([^"]*\)".*/\1/p')"
  [ -n "$version" ] || die "no version in the answer from $es_url (wrong credentials?): $(printf '%s' "$info" | head -c 300)"
  echo "Elasticsearch $version at $es_url"
fi

# Inside the container, this machine is host.docker.internal, not localhost.
container_es_host="$es_host"
case "$es_host" in
  localhost|127.0.0.1|::1|"[::1]") container_es_host="host.docker.internal" ;;
esac

run_args=(
  -d --name "$name"
  --label py-tools.kibana=true
  --add-host host.docker.internal:host-gateway
  -p "$bind:$port:5601"
  -e "ELASTICSEARCH_HOSTS=[\"$es_scheme://$container_es_host:$es_port\"]"
)
if [ -n "$service_token" ]; then
  run_args+=(-e "ELASTICSEARCH_SERVICEACCOUNTTOKEN=$service_token")
elif [ -n "$es_username" ]; then
  run_args+=(-e "ELASTICSEARCH_USERNAME=$es_username" -e "ELASTICSEARCH_PASSWORD=$es_password")
fi
if [ "$es_scheme" = "https" ]; then
  if $insecure; then
    run_args+=(-e "ELASTICSEARCH_SSL_VERIFICATIONMODE=none")
  elif [ -n "$ca_cert" ]; then
    ca_dir="$(cd -P "$(dirname "$ca_cert")" && pwd)"
    run_args+=(
      -v "$ca_dir/$(basename "$ca_cert"):/usr/share/kibana/config/es-ca.crt:ro"
      -e "ELASTICSEARCH_SSL_CERTIFICATEAUTHORITIES=[\"/usr/share/kibana/config/es-ca.crt\"]"
    )
  fi
fi

image="docker.elastic.co/kibana/kibana:$version"
echo "Starting $name ($image), Elasticsearch $es_scheme://$container_es_host:$es_port inside the container"
docker run "${run_args[@]}" "$image" >/dev/null

# Can the container reach ES? Any HTTP answer counts (even 401); curl exit 7 or
# 28 means it can't connect, and Kibana would wait for ES forever.
container_es_url="$es_scheme://$container_es_host:$es_port/"
rc=0
docker exec "$name" curl -s -k -o /dev/null --connect-timeout 5 --max-time 8 "$container_es_url" || rc=$?
case "$rc" in
  7|28)
    echo "kibana-start.sh: WARNING: the container can't reach Elasticsearch at $container_es_url." >&2
    if [ "$container_es_host" = "host.docker.internal" ]; then
      echo "  ES must be published on an address containers can reach (e.g. 0.0.0.0:$es_port," >&2
      echo "  not 127.0.0.1:$es_port), and Docker networking must work. Check that" >&2
      echo "  'ip route get <a container IP>' goes through docker0; a VPN using the same" >&2
      echo "  range (e.g. 172.17.x) takes over that traffic. For an 'oc port-forward', add" >&2
      echo "  --address 127.0.0.1,<docker0 address>. See the README section" >&2
      echo "  'Docker networking on WSL2 with a VPN, and oc port-forward'." >&2
    fi
    ;;
esac

url="http://localhost:$port"
[ "$bind" = "127.0.0.1" ] || [ "$bind" = "0.0.0.0" ] || url="http://$bind:$port"
if ! $wait_ready; then
  echo "Started. Kibana will be at $url when ready (docker logs -f $name)."
  exit 0
fi

echo "Waiting up to ${timeout}s for Kibana at $url ..."
deadline=$((SECONDS + timeout))  # elapsed time, not loop counts: each probe can take seconds
while [ "$SECONDS" -lt "$deadline" ]; do
  status="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 "$url/api/status" || true)"
  if [ "$status" = "200" ]; then
    echo "Kibana is ready: $url"
    exit 0
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || echo false)" != "true" ]; then
    echo "kibana-start.sh: $name stopped; last log lines:" >&2
    docker logs --tail 20 "$name" >&2 || true
    exit 1
  fi
  sleep 3
done
echo "kibana-start.sh: Kibana wasn't ready after ${timeout}s; it may still be starting. Last log lines:" >&2
docker logs --tail 20 "$name" >&2 || true
exit 1
