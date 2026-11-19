#!/usr/bin/env bash
set -euo pipefail

fixture="$(mktemp -d)"
legacy=zjuzsj/tide-listen-server:0.13.1
current=tidelisten-sync:local
compose=(docker compose --project-name tide-bind-test --file "$fixture/compose.yaml")
trap '"${compose[@]}" down --remove-orphans' EXIT

mkdir "$fixture/data" "$fixture/secrets"
sudo chown 1000:1000 "$fixture/data" "$fixture/secrets"
sudo chmod 700 "$fixture/data" "$fixture/secrets"
mounts=(-v "$fixture/data:/data" -v "$fixture/secrets:/secrets")

printf '%s\n' 'preserved-test-password-01' |
  docker run --rm -i "${mounts[@]}" "$legacy" init preserved-admin

docker run --rm -i "${mounts[@]}" --entrypoint node "$legacy" --input-type=module - <<'NODE'
import { DatabaseSync } from 'node:sqlite'
const db = new DatabaseSync('/data/sync.sqlite')
const admin = db.prepare('SELECT id FROM users WHERE admin=1').get()
const lists = {
  defaultList: { id: 'default', name: '试听列表', type: 'default', meta: {}, list: [] },
  loveList: { id: 'love', name: '我喜欢', type: 'default', meta: {}, list: [] },
  userList: [{ id: 'original', name: 'Original playlist', type: 'general', meta: {}, list: [] }],
}
db.prepare('INSERT INTO libraries(uid,kind,revision,data) VALUES(?,?,?,?)')
  .run(admin.id, 'lists', 7, JSON.stringify(lists))
db.close()
NODE

key_digest="$(sudo sha256sum "$fixture/secrets/sources.key")"
database_inode="$(sudo stat -c %i "$fixture/data/sync.sqlite")"
sudo touch "$fixture/data/untouched"
sudo chmod 644 "$fixture/data/untouched"
sudo chown root:root "$fixture/data" "$fixture/secrets" \
  "$fixture/data/sync.sqlite" "$fixture/secrets/sources.key"
sudo chmod 600 "$fixture/data/sync.sqlite" "$fixture/secrets/sources.key"
if docker run --rm "${mounts[@]}" -e SYNC_ADMIN_PASSWORD=ignored-fixture-pass "$legacy" \
  > "$fixture/legacy.log" 2>&1; then
  echo 'Expected the old image to fail when the bind directories belong to root' >&2
  exit 1
fi
grep -q 'EACCES' "$fixture/legacy.log"

cat > "$fixture/compose.yaml" <<YAML
services:
  sync:
    image: $current
    pull_policy: never
    environment:
      SYNC_PUBLIC_URL: https://bind.example.com
      SYNC_ADMIN_USERNAME: replacement
      SYNC_ADMIN_PASSWORD: wrong-fixture-password
    volumes:
      - ./data:/data
      - ./secrets:/secrets
    security_opt: [no-new-privileges:true]
    ports:
      - '127.0.0.1:8474:8472'
YAML

"${compose[@]}" up -d
url=http://127.0.0.1:8474
curl -fsS --retry 20 --retry-all-errors --retry-delay 1 --max-time 5 "$url/health" ||
  { "${compose[@]}" logs sync; exit 1; }
curl -fsS -D "$fixture/headers" "$url/admin/api/login" \
  -H 'Origin: https://bind.example.com' -H 'Content-Type: application/json' \
  --data '{"name":"preserved-admin","password":"preserved-test-password-01"}' |
  jq -e '.account == "preserved-admin"'
cookie="$(awk '/^[Ss]et-[Cc]ookie:/ {sub(/;.*/, "", $2); print $2}' "$fixture/headers")"
test -n "$cookie"
curl -fsS -H "Cookie: $cookie" "$url/admin/api/lists" |
  jq -e '.revision == 7 and .data.userList[0].name == "Original playlist"'

test "$(sudo sha256sum "$fixture/secrets/sources.key")" = "$key_digest"
test "$(sudo stat -c %i "$fixture/data/sync.sqlite")" = "$database_inode"
test "$(sudo stat -c %u:%g:%a "$fixture/data/untouched")" = 0:0:644
for dir in "$fixture/data" "$fixture/secrets"; do
  test "$(sudo stat -c %u:%g:%a "$dir")" = 1000:1000:700
done
test "$(sudo stat -c %u:%g:%a "$fixture/secrets/sources.key")" = 1000:1000:600
"${compose[@]}" exec -T --user node sync awk '
  /^Uid:/ { uid = ($2 == 1000 && $3 == 1000) }
  /^Gid:/ { gid = ($2 == 1000 && $3 == 1000) }
  /^CapEff:/ { caps = ($2 ~ /^0+$/) }
  END { exit !(uid && gid && caps) }
' /proc/1/status
