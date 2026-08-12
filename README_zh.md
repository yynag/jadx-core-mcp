# JADX Headless Server (jadx-core-mcp)

面向 AI Agent 的 JADX 无头多 APK 服务：**HTTP REST + MCP Streamable HTTP（局域网）+ MCP Stdio（本机）**。

> **无 Token**，Master 固定监听 `0.0.0.0`。仅在可信内网使用。

## 三种接入方式

| 方式 | 启动 | 客户端 |
|------|------|--------|
| **OpenCode 局域网 remote** | `java -jar app-all.jar --port 8650` | `type: remote` + `url: http://服务器IP:8650/mcp` |
| **OpenCode 本机 local** | （由客户端拉起）`--mcp` | `type: local` + `command: java -jar ... --mcp` |
| **脚本 / REST** | 同上默认模式 | `http://服务器IP:8650/...` |

### OpenCode：局域网 remote（推荐大内存服务器）

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

服务器上：

```bash
./gradlew :app:shadowJar
java -jar app/build/libs/app-all.jar --port 8650
# 可选部署环境变量：JADX_WORKER_XMX=8g
```

### OpenCode：本机 Stdio

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jadx": {
      "type": "local",
      "command": [
        "java", "-jar", "/ABS/PATH/app/build/libs/app-all.jar", "--mcp"
      ],
      "enabled": true
    }
  }
}
```

## 架构

- **Master（0.0.0.0:port）**：REST + MCP `/mcp`（Streamable HTTP，供 OpenCode remote）
- **Worker（127.0.0.1:随机端口）**：每 APK 一 JVM；仅本机访问；无 `/apk/load`
- 无 Worker 数量硬限；load OOM 时错误信息会列出活跃实例，提示 `apk_unload`

## MCP Tools（约 10 个）

| Tool | 作用 |
|------|------|
| `apk_load` / `apk_unload` / `apk_list` | 生命周期（`max_heap` 可选） |
| `meta_summary` / `meta_manifest` | 摘要 / Manifest |
| `meta_class` | 类列表或 methods+fields |
| `decompile` | `target=java\|smali\|method\|main_activity`，`timeout` 由 Agent 决定 |
| `resource` | `action=list\|file\|strings` |
| `xref` | `target_type=class\|method\|field` |
| `search` | `scope=class\|code\|method_name`；`timeout`/`max_scan`/`max_decompile` 由 Agent 决定 |

失败：`isError=true`，不返回假源码。

## REST 摘要

| 路径 | 说明 |
|------|------|
| `GET /health` | 含 `mcp: "/mcp"` |
| `POST /apk/load` | `apk_path`, 可选 `max_heap` |
| `POST /apk/unload` | `apk_id`, 可选 `clear_cache` |
| `GET /apk/list` | 实例列表 |
| `/meta/*` `/decompile/*` `/resource/*` `/xref/*` `/search/*` | 需 `apk_id` |

搜索默认 `search_in=class`；`code` 需显式指定；非法 scope → 400。

## 部署级环境变量（可选）

| 变量 | 用途 | 谁设 |
|------|------|------|
| `JADX_WORKER_XMX` | 未传 `max_heap` 时的默认堆 | 运维按机器内存 |
| `JADX_WORKER_TIMEOUT_SEC` | APK **加载就绪**最长等待秒 | 大包环境 |
| `JADX_DECOMPILE_TIMEOUT` | 单类反编译默认秒（请求 `timeout` 优先） | 可选默认 |

单次分析的 `timeout` / `max_scan` / `max_decompile` / `max_heap` 由 **Agent 调用参数** 决定，不必改 Env。

## 构建与测试

```bash
./gradlew :app:shadowJar
./gradlew :app:test
```

金样例：`app/src/test/resources/sample.jar`（`com.yyang.sample.HelloSample`）。

## License

Apache-2.0
