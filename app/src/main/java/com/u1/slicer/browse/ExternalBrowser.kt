package com.u1.slicer.browse

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.u1.slicer.MainActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Open a web URL outside the app, excluding our own HTTPS VIEW handler from the chooser. */
fun openExternalBrowser(context: Context, url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (parsed.scheme !in setOf("http", "https") || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
        return false
    }
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(parsed.toString())).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val chooser = Intent.createChooser(view, null).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
