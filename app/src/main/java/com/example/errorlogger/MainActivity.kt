
package com.example.errorlogger

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private val Background = Color(0xFF10141D)
private val Panel = Color(0xFF1B2230)
private val Green = Color(0xFF55D6A0)
private val Red = Color(0xFFFF6B75)
private val Yellow = Color(0xFFFFC857)
private val Blue = Color(0xFF79B8FF)
private val Muted = Color(0xFF9BA8BA)

private const val LOG_TAG = "ErrorLogger"
private const val MAX_LOG_ENTRIES = 200

data class LogEntry(
    val level: String,
    val message: String,
    val time: String,
    val details: String = ""
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashReportStore.install(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green,
                    background = Background,
                    surface = Panel
                )
            ) {
                ErrorLoggerScreen()
            }
        }
    }
}

/**
 * Stores application logs and the most recent uncaught crash report.
 */
private object CrashReportStore {

    private const val PREFS_NAME = "error_logger_storage"
    private const val LOGS_KEY = "saved_logs"
    private const val CRASH_KEY = "last_crash_report"

    @Volatile
    private var handlerInstalled = false

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadLogs(context: Context): List<LogEntry> {
        val savedJson = preferences(context).getString(LOGS_KEY, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(savedJson)
            val entries = mutableListOf<LogEntry>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                entries.add(
                    LogEntry(
                        level = item.optString("level", "INFO"),
                        message = item.optString("message", ""),
                        time = item.optString("time", ""),
                        details = item.optString("details", "")
                    )
                )
            }

            entries
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Unable to load saved logs", exception)
            emptyList()
        }
    }

    fun saveLogs(context: Context, logs: List<LogEntry>) {
        try {
            val array = JSONArray()

            logs.takeLast(MAX_LOG_ENTRIES).forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("level", entry.level)
                        put("message", entry.message)
                        put("time", entry.time)
                        put("details", entry.details)
                    }
                )
            }

            preferences(context).edit()
                .putString(LOGS_KEY, array.toString())
                .apply()
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Unable to save logs", exception)
        }
    }

    fun saveCrashReport(context: Context, report: String) {
        // commit() writes synchronously, which is useful when handling a crash.
        preferences(context).edit()
            .putString(CRASH_KEY, report)
            .commit()
    }

    fun loadCrashReport(context: Context): String {
        return preferences(context).getString(CRASH_KEY, "") ?: ""
    }

    fun clearCrashReport(context: Context) {
        preferences(context).edit()
            .remove(CRASH_KEY)
            .apply()
    }

    @Synchronized
    fun install(context: Context) {
        if (handlerInstalled) return

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    appendLine("UNCAUGHT APPLICATION EXCEPTION")
                    appendLine("Time: ${currentTime()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine(
                        Log.getStackTraceString(throwable)
                    )
                }

                saveCrashReport(appContext, report)

                Log.e(
                    LOG_TAG,
                    "Uncaught exception saved to crash report storage",
                    throwable
                )
            } catch (storageException: Exception) {
                Log.e(
                    LOG_TAG,
                    "Failed to save crash report",
                    storageException
                )
            }

            // Preserve Android's normal crash handling.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(
                    android.os.Process.myPid()
                )
                kotlin.system.exitProcess(10)
            }
        }

        handlerInstalled = true
    }
}

