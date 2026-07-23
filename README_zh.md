# JADX Headless Server (jadx-core-mcp)

<p align="center">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/JDK-21%2B-ED8B00.svg" alt="JDK 21" />
  <img src="https://img.shields.io/badge/JADX-1.5.6-blue.svg" alt="JADX" />
  <img src="https://img.shields.io/badge/Protocol-MCP-green.svg" alt="MCP Protocol" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-red.svg" alt="License" />
</p>

<p align="center">
  <b>面向 AI 大模型 Agent 与自定义逆向分析工具的高性能、物理隔离多 APK 反编译与结构分析引擎。</b>
</p>

<p align="center">
  <a href="./README.md">English</a> | <a href="./README_zh.md">中文文档</a>
</p>

---

## ⚡ 核心特性

- 🚀 **多 Worker 进程物理隔离池**：Master 主进程统一路由管理，Worker 子进程按需拉起并为每个 APK 生成唯一的 `apk_id`（UUID）。卸载 APK 时直接摧毁子进程，从操作系统层面物理回收 JVM 堆内存与 Native 句柄，实现零内存泄漏。
- 🤖 **原生 MCP (Model Context Protocol) 协议支持**：内置官方 `io.modelcontextprotocol:kotlin-sdk`，通过 `--mcp` 参数一键启动 Stdio 模式，为 opencode、Claude Desktop、Cursor 等 AI Agent 提供原生 20+ JADX 逆向分析 Tools。
- ⚡ **秒级惰性加载与按需反编译**：加载 APK 时仅解析 DEX 结构并建立符号表索引（1~2 秒内完成），不预先反编译 Class 源码；单类反编译按需响应（< 50ms）。
- 🛡️ **超时断路防护**：集成单类 5 秒反编译超时保护 (`Future.get(5s)`)，防止极复杂混淆类引发死循环卡死 CPU。
- 💾 **三级代码缓存机制**：对齐 JADX-GUI 系统偏实现偏好设置 (`DISK_WITH_CACHE`, `MEMORY`, `DISK`)，采用 `SoftReference` 软引用缓存保护 JVM 堆内存，当内存紧张时由 GC 自动回收。
- 🌐 **纯粹的 RESTful 架构**：遵从 `/domain/action` 简洁领域划分（涵盖 `/apk/`、`/meta/`、`/decompile/`、`/resource/`、`/xref/`、`/search/`）。

---

## 🏗️ 架构概览

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
  │ • 独立 JVM PID        │         │ • 独立 JVM PID        │         │ • 独立 JVM PID        │
  │ • JadxEngine 实例     │         │ • JadxEngine 实例     │         │ • JadxEngine 实例     │
  │ • 软引用源码缓存      │         │ • 软引用源码缓存      │         │ • 软引用源码缓存      │
  └───────────────────────┘         └───────────────────────┘         └───────────────────────┘
