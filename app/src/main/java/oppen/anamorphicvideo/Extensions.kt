package oppen.anamorphicvideo

import android.content.Context
import androidx.preference.PreferenceManager

fun Context.getBooleanPref(key: String, default: Boolean): Boolean{
    return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(key, default)
}

fun Context.getStringPref(key: String, default: String): String? {
    return PreferenceManager.getDefaultSharedPreferences(this).getString(key, default)
}