# JADX Headless Server (jadx-core-mcp)

<p align="center">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/JDK-21%2B-ED8B00.svg" alt="JDK 21" />
  <img src="https://img.shields.io/badge/JADX-1.5.6-blue.svg" alt="JADX" />
  <img src="https://img.shields.io/badge/Protocol-MCP-green.svg" alt="MCP Protocol" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-red.svg" alt="License" />
</p>

<p align="center">
  <b>High-performance, isolated multi-APK decompilation & analysis engine for AI Agents and Reverse Engineering tools.</b>
</p>

<p align="center">
  <a href="./README.md">English</a> | <a href="./README_zh.md">中文文档</a>
</p>

---

## ⚡ Highlights

- 🚀 **Isolated Multi-Worker Process Pool**: Master daemon coordinates independent JVM Worker sub-processes bound to unique UUID `apk_id`s. Unloading an APK kills the Worker process, guaranteeing 100% OS-level heap memory reclamation with zero memory leaks.
- 🤖 **Native Model Context Protocol (MCP) Support**: Built-in official `io.modelcontextprotocol:kotlin-sdk`. One-click launch in Stdio mode (`--mcp`) equips AI Agents (opencode, Claude Desktop, Cursor) with 20+ fine-grained Android reverse-engineering tools.
- ⚡ **Sub-second Lazy Indexing & On-Demand Decompilation**: Instant APK loading (< 1-2s) parses DEX structures and indexes symbol tables without pre-decompiling source code. Single class decompilation executes on-demand in < 50ms.
- 🛡️ **Built-in Timeout & Circuit Breaker**: Single-class 5-second decompilation timeout protection (`Future.get(5s)`) prevents malicious AST obfuscation loops from blocking thread pools.
- 💾 **Three-Tier Code Caching**: Aligned with JADX-GUI preferences (`DISK_WITH_CACHE`, `MEMORY`, `DISK`), powered by `SoftReference` for automatic garbage collection under memory pressure.
- 🌐 **Clean RESTful API Architecture**: Strict `/domain/action` routing scheme covering `/apk/`, `/meta/`, `/decompile/`, `/resource/`, `/xref/`, and `/search/`.

---

## 🏗️ Architecture Overview

```
                        ┌─────────────────────────────────────────────────────────────┐
                        │              AI Agent / Client Application                  │
                        └───────────────────────┬─────────────────────────────┬───────┘
                                                │                             │
                                  HTTP REST API │                             │ MCP Stdio (JSON-RPC)
                                  (Port 8650)   │                             │
                                                ▼                             ▼
                        ┌─────────────────────────────────────────────────────────────┐
                        │                   Master Daemon Process                     │
                        │                 (JadxHttpServer / Main)                     │
                        └───────────────────────┬─────────────────────────────────────┘
                                                │
                                                │ Transparent Reverse Proxy / Process Pool
                                                ▼
              ┌─────────────────────────────────┼─────────────────────────────────┐
              │                                 │                                 │
              ▼                                 ▼                                 ▼
  ┌───────────────────────┐         ┌───────────────────────┐         ┌───────────────────────┐
  │   Worker Process #1   │         │   Worker Process #2   │         │   Worker Process #N   │
  │ (apk_id: UUID-1)      │         │ (apk_id: UUID-2)      │         │ (apk_id: UUID-N)      │
  │                       │         │                       │         │                       │
  │ • Isolated JVM PID    │         │ • Isolated JVM PID    │         │ • Isolated JVM PID    │
  │ • JadxEngine Instance │         │ • JadxEngine Instance │         │ • JadxEngine Instance │
  │ • SoftRef Code Cache  │         │ • SoftRef Code Cache  │         │ • SoftRef Code Cache  │
  └───────────────────────┘         └───────────────────────┘         └───────────────────────┘
```

---

## 📦 Prerequisites

- **JDK 21+**
- **Gradle 8.x** (or use the included `./gradlew` Wrapper)

---

## 🛠️ Build & Package

Build a standalone executable Fat-JAR with Gradle:

