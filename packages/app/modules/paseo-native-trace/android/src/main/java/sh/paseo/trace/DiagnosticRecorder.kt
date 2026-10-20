package sh.paseo.trace

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** All serialization and storage belongs to the worker, never the JS/UI threads. */
internal class DiagnosticRecorder(private val context: Context, private val metadata: Map<String, String>) {
  private data class Event(val name: String, val data: Map<String, String>, val monoMs: Long = SystemClock.elapsedRealtime(), val wallMs: Long = System.currentTimeMillis())
  private val queue = ArrayBlockingQueue<Event>(2048)
  private val dropped = AtomicLong()
  private val worker = Executors.newSingleThreadScheduledExecutor()
  private val session = UUID.randomUUID().toString()
  private var output: OutputStream? = null
  private var currentUri: android.net.Uri? = null
  private var size = 0
  private var part = 0
  @Volatile private var failed = false
  private val watchdog = HeartbeatWatchdog(SystemClock.elapsedRealtime(), ::record)

  init {
    record("session.start", metadata + mapOf("session" to session, "schema" to "1", "heartbeatMs" to "250", "stallThresholdMs" to "500"))
    worker.scheduleWithFixedDelay({ drain() }, 0, 250, TimeUnit.MILLISECONDS)
  }

  fun record(name: String, data: Map<String, String>) {
    if (failed) return
    // Limit native memory even if an instrumentation caller accidentally supplies large fields.
    val bounded = data.entries.take(24).associate { it.key.take(80) to it.value.take(256) }
    if (!queue.offer(Event(name.take(120), bounded))) dropped.incrementAndGet()
  }

  fun heartbeat() = watchdog.heartbeat(SystemClock.elapsedRealtime())
  fun active(value: Boolean) = watchdog.active(value, SystemClock.elapsedRealtime())

  private fun drain() {
    if (failed) return
    try {
      watchdog.check(SystemClock.elapsedRealtime())
      val lost = dropped.getAndSet(0)
      if (lost > 0) write(Event("recorder.dropped", mapOf("count" to "$lost")))
      // A traffic flood must not prevent watchdog checks or flushing.
      var remaining = 2048
      while (remaining-- > 0) {
        val event = queue.poll() ?: break
        write(event)
      }
      // Closing each batch publishes size/modified time to MediaStore. Keeping a
      // descriptor open makes the current file appear as 0 B in Android Files.
      output?.close()
      output = null
    } catch (error: Exception) {
      // Storage failure must never crash the app or create a retry storm.
      failed = true
      queue.clear()
      runCatching { output?.close() }
      output = null
      Log.e("PaseoDiagnostics", "Diagnostic recording stopped: storage unavailable", error)
    }
  }

  private fun write(event: Event) {
    val bytes = (JSONObject().apply {
      put("event", event.name)
      put("session", session)
      put("wallMs", event.wallMs)
      put("monoMs", event.monoMs)
      put("data", JSONObject(event.data))
    }.toString() + "\n").toByteArray(Charsets.UTF_8)
    if (currentUri == null || size + bytes.size > 5 * 1024 * 1024) rotate()
    if (output == null) {
      output = checkNotNull(context.contentResolver.openOutputStream(checkNotNull(currentUri), "wa")).buffered(64 * 1024)
    }
    output!!.write(bytes)
    size += bytes.size
  }

  private fun rotate() {
    output?.close()
    output = null
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, "paseo-diagnostics-${System.currentTimeMillis()}-${session.take(8)}-${part++}.jsonl")
      put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
      put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Paseo diagnostics/")
    }
    val uri = checkNotNull(resolver.insert(collection, values))
    currentUri = uri
    output = checkNotNull(resolver.openOutputStream(uri, "w")).buffered(64 * 1024)
    val header = (JSONObject().apply {
      put("event", "file.start")
      put("session", session)
      put("wallMs", System.currentTimeMillis())
      put("monoMs", SystemClock.elapsedRealtime())
      put("data", JSONObject(metadata + mapOf("schema" to "1", "part" to "$part")))
    }.toString() + "\n").toByteArray(Charsets.UTF_8)
    output!!.write(header)
    size = header.size
    // Only prune files created by this installation, never arbitrary user downloads.
    resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
      "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
      arrayOf("Download/Paseo diagnostics/", context.packageName), "${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
      var count = 0
      while (cursor.moveToNext()) {
        if (++count > 8) resolver.delete(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
      }
    }
  }

  fun close() {
    worker.execute {
      record("session.end", emptyMap())
      drain()
      runCatching { output?.close() }
      output = null
    }
    worker.shutdown()
  }
}
