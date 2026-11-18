package com.rton.howstheweather.widget

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.rton.howstheweather.R

/**
 * Zero-UI entry point for the widget's Google Search and Lens buttons.
 */
class WidgetSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetHaptics.performClick(this)
        if (intent.action == ACTION_OPEN_LENS) {
            launchGoogleLens(this)
        } else {
            launchGoogleSearch(this)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_OPEN_LENS = "com.rton.howstheweather.widget.ACTION_OPEN_LENS"

        private const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GOOGLE_LENS_PACKAGE = "com.google.ar.lens"
        private const val GOOGLE_SEARCH_ACTIVITY =
            "com.google.android.googlequicksearchbox.SearchActivity"

        private fun launchGoogleLens(context: Context) {
            val candidates = buildList {
                // Lens deep links used by Chromium; keep them scoped to the Google app.
                for (uri in listOf("googleapp://lens", "google://lens")) {
                    add(
                        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                            setPackage(GOOGLE_SEARCH_PACKAGE)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
                context.packageManager.getLaunchIntentForPackage(GOOGLE_LENS_PACKAGE)?.let {
                    add(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            for (candidate in candidates) {
                try {
                    context.startActivity(candidate)
                    return
                } catch (_: ActivityNotFoundException) {
                    // Try the next Lens entry point if this Google app version lacks it.
                } catch (_: SecurityException) {
                    // Some versions restrict an otherwise resolvable entry point.
                }
            }
            Toast.makeText(context, R.string.widget_google_lens_unavailable, Toast.LENGTH_LONG).show()
        }

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
