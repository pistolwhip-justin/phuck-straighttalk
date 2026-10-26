package com.datanet.share.common

object FormatBytes {
    private const val KB = 1024.0
    private const val MB = 1024.0 * 1024.0

    fun format(bytes: Long): String = when {
        bytes >= MB.toLong() -> String.format("%.2fMB", bytes / MB)
        bytes >= KB.toLong() -> String.format("%.2fKB", bytes / KB)
        else -> "${bytes}B"
    }

    fun formatRxTx(rx: Long, tx: Long): String = "rx=${format(rx)} tx=${format(tx)}"
}