```

---

## 📦 环境要求

- **JDK 21+**
- **Gradle 8.x**（推荐使用项目自带 `./gradlew` Wrapper）

---

## 🛠️ 编译打包

使用 Gradle 构建 Fat-JAR 可执行文件：

```bash
./gradlew :app:shadowJar
```

构建成功后，可执行文件输出至：
`app/build/libs/app-all.jar`

---

## 🚀 快速启动

### 1. 运行模式启动

#### HTTP REST 模式（默认）
启动 Master 守护服务（监听 8650 端口）：
```bash
java -jar app/build/libs/app-all.jar --port 8650
```

#### MCP Stdio 模式
以 Model Context Protocol Stdio 模式运行，供 AI Agent 直接交互：
```bash
java -jar app/build/libs/app-all.jar --mcp
```

---

### 2. MCP (Model Context Protocol) 客户端配置

在 AI 工具（例如 `opencode.jsonc` 或 Claude Desktop 配置文件 `claude_desktop_config.json`）中添加如下 `mcp` 配置：

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

*注意：MCP 工具命名遵从 `domain_action` 规范（如 `apk_load`, `meta_manifest`, `decompile_class`, `search_classes`），客户端加载后展示为 `jadx_apk_load`, `jadx_meta_manifest`, `jadx_decompile_class` 等。*

---

## 📖 完整 REST API 规格手册

所有业务 API 均要求在请求头 `X-Apk-Id: <uuid>` 或 URL Query 参数 `?apk_id=<uuid>` 中指定目标 `apk_id`。

### 1. 服务控制与生命周期 (`/apk/*` & `/health`)

#### GET `/health`
- **说明**：检查健康状态。未传 `apk_id` 返回 Master 健康信息；Worker 内部返回当前 APK 信息。
- **响应**：`{"status": "ok", "activeApksCount": 2, "isMaster": true}`

#### POST `/apk/load`
- **说明**：动态加载 Android APK 文件并拉起专属 Worker 子进程。
- **参数**：`apk_path` (string, 必需) - 待加载 of APK 文件绝对路径。
- **示例**：`curl -X POST "http://127.0.0.1:8650/apk/load?apk_path=/path/to/target.apk"`
- **响应**：`{"status": "success", "apk_id": "9f8a0b94-27d1-4e1b-9f93-e4c19a9f24e1", "apk_path": "/path/to/target.apk", "worker_port": 54321}`

#### POST `/apk/unload`
- **说明**：销毁指定 `apk_id` 的 Worker 子进程并彻底回收内存。
- **参数**：`apk_id` (string, 必需), `clear_cache` (boolean, 可选, 默认 false)。
- **示例**：`curl -X POST "http://127.0.0.1:8650/apk/unload?apk_id=9f8a0b94...&clear_cache=true"`

#### GET `/apk/list`
- **说明**：列出当前运行池中所有活跃的 APK 及其 UUID。

---

### 2. 元数据与结构分析 (`/meta/*`)

#### GET `/meta/manifest`
- **说明**：解析并返回 APK 的 `AndroidManifest.xml` 文本内容。
- **示例**：`curl "http://127.0.0.1:8650/meta/manifest?apk_id=<uuid>"`

#### GET `/meta/summary`
- **说明**：获取 APK 结构概览（类总数、Main Activity 类名、APK 路径）。
- **响应**：`{"classesCount": 3540, "mainActivity": "com.example.app.MainActivity", "currentApkPath": "/path/to/app.apk"}`

#### GET `/meta/classes`
- **说明**：分页获取 Class 全限定名列表。
- **参数**：`offset` (默认 0), `count` (默认 50), `package` (可选包名前缀过滤)。

#### GET `/meta/methods`
- **说明**：查询指定 Class 中的所有 Method 声明列表（必需参数 `class_name`）。

#### GET `/meta/fields`
- **说明**：查询指定 Class 中的所有 Field 字段声明列表（必需参数 `class_name`）。

#### GET `/meta/main-activity`
- **说明**：直接获取入口 Main Activity 类名及其 Java 源码。

---

### 3. 按需反编译 (`/decompile/*`)

#### GET `/decompile/java`
- **说明**：按需反编译指定 Class 的 Java 源码（必需参数 `class_name`）。
- **示例**：`curl "http://127.0.0.1:8650/decompile/java?apk_id=<uuid>&class_name=com.example.app.MainActivity"`

#### GET `/decompile/smali`
- **说明**：获取指定 Class 的 Smali 反汇编代码（必需参数 `class_name`）。

#### GET `/decompile/method`
- **说明**：精准提取指定 Class 中特定 Method 的 Java 源码切片（必需参数 `class_name`, `method_name`）。

---

### 4. 资源文件分析 (`/resource/*`)

#### GET `/resource/strings`
- **说明**：分页提取 `strings.xml` 常量列表（参数 `offset`, `count`）。

#### GET `/resource/list`
- **说明**：分页枚举所有资源文件相对路径（如 `res/xml/network_security_config.xml`）。

#### GET `/resource/file`
- **说明**：获取指定资源文件的文本内容（必需参数 `file_name`）。

---

### 5. 交叉引用与代码搜索 (`/xref/*` & `/search/*`)

#### GET `/xref/class`
- **说明**：追查 Class 交叉引用点及代码切片（必需参数 `class_name`）。

#### GET `/xref/method`
- **说明**：追查 Method 调用点及代码切片（必需参数 `class_name`, `method_name`）。

#### GET `/xref/field`
- **说明**：追查 Field 读写引用点及代码切片（必需参数 `class_name`, `field_name`）。

#### GET `/search/classes`
- **说明**：全局搜索代码、类名、方法名或注释中包含关键字的 Class。
- **参数**：`search_term` (必需), `search_in` (默认 code), `package`, `offset`, `count`。

#### GET `/search/method`
- **说明**：跨 Class 模糊匹配方法名称（必需参数 `method_name`）。

---

## 🛠️ MCP 20+ Tools 图谱

在启动 `--mcp` 模式后，`jadx-core-mcp` 自动向客户端注册以下 20 个 Tools（客户端中自动加 `jadx_` 前缀）：

| 分组 | MCP Tool 名称 | 功能描述 |
| :--- | :--- | :--- |
| **生命周期** | `apk_load` | 动态装载 APK 并拉起 Worker 子进程，返回 `apk_id` UUID |
| | `apk_unload` | 卸载 Worker 子进程，物理回收 OS 内存 |
| | `apk_list` | 列出当前运行池中所有活跃的 `apk_id` 及其信息 |
| **元数据** | `meta_manifest` | 提取 `AndroidManifest.xml` 文本内容 |
| | `meta_summary` | 获取 APK 结构概览（类总数、入口类名） |
| | `meta_classes` | 分页或按包名过滤获取 Class 全限定名列表 |
| | `meta_methods` | 查询指定 Class 中的所有 Method 签名 |
| | `meta_fields` | 查询指定 Class 中的所有 Field 声明 |
| | `meta_main_activity` | 获取 Main Activity 类名及其 Java 源码 |
| **反编译** | `decompile_class` | 按需反编译指定 Class 的全量 Java 源码 |
| | `decompile_smali` | 获取指定 Class 的 Smali 字节码 |
| | `decompile_method` | 提取指定 Class 中特定 Method 的源码切片 |
| **资源** | `resource_strings` | 分页提取 `strings.xml` 字符串表 |
| | `resource_list` | 分页枚举所有资源文件相对路径 |
| | `resource_file` | 获取指定资源文件（如 XML/JSON/配置）的文本内容 |
| **交叉引用** | `xref_class` | 追查 Class 的交叉引用点及代码切片 |
| | `xref_method` | 追查 Method 的调用位置及代码切片 |
| | `xref_field` | 追查 Field 的读写位置及代码切片 |
| **搜索** | `search_classes` | 全局搜索匹配关键字 of Class 列表 |
| | `search_method` | 跨 Class 模糊匹配方法名称 |

---

## 📋 日志架构与排查运维指南

系统运行日志自动分类落盘至 `logs/` 目录：

- `logs/server.log`：Master 主进程全量运行日志（按 10MB 切分轮转）。
- `logs/error.log`：Master 主进程独立 `ERROR` 崩溃日志，方便快速定位 Exception 堆栈。
- `logs/worker.log`：Worker 子进程内部 JADX 引擎日志、AST 遍历与资源解包日志。

### 常用日志排查命令
```bash
# 查看 Master 崩溃错误
grep "\[ERROR\]" logs/error.log

# 查看 Worker 子进程反编译异常
grep -i "exception\|error\|timeout" logs/worker.log

# 查看进程生命周期与代理历史
grep -i "ProcessManager\|JadxHttpServer" logs/server.log
```

---

## 🧪 测试验证

运行项目全量集成测试用例：

```bash
./gradlew test --rerun-tasks
```

---

## 📄 开源协议

本项目采用 [Apache License 2.0](./LICENSE) 协议开源。

## 🤝 贡献指南

欢迎贡献代码与建议！提交 Issue 或 PR 前请参阅 [CONTRIBUTING.md](./CONTRIBUTING.md)。
