package com.rangandale.appthrottler.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rangandale.appthrottler.domain.model.Scenario
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The user's own scenarios. Built-in ones live in code and are not stored. */
@Singleton
class ScenarioRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
) {
    private val prefs = context.getSharedPreferences("scenarios", Context.MODE_PRIVATE)
    private val type = object : TypeToken<List<Scenario>>() {}.type

    private val _scenarios = MutableStateFlow(read())
    val scenarios: StateFlow<List<Scenario>> = _scenarios.asStateFlow()

    private fun read(): List<Scenario> = try {
        prefs.getString(KEY, null)?.let { gson.fromJson<List<Scenario>>(it, type) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Adds [scenario], replacing one with the same name. */
    fun save(scenario: Scenario) = replaceAll(_scenarios.value.filter { it.name != scenario.name } + scenario)

    fun delete(name: String) = replaceAll(_scenarios.value.filter { it.name != name })

    fun replaceAll(scenarios: List<Scenario>) {
        val sorted = scenarios.sortedBy { it.name.lowercase() }
        _scenarios.value = sorted
        prefs.edit().putString(KEY, gson.toJson(sorted)).apply()
    }

    private companion object {
        const val KEY = "scenarios"
    }
}
