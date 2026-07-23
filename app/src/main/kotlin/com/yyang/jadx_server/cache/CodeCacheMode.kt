package com.yyang.jadx_server.cache

/**
 * Code cache mode enumeration aligned with JADX-GUI system preferences.
 */
enum class CodeCacheMode {
    /** Pure in-memory strong reference cache: fastest query, highest memory overhead */
    MEMORY,

    /** Disk + memory soft-reference cache (Default/Recommended): balanced speed, automatic OOM prevention, fast re-load */
    DISK_WITH_CACHE,

    /** Pure disk cache: read-from-disk queries, lowest memory footprint */
    DISK
}
