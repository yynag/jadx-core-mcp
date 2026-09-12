# JADX Headless Server (jadx-core-mcp)

Headless multi-APK JADX for AI agents: **HTTP REST + MCP Streamable HTTP (LAN) + MCP Stdio (local)**.

> **No auth token.** Master binds `0.0.0.0`. Trusted LAN only.

## Access modes

| Mode | Start | Client |
|------|--------|--------|
| **OpenCode remote (LAN)** | `java -jar app-all.jar --port 8650` | `type: remote`, `url: http://HOST:8650/mcp` |
| **OpenCode local** | client spawns `--mcp` | `type: local`, `command: java -jar ... --mcp` |
| **REST scripts** | default mode | `http://HOST:8650/...` |

### OpenCode remote (LAN)

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jadx": {
      "type": "remote",
      "url": "http://192.168.1.100:8650/mcp",
      "enabled": true,
      "oauth": false
    }
  }
}
```

### OpenCode local (Stdio)

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jadx": {
      "type": "local",
      "command": ["java", "-jar", "/ABS/PATH/app-all.jar", "--mcp"],
      "enabled": true
    }
  }
}
```

## Architecture

- **Master `0.0.0.0:port`**: REST + MCP `/mcp` (Streamable HTTP for OpenCode remote)
- **Worker `127.0.0.1:ephemeral`**: one JVM per APK; no public `/apk/load`
- No hard worker cap; OOM errors list active instances and suggest `apk_unload`

## MCP tools (~10)

`apk_load` / `apk_unload` / `apk_list` · `meta_summary` / `meta_manifest` / `meta_class` · `decompile` · `resource` · `xref` · `search`

Agent-controlled: `timeout`, `max_heap`, `max_scan`, `max_decompile`, `deobf` (default **true**, JADX unique names like GUI). Failures set `isError=true`. See `AGENTS.md` for the intended tool order.

- `search` scope: `class` · `string` (DEX const-string) · `method_name` · `field` · `code` / `comment` (budgeted). Hits include `preview`.
- `meta_summary` includes `topPackages`
- `meta_manifest` + `component_type` lists activity/service/receiver/provider (`only_exported` optional)
- `resource action=id` resolves `0x7f…`
- `rename` class/method/field aliases (persisted by APK fingerprint, re-applied on next load)
- Decompile output truncated at 80k chars (`truncated` + `total_chars`)
- Class lookup accepts `p000.` / `defpackage.` aliases

## Deploy env (optional)

| Env | Purpose |
|-----|---------|
| `JADX_WORKER_XMX` | Default heap if `max_heap` omitted |
| `JADX_WORKER_TIMEOUT_SEC` | Load-ready timeout |
| `JADX_DECOMPILE_TIMEOUT` | Default decompile seconds (request `timeout` wins) |

## Build / test

```bash
./gradlew :app:shadowJar
./gradlew :app:test
```

## License

Apache-2.0
