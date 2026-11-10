package id.coolmax.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FreqFormatters {
    fun formatKhzToMhz(khz: Long): String {
        val mhz = khz / 1000L
        return "$mhz MHz"
    }

    fun formatDurationMs(ms: Long): String {
        val totalSec = ms / 1000L
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }
}
