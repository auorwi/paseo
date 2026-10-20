package sh.paseo.trace

/** Monotonic time is supplied by the owner, so suspension and recovery are deterministic. */
internal class HeartbeatWatchdog(startMs: Long, private val emit: (String, Map<String, String>) -> Unit) {
  private var foreground = true
  private var lastHeartbeat = startMs
  private var overdue = false

  @Synchronized fun heartbeat(now: Long) {
    if (foreground && now - lastHeartbeat > 750) {
      emit("js.stall.recovered", mapOf("heartbeatGapMs" to "${now - lastHeartbeat}", "overdueMs" to "${now - lastHeartbeat - 250}"))
    }
    lastHeartbeat = now
    overdue = false
  }

  @Synchronized fun active(value: Boolean, now: Long) {
    if (foreground == value) return
    emit("app.lifecycle", mapOf("state" to if (value) "foreground" else "background", "stallOpen" to "$overdue"))
    foreground = value
    lastHeartbeat = now
    overdue = false
  }

  @Synchronized fun check(now: Long) {
    val gap = now - lastHeartbeat
    if (foreground && gap > 750 && !overdue) {
      emit("js.stall.started", mapOf("heartbeatGapMs" to "$gap", "lastHeartbeatMonoMs" to "$lastHeartbeat"))
      overdue = true
    }
  }
}