@Composable
fun ErrorLoggerScreen() {
    val context = LocalContext.current.applicationContext

    val logs = remember {
        mutableStateListOf<LogEntry>().apply {
            addAll(CrashReportStore.loadLogs(context))

            if (isEmpty()) {
                add(
                    LogEntry(
                        level = "INFO",
                        message = "Error Logger initialized",
                        time = currentTime(),
                        details = "Application started successfully."
                    )
                )
                CrashReportStore.saveLogs(context, this)
            }
        }
    }

    var memoryText by remember { mutableStateOf("") }

    var performanceText by remember {
        mutableStateOf("No performance test run yet.")
    }

    var crashReportText by remember { mutableStateOf("") }

    val errors = logs.count { it.level == "ERROR" }
    val warnings = logs.count { it.level == "WARN" }
    val info = logs.count { it.level == "INFO" }

    Scaffold(
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "ERROR LOGGER",
                color = Green,
                fontSize = 25.sp,
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "ANDROID DEBUGGING DASHBOARD",
                color = Muted,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    "INFO", info.toString(), Blue,
                    Modifier.weight(1f)
                )
                SummaryCard(
                    "WARN", warnings.toString(), Yellow,
                    Modifier.weight(1f)
                )
                SummaryCard(
                    "ERROR", errors.toString(), Red,
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "TEST CONTROLS",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        addLog(
                            context, logs, "INFO",
                            "Test info message"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Info")
                }

                Button(
                    onClick = {
                        addLog(
                            context, logs, "WARN",
                            "Test warning message"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Warning")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            throw IllegalStateException(
                                "This is a simulated test exception."
                            )
                        } catch (exception: Exception) {
                            val details =
                                Log.getStackTraceString(exception)

                            Log.e(
                                LOG_TAG,
                                "Simulated exception captured",
                                exception
                            )

                            addLog(
                                context,
                                logs,
                                "ERROR",
                                exception.message ?: "Unknown error",
                                details
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Error")
                }

                OutlinedButton(
                    onClick = {
                        logs.clear()
                        addLog(
                            context, logs, "INFO",
                            "Logs cleared"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Logs")
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "PERFORMANCE MONITOR",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Button(
                        onClick = {
                            val runtime = Runtime.getRuntime()

                            val usedBefore =
                                runtime.totalMemory() -
                                        runtime.freeMemory()

                            val start = System.nanoTime()

                            var result = 0L
                            for (i in 1..100_000) {
                                result += i.toLong()
                            }

                            val elapsedMs =
                                (System.nanoTime() - start) /
                                        1_000_000.0

                            val usedAfter =
                                runtime.totalMemory() -
                                        runtime.freeMemory()

                            performanceText =
                                "Test duration: ${
                                    "%.3f".format(
                                        Locale.US, elapsedMs
                                    )
                                } ms\n" +
                                        "Approx. memory change: ${
                                            (usedAfter - usedBefore) / 1024
                                        } KB\n" +
                                        "Test result: $result"

                            addLog(
                                context,
                                logs,
                                "INFO",
                                "Performance test completed in " +
                                        "${
                                            "%.3f".format(
                                                Locale.US, elapsedMs
                                            )
                                        } ms"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run Performance Test")
                    }

                    Text(
                        text = performanceText,
                        color = Green,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val runtime = Runtime.getRuntime()

                            val used = runtime.totalMemory() -
                                    runtime.freeMemory()

                            val max = runtime.maxMemory()
                            val native =
                                Debug.getNativeHeapAllocatedSize()

                            memoryText =
                                "Java heap used: ${
                                    used / (1024 * 1024)
                                } MB\n" +
                                        "Java heap maximum: ${
                                            max / (1024 * 1024)
                                        } MB\n" +
                                        "Native heap allocated: ${
                                            native / (1024 * 1024)
                                        } MB\n\n" +
                                        "Note: These are memory snapshots, " +
                                        "not proof of a memory leak."

                            addLog(
                                context,
                                logs,
                                "INFO",
                                "Memory diagnostics collected"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check Memory")
                    }

                    if (memoryText.isNotEmpty()) {
                        Text(
                            text = memoryText,
                            color = Blue,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            crashReportText =
                                CrashReportStore.loadCrashReport(context)

                            if (crashReportText.isBlank()) {
                                crashReportText =
                                    "No saved uncaught crash report found."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View Last Crash Report")
                    }

                    if (crashReportText.isNotEmpty()) {
                        Text(
                            text = crashReportText,
                            color = if (
                                crashReportText.startsWith(
                                    "UNCAUGHT APPLICATION EXCEPTION"
                                )
                            ) Red else Muted,
                            fontSize = 11.sp
                        )

                        OutlinedButton(
                            onClick = {
                                CrashReportStore.clearCrashReport(context)
                                crashReportText =
                                    "Saved crash report cleared."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear Crash Report")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "LOG HISTORY (${logs.size})",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No logs available.", color = Muted)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    logs.reversed().forEach { entry ->
                        LogCard(entry)
                    }
                }
                    }
                }
            }
        }


@Composable
fun SummaryCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Panel
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = color, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 23.sp)
        }
    }
}

@Composable
fun LogCard(entry: LogEntry) {
    val levelColor = when (entry.level) {
        "ERROR" -> Red
        "WARN" -> Yellow
        "DEBUG" -> Blue
        else -> Green
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Panel
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = levelColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(5.dp)
                ) {
                    Text(
                        text = entry.level,
                        color = levelColor,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        )
                    )
                }

                Spacer(Modifier.size(8.dp))

                Text(
                    text = entry.time,
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = entry.message,
                color = Color.White,
                fontSize = 14.sp
            )

            if (entry.details.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.details,
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 5
                )
            }
        }
    }
}

fun addLog(
    context: Context,
    logs: MutableList<LogEntry>,
    level: String,
    message: String,
    details: String = ""
) {
    val entry = LogEntry(
        level = level,
        message = message,
        time = currentTime(),
        details = details
    )

    logs.add(entry)

    // Keep the saved history bounded to avoid unlimited growth.
    while (logs.size > MAX_LOG_ENTRIES) {
        logs.removeAt(0)
    }

    CrashReportStore.saveLogs(context, logs)

    when (level) {
        "ERROR" -> Log.e(LOG_TAG, message)
        "WARN" -> Log.w(LOG_TAG, message)
        "DEBUG" -> Log.d(LOG_TAG, message)
        else -> Log.i(LOG_TAG, message)
    }
}

fun currentTime(): String {
    return SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date())
}
