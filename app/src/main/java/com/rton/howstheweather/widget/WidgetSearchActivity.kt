package com.rton.howstheweather.widget

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Lightweight zero-UI trampoline activity that opens the Google Search app
 * directly in search input mode, with graceful fallbacks if the Google app is
 * disabled or not installed.
 */
class WidgetSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetHaptics.performClick(this)
        launchGoogleSearch(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GOOGLE_SEARCH_ACTIVITY =
            "com.google.android.googlequicksearchbox.SearchActivity"

        fun launchGoogleSearch(context: Context) {
            val candidates = buildList {
                // 1. Direct Google Search input overlay/activity
                add(
                    Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).apply {
                        setPackage(GOOGLE_SEARCH_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
                // 2. Explicit SearchActivity inside Google app
                add(
                    Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(GOOGLE_SEARCH_PACKAGE, GOOGLE_SEARCH_ACTIVITY)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
                // 3. Web search action targeted at Google app
                add(
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        setPackage(GOOGLE_SEARCH_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                // 4. Google app main launcher intent
                context.packageManager.getLaunchIntentForPackage(GOOGLE_SEARCH_PACKAGE)?.let { launchIntent ->
                    add(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // 5. System global search / web search fallback
                add(
                    Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                add(
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                // 6. Browser fallback to google.com
                add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }

            for (intent in candidates) {
                val launched = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (launched) return
            }
        }
    }
}
