package com.rton.howstheweather.diagnostics

/** Debug-only timing sink installed by the activity; disabled in release and JVM tests. */
internal object StartupDiagnostics {
    const val TAG = "WeatherStartup"
    private val origin = System.nanoTime()
    @Volatile private var sink: ((String) -> Unit)? = null

    fun install(output: (String) -> Unit) {
        sink = output
    }

    fun event(message: String) {
        val output = sink ?: return
        val elapsed = (System.nanoTime() - origin) / 1_000_000
        output("t=${elapsed}ms thread=${Thread.currentThread().name} $message")
    }

    fun begin(stage: String): Long {
        if (sink == null) return 0L
        event("$stage begin")
        return System.nanoTime()
    }

    fun end(stage: String, started: Long) {
        if (started == 0L) return
        event("$stage end durationMs=${(System.nanoTime() - started) / 1_000_000}")
    }

    fun <T> measure(stage: String, block: () -> T): T {
        val started = begin(stage)
        try {
            return block()
        } finally {
            end(stage, started)
        }
    }

    suspend fun <T> measureSuspending(stage: String, block: suspend () -> T): T {
        val started = begin(stage)
        try {
            return block()
        } finally {
            end(stage, started)
        }
    }
}
