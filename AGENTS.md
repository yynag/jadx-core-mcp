# JADX Headless MCP — Agent 用法

给大模型用的无头 JADX。先读摘要，再搜，再 xref，最后按需反编译。不要枚举全部类。

## 工作流

1. `apk_load`（大包 `max_heap=4g`；`deobf` 默认 true）
2. `meta_summary` — 看 `mainActivity`、`topPackages`
3. `meta_manifest` — 需要入口时加 `component_type=activity` + `only_exported=true`
4. `search`
   - 文案 / URL / 错误串：`scope=string`（DEX 常量；首次按需扫描，结果写入 APK 旁磁盘缓存，重启可复用）
   - 类名：`scope=class`
   - 方法/字段名：`scope=method_name` / `field`
   - 源码关键字：`scope=code`（慢，必须设 `timeout`/`max_scan`/`package`）
5. `xref` — 再 `decompile target=method`（整类可能 >80k 会被截断，看 `truncated`）
6. 大类 `timeout=90`。不要对 6 万类做 `meta_class` 全量翻页。

## 工具

| Tool | 用途 |
|------|------|
| apk_load / unload / list | Worker 生命周期 |
| meta_summary | 总览 |
| meta_manifest | XML 或组件列表 |
| meta_class | 类列表 / 方法字段签名；`main_app=true` |
| search | class/string/method_name/field/code/comment |
| xref | 交叉引用 + snippet |
| decompile | java/smali/method/main_activity |
| resource | list/file/strings/`id`（`0x7f…`） |
| rename | 别名（按 APK 指纹落盘，下次 load 自动恢复） |

失败为 `isError`，不会返回假源码。类名可用 `p000.` / `defpackage.` 别名。
