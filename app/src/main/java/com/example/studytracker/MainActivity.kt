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
    fun readingMinutes():
