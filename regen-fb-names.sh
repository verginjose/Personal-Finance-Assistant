#!/bin/bash
# regen-fb-names.sh
# Run after every 'docker compose up --build' to update the Fluent Bit container name map
# Usage: ./regen-fb-names.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LUA="$SCRIPT_DIR/observability/fluent-bit/docker_name.lua"

echo "Generating container name map..."

python3 - "$LUA" << 'PYEOF'
import subprocess, sys

result = subprocess.run(['docker','ps','-a','--format','{{.ID}} {{.Names}}'], capture_output=True, text=True)
lines = result.stdout.strip().split('\n')

lua  = "-- docker_name.lua (auto-generated — run regen-fb-names.sh after rebuild)\n"
lua += "local NAMES = {\n"
for line in lines:
    parts = line.strip().split(' ', 1)
    if len(parts) == 2:
        short_id, name = parts[0][:12], parts[1].strip()
        lua += f'  ["{short_id}"] = "{name}",\n'
        print(f"  {short_id} -> {name}")
lua += """}
local function resolve(cid)
    local short = string.sub(cid, 1, 12)
    return NAMES[short] or short
end
function add_container_name(tag, timestamp, record)
    local cid = string.match(tag, "docker%.(.+)")
    record["source"] = cid and resolve(cid) or "unknown"
    -- Remove Docker metadata; ClickHouse only needs 'source' and 'log'.
    -- timestamp uses DEFAULT now64() in ClickHouse.
    record["time"]   = nil
    record["stream"] = nil
    return 1, timestamp, record
end
"""

path = sys.argv[1] if len(sys.argv) > 1 else '/dev/stdout'
with open(path, 'w') as f:
    f.write(lua)
print(f"Written to {path}")
PYEOF

echo "Restarting fluent-bit..."
docker compose restart fluent-bit
echo "Done! Container names will appear in ClickHouse within ~10s."
