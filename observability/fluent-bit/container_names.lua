-- container_names.lua
-- Secondary container name lookup (legacy / backup)
-- Regenerate with: docker ps --format '["{{.ID}}"]="{{.Names}}"'
local NAMES = {
  ["b04b3f16b95c"] = "fluent-bit",
  ["5f83b10c3f50"] = "frontend",
  ["2ae7473f1565"] = "api-gateway",
  ["758681c1fb7f"] = "analytics-service",
  ["b91d6a5b42ad"] = "ocr-parser-service",
  ["f3b5a1f8d960"] = "upsert-service",
  ["573163171ebd"] = "auth-service",
  ["e18b4c93f298"] = "grafana",
  ["733ee99b5a7b"] = "postgres-db",
  ["c9b305fb6544"] = "clickhouse",
  ["bb803e4bdfed"] = "prometheus",
}

local function get_name(cid)
    -- Try exact short ID (12 chars)
    local short = string.sub(cid, 1, 12)
    if NAMES[short] then return NAMES[short] end
    -- Try full ID match by prefix
    for k, v in pairs(NAMES) do
        if string.sub(cid, 1, #k) == k then return v end
    end
    return short
end
