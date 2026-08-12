package com.yyang.jadx_server.service

/**
 * WHY: 超时/失败若以 HTTP 200 + 伪源码返回，Agent 会当真码继续推理，污染整条逆向链。
 * DECISION: 统一抛受检业务异常，由 HTTP/MCP 层映射为非成功状态（4xx/5xx 或 isError=true）。
 * EVIDENCE: 审查缺陷 S0「假源码 200」；MCP SDK CallToolResult.isError 契约。
 */
class DecompileException(
    val code: String,
    message: String,
    val httpStatus: Int = 504
) : RuntimeException(message) {
    companion object {
        const val TIMEOUT = "DECOMPILE_TIMEOUT"
        const val FAILED = "DECOMPILE_FAILED"
        const val NOT_FOUND = "NOT_FOUND"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val BUDGET_EXCEEDED = "SEARCH_BUDGET_EXCEEDED"
    }
}