```bash
./gradlew :app:shadowJar
```

Upon build completion, the output Fat-JAR will be located at:
`app/build/libs/app-all.jar`

---

## 🚀 Quick Start

### 1. Launch Modes

#### HTTP REST Mode (Default)
Starts the Master HTTP server listening on port 8650:
```bash
java -jar app/build/libs/app-all.jar --port 8650
```

#### MCP Stdio Mode
Starts the server in Model Context Protocol Stdio transport mode for AI Agent integrations:
```bash
java -jar app/build/libs/app-all.jar --mcp
```

---

### 2. MCP (Model Context Protocol) Client Setup

Add `jadx-core-mcp` to your AI tool configuration (e.g., `opencode.jsonc` or Claude Desktop configuration `claude_desktop_config.json`):

```jsonc
{
  "mcp": {
    "jadx": {
      "type": "local",
      "command": [
        "java",
        "-jar",
        "/absolute/path/to/jadx-core-mcp/app/build/libs/app-all.jar",
        "--mcp"
      ],
      "enabled": true
    }
  }
}
```

*Note: MCP tool names follow domain-action conventions (`apk_load`, `meta_manifest`, `decompile_class`, `search_classes`, etc.) and are surfaced in MCP clients with the tool prefix `jadx_*`.*

---

## 📖 Complete REST API Specifications

All business endpoints require specifying the target `apk_id` via HTTP header `X-Apk-Id: <uuid>` or URL Query parameter `?apk_id=<uuid>`.

### 1. Service Lifecycle & Management (`/apk/*` & `/health`)

#### GET `/health`
- **Description**: Health status of Master node or active Worker node.
- **Response**: `{"status": "ok", "activeApksCount": 2, "isMaster": true}`

#### POST `/apk/load`
- **Description**: Dynamically load an APK file and spawn an isolated Worker process.
- **Parameters**: `apk_path` (string, required) - Absolute path to target APK file.
- **Example**: `curl -X POST "http://127.0.0.1:8650/apk/load?apk_path=/path/to/app.apk"`
- **Response**: `{"status": "success", "apk_id": "9f8a0b94-27d1-4e1b-9f93-e4c19a9f24e1", "apk_path": "/path/to/app.apk", "worker_port": 54321}`

#### POST `/apk/unload`
- **Description**: Destroy Worker sub-process for `apk_id` and release OS heap memory.
- **Parameters**: `apk_id` (string, required), `clear_cache` (boolean, optional, default: false).
- **Example**: `curl -X POST "http://127.0.0.1:8650/apk/unload?apk_id=9f8a0b94...&clear_cache=true"`

#### GET `/apk/list`
- **Description**: List all active loaded APK instances running in the process pool.

---

### 2. Metadata & Structure Analysis (`/meta/*`)

#### GET `/meta/manifest`
- **Description**: Extract raw text content of `AndroidManifest.xml`.
- **Example**: `curl "http://127.0.0.1:8650/meta/manifest?apk_id=<uuid>"`

#### GET `/meta/summary`
- **Description**: Structural summary (total class count, main activity name, APK path).
- **Response**: `{"classesCount": 3540, "mainActivity": "com.example.app.MainActivity", "currentApkPath": "/path/to/app.apk"}`

#### GET `/meta/classes`
- **Description**: Paginated list of fully qualified class names.
- **Parameters**: `offset` (default: 0), `count` (default: 50), `package` (optional prefix filter).

#### GET `/meta/methods`
- **Description**: Query method signatures declared in specified Class (`class_name` required).

#### GET `/meta/fields`
- **Description**: Query field declarations in specified Class (`class_name` required).

#### GET `/meta/main-activity`
- **Description**: Retrieve main activity class name and Java source code directly.

---

### 3. On-Demand Decompilation (`/decompile/*`)

#### GET `/decompile/java`
- **Description**: Decompile full Java source code on-demand for specified Class (`class_name` required).
- **Example**: `curl "http://127.0.0.1:8650/decompile/java?apk_id=<uuid>&class_name=com.example.app.MainActivity"`

