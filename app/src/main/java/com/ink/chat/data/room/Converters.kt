package com.ink.chat.data.room

import androidx.room.TypeConverter
import com.google.gson.Gson

/** Room 类型转换器：List<String>（attachments）↔ JSON 文本（§4.4） */
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.let { GSON.toJson(it) }

    @TypeConverter
    fun toStringList(value: String?): List<String>? = value?.let {
        runCatching { GSON.fromJson(it, Array<String>::class.java)?.toList() }.getOrNull()
    }

    companion object {
        private val GSON = Gson()
    }
}
