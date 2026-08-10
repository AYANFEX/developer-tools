package com.unixshells.devbrowser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class Profile(
    val id: String,
    val name: String,
    val colorHex: String
) {
    val color: Int get() = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#4FC3F7") }
}

class ProfileManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "devbrowser_profiles"
        private const val KEY_PROFILES = "profiles_list"

        val DEFAULT_PROFILES = listOf(
            Profile("default", "Default", "#4FC3F7"),
            Profile("work", "Work / Dev", "#FFB74D"),
            Profile("testing", "Testing / QA", "#81C784"),
            Profile("guest", "Guest", "#BA68C8")
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProfiles(): List<Profile> {
        val jsonStr = prefs.getString(KEY_PROFILES, null) ?: return DEFAULT_PROFILES
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Profile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Profile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        colorHex = obj.getString("colorHex")
                    )
                )
            }
            if (list.isEmpty()) DEFAULT_PROFILES else list
        } catch (e: Exception) {
            DEFAULT_PROFILES
        }
    }

    fun saveProfiles(profiles: List<Profile>) {
        val array = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("colorHex", p.colorHex)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun addProfile(name: String, colorHex: String): Profile {
        val profiles = getProfiles().toMutableList()
        val id = "profile_" + System.currentTimeMillis()
        val newProfile = Profile(id, name, colorHex)
        profiles.add(newProfile)
        saveProfiles(profiles)
        return newProfile
    }

    fun deleteProfile(id: String) {
        val profiles = getProfiles().filter { it.id != id && it.id != "default" }
        saveProfiles(profiles)
    }
}