#### GET `/decompile/smali`
- **Description**: Get Smali disassembly bytecode for specified Class (`class_name` required).

#### GET `/decompile/method`
- **Description**: Extract Java source code snippet for specific Method (`class_name` and `method_name` required).

---

### 4. Resource File Analysis (`/resource/*`)

#### GET `/resource/strings`
- **Description**: Paginated string table entries from `strings.xml` (`offset`, `count`).

#### GET `/resource/list`
- **Description**: Paginated relative paths of all resource files (e.g. `res/xml/network_security_config.xml`).

#### GET `/resource/file`
- **Description**: Read text content of specified resource file (`file_name` required).

---

### 5. Cross References & Search (`/xref/*` & `/search/*`)

#### GET `/xref/class`
- **Description**: Find cross references to specified Class (`class_name` required).

#### GET `/xref/method`
- **Description**: Find call sites and code snippets for specified Method (`class_name`, `method_name` required).

#### GET `/xref/field`
- **Description**: Find read/write reference sites for specified Field (`class_name`, `field_name` required).

#### GET `/search/classes`
- **Description**: Global search for term across code, class names, method names, fields, or comments.
- **Parameters**: `search_term` (required), `search_in` (`code`|`class`|`method`|`field`|`comment`), `package`, `offset`, `count`.

#### GET `/search/method`
- **Description**: Fuzzy match method names across all loaded classes (`method_name` required).

---

## 🛠️ MCP 20+ Tools Catalog

When launched with `--mcp`, `jadx-core-mcp` exposes 20 fine-grained reverse-engineering tools (`jadx_*` prefix added automatically by MCP clients):

| Domain | MCP Tool Name | Description |
| :--- | :--- | :--- |
| **Lifecycle** | `apk_load` | Dynamically load APK and spawn isolated Worker process |
| | `apk_unload` | Destroy Worker process and release OS heap memory |
| | `apk_list` | List active `apk_id` instances in process pool |
| **Metadata** | `meta_manifest` | Extract raw `AndroidManifest.xml` text |
| | `meta_summary` | Get APK summary (class count, main activity name) |
| | `meta_classes` | Paginated list of fully qualified class names |
| | `meta_methods` | Query method signatures for specified Class |
| | `meta_fields` | Query field declarations for specified Class |
| | `meta_main_activity` | Get main activity class name and Java source code |
| **Decompile** | `decompile_class` | Decompile full Java source code on-demand |
| | `decompile_smali` | Get Smali disassembly bytecode |
| | `decompile_method` | Extract Java source snippet for specific Method |
| **Resource** | `resource_strings` | Paginated extraction of `strings.xml` string table |
| | `resource_list` | Paginated enumeration of resource file paths |
| | `resource_file` | Read text content of specified resource file |
| **XRef** | `xref_class` | Find cross references to specified Class |
| | `xref_method` | Find call sites for specified Method |
| | `xref_field` | Find read/write reference sites for specified Field |
| **Search** | `search_classes` | Global search for keyword matching classes |
| | `search_method` | Fuzzy match method names across all classes |

---

## 📋 Logging & Operations Architecture

Runtime logs are automatically categorized and output to the `logs/` directory:

- `logs/server.log`: Master process full lifecycle logs (rotated at 10MB).
- `logs/error.log`: Master process error and crash stack traces for fast debugging.
- `logs/worker.log`: Worker process JADX decompilation, AST traversal, and resource unpacking logs.

### Useful Diagnostic Commands
```bash
# Inspect Master crash errors
grep "\[ERROR\]" logs/error.log

# Inspect Worker decompilation exceptions or timeouts
grep -i "exception\|error\|timeout" logs/worker.log

# Inspect process lifecycle & proxy requests
grep -i "ProcessManager\|JadxHttpServer" logs/server.log
```

---

## 🧪 Testing

Run full unit and integration test suite:

```bash
./gradlew test --rerun-tasks
```

---

## 📄 License

Distributed under the [Apache License 2.0](./LICENSE).

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](./CONTRIBUTING.md) for details on submitting issues and pull requests.
