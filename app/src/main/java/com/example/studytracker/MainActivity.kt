package com.example.studytracker

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.app.usage.UsageStatsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ---------- Data + storage ----------

data class Todo(val id: Long, val text: String, val done: Boolean)

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("study", Context.MODE_PRIVATE)

    fun todos(): List<Todo> {
        val arr = JSONArray(p.getString("todos", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Todo(o.getLong("id"), o.getString("text"), o.getBoolean("done"))
        }
    }

    fun saveTodos(list: List<Todo>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        p.edit().putString("todos", arr.toString()).apply()
    }

    private fun todayKey() = "read_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun readingMinutes(): Long = p.getLong(todayKey(), 0)
    fun addReading(minutes: Long) = p.edit().putLong(todayKey(), readingMinutes() + minutes).apply()

    var readingStart: Long
        get() = p.getLong("start", 0)
        set(v) = p.edit().putLong("start", v).apply()

    var prevFilter: Int
        get() = p.getInt("prevFilter", NotificationManager.INTERRUPTION_FILTER_ALL)
        set(v) = p.edit().putInt("prevFilter", v).apply()
}

// ---------- System helpers ----------

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Apps used today, sorted by time. Pair(app name, minutes) */
fun todayUsage(ctx: Context): List<Pair<String, Long>> {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val stats = usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
    val pm = ctx.packageManager
    return stats.values
        .filter { it.totalTimeInForeground >= 60_000 && pm.getLaunchIntentForPackage(it.packageName) != null }
        .map {
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString()
            } catch (e: Exception) { it.packageName }
            name to it.totalTimeInForeground / 60_000
        }
        .sortedByDescending { it.second }
}

/** Turns reading mode on/off using Do Not Disturb. Returns false if permission is missing. */
fun setReadingMode(ctx: Context, store: Store, on: Boolean): Boolean {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!nm.isNotificationPolicyAccessGranted) return false
    if (on) {
        store.prevFilter = nm.currentInterruptionFilter
        store.readingStart = System.currentTimeMillis()
        // PRIORITY = only calls/messages you allowed as "priority" in Do Not Disturb settings
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    } else {
        nm.setInterruptionFilter(store.prevFilter)
        if (store.readingStart > 0) {
            store.addReading((System.currentTimeMillis() - store.readingStart) / 60_000)
        }
        store.readingStart = 0
    }
    return true
}

fun fmt(min: Long) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashPrefs = getSharedPreferences("crash", Context.MODE_PRIVATE)
        val lastCrash = crashPrefs.getString("trace", null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            crashPrefs.edit().putString("trace", android.util.Log.getStackTraceString(e)).commit()
            Process.killProcess(Process.myPid())
        }
        if (lastCrash != null) {
            crashPrefs.edit().remove("trace").apply()
            setContent {
                LazyColumn(Modifier.padding(16.dp).padding(top = 40.dp)) {
                    item { Text("Crash report:\n\n" + lastCrash.take(1800)) }
                }
            }
            return
        }
        setContent { MaterialTheme { App(tick.intValue) } }
    }

    // Refresh data when coming back from the Settings screens
    override fun onResume() {
        super.onResume()
        tick.intValue++
    }
}

// ---------- UI ----------

@Composable
fun App(tick: Int) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var todos by remember { mutableStateOf(store.todos()) }
    val names = listOf("Today", "Apps", "To-do", "Reading")
    val icons = listOf("📊", "📱", "✅", "📖")

    Scaffold(bottomBar = {
        NavigationBar {
            names.forEachIndexed { i, n ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Text(icons[i]) }, label = { Text(n) }
                )
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(ctx, store, todos, tick)
                1 -> AppsScreen(ctx, tick)
                2 -> TodoScreen(todos) { todos = it; store.saveTodos(it) }
                else -> ReadingScreen(ctx, store, tick)
            }
        }
    }
}

@Composable
fun TodayScreen(ctx: Context, store: Store, todos: List<Todo>, tick: Int) {
    val access = remember(tick) { hasUsageAccess(ctx) }
    val screenMin = remember(tick) { if (access) todayUsage(ctx).sumOf { it.second } else 0L }
    val done = todos.count { it.done }
    val progress = if (todos.isEmpty()) 0f else done.toFloat() / todos.size

    Text("Daily Report", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Tasks: $done / ${todos.size} done")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Reading / study time: ${fmt(store.readingMinutes())}")
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (access) Text("Phone screen time: ${fmt(screenMin)}")
            else Text("Allow usage access in the Apps tab to see screen time.")
        }
    }
}

@Composable
fun AppsScreen(ctx: Context, tick: Int) {
    val access = remember(tick) { hasUsageAccess(ctx) }
    Text("App usage today", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    if (!access) {
        Text("To track your apps, please allow \"Usage access\" for this app.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
            Text("Open settings")
        }
        return
    }
    val usage = remember(tick) { todayUsage(ctx) }
    if (usage.isEmpty()) Text("No app usage recorded yet today.")
    LazyColumn {
        items(usage) { (name, min) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name)
                Text(fmt(min))
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun TodoScreen(todos: List<Todo>, onChange: (List<Todo>) -> Unit) {
    var text by remember { mutableStateOf("") }
    Text("To-do list", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f), label = { Text("New task") }, singleLine = true
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            if (text.isNotBlank()) {
                onChange(todos + Todo(System.currentTimeMillis(), text.trim(), false))
                text = ""
            }
        }) { Text("Add") }
    }
    LazyColumn {
        items(todos, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.done, onCheckedChange = { c ->
                    onChange(todos.map { if (it.id == t.id) it.copy(done = c) else it })
                })
                Text(t.text, Modifier.weight(1f))
                TextButton(onClick = { onChange(todos.filter { it.id != t.id }) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun ReadingScreen(ctx: Context, store: Store, tick: Int) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val granted = remember(tick) { nm.isNotificationPolicyAccessGranted }
    var on by remember(tick) { mutableStateOf(store.readingStart > 0) }

    Text("Reading mode", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "When on, notifications are silenced. Only calls and messages from the " +
            "contacts you allow as \"priority\" in Do Not Disturb settings will come through."
    )
    Spacer(Modifier.height(16.dp))
    if (!granted) {
        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) {
            Text("Allow Do Not Disturb access")
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "Reading mode is ON" else "Reading mode is OFF", Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { want ->
                if (setReadingMode(ctx, store, want)) on = want
            })
        }
        Spacer(Modifier.height(16.dp))
        Text("Today's reading time: ${fmt(store.readingMinutes())}")
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { ctx.startActivity(Intent("android.settings.ZEN_MODE_PRIORITY_SETTINGS")) }) {
            Text("Choose who can reach me")
        }
    }
}
