package com.yyang.jadx_server.cache

/**
 * WHY: 历史枚举对齐 JADX-GUI 三级模式，但全项目无切换入口，MEMORY/DISK 分支为死代码且注释与 SoftReference 实现不符。
 * DECISION: 仅保留文档占位；运行时固定 soft+disk（见 JadxCacheManager），不再分支切换。
 * EVIDENCE: 审查 K5/K6；简化配置面，避免假「可控性」。
 */
@Deprecated("Runtime always uses soft-reference + disk; kept for API stability only.")
enum class CodeCacheMode {
    MEMORY,
    DISK_WITH_CACHE,
    DISK
}
