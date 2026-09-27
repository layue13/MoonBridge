#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
install="$repo_root/proxy-core/build/install/moonbridge"
python_image='docker.gitea.com/runner-images@sha256:fd911d7417bfbf0f454530e447da95b58001e1df41bbc5e1a8dd35d432575aae'
java_image='eclipse-temurin@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5'
suffix=$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')
network="moonbridge-channel-$suffix"
backend="sp-backend-$suffix"
proxy="sp-proxy-$suffix"
work=$(mktemp -d "${TMPDIR:-/tmp}/moonbridge-channel.XXXXXX")
secret=$(python3 -c 'import secrets; print(secrets.token_hex(32))')

cleanup() {
    docker rm -f "$proxy" "$backend" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT HUP INT TERM

cd "$repo_root"
bash ./gradlew :proxy-core:installDist --no-daemon
cat > "$work/config.yml" <<EOF
listen: "0.0.0.0:25577"
authentication: OFFLINE
allowOfflinePublicAccess: true
initialRouting:
  servers: [remote]
backends: []
plugins:
  directory: "/opt/moonbridge/plugins"
  enabled: {}
backendChannel:
  listen: "0.0.0.0:28081"
  clients:
    container-network-smoke:
      backendName: "remote"
      keyId: "primary"
      secret: "$secret"
      allowedHosts: ["$backend"]
      allowedNamespaces: ["smoke"]
EOF

docker network create "$network" >/dev/null
docker run -d --network "$network" --name "$backend" \
    -v "$repo_root:/work:ro" --entrypoint python3 "$python_image" \
    /work/smoke/container-network.py backend 2 >/dev/null
ready=0
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if docker logs "$backend" 2>&1 | grep -q 'BACKEND_READY'; then ready=1; break; fi
    sleep 1
done
if [ "$ready" -ne 1 ]; then docker logs "$backend"; exit 1; fi

docker run -d --network "$network" --name "$proxy" \
    -v "$install:/opt/moonbridge:ro" -v "$work/config.yml:/opt/moonbridge.yml:ro" \
    --entrypoint java "$java_image" -cp '/opt/moonbridge/lib/*' \
    dev.moonbridge.app.ProxyMain --config /opt/moonbridge.yml >/dev/null
if ! docker run --rm --network "$network" -v "$repo_root:/work:ro" \
    -e "MOONBRIDGE_CHANNEL_SECRET=$secret" --entrypoint python3 "$python_image" \
    /work/smoke/container-network.py client control "$proxy" "$backend"; then
    docker logs "$proxy" || true
    docker logs "$backend" || true
    exit 1
fi
for attempt in 1 2 3 4 5; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$backend")" = false ]; then break; fi
    sleep 1
done
if [ "$(docker inspect -f '{{.State.Running}}' "$backend")" != false ] ||
   [ "$(docker inspect -f '{{.State.ExitCode}}' "$backend")" != 0 ]; then
    docker logs "$backend"
    exit 1
fi
echo 'control: cross-container registration, login, GOODBYE and PLAY probe passed'
