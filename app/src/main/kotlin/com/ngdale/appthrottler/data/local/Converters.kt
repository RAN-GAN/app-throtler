package com.rangandale.appthrottler.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    fun toJson(values: List<String>): String = gson.toJson(values)

    fun fromJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
}