package io.zharp.sample.monitoring

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.zharp.android.Zharp
import okhttp3.Request

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = if (Zharp.isActive) "Sending to Zharp — session ${Zharp.sessionId}" else "Not started — set zharp.appKey in local.properties"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(button("Log info") { Zharp.log.info("Checkout opened", mapOf("cartItems" to 3)) })
            addView(button("Caught error") {
                try {
                    error("Card declined")
                } catch (e: IllegalStateException) {
                    Zharp.captureError(e, mapOf("orderId" to 99))
                }
            })
            addView(button("HTTP call (traced)") {
                Thread {
                    Zharp.startSpan("load orders") {
                        runCatching {
                            SampleApp.http.newCall(Request.Builder().url("http://10.0.2.2:3000/api/v1/health").build()).execute().close()
                        }.onFailure { Zharp.log.warn("Orders didn't load", mapOf("error" to it.toString())) }
                    }
                }.start()
            })
            addView(button("Open another screen") { startActivity(Intent(this@MainActivity, DetailActivity::class.java)) })
            addView(button("Freeze 6 s (ANR)") { Thread.sleep(6_000) })
            addView(button("Crash") { throw IllegalStateException("Sample crash") })
            addView(button("Sign out (new session)") { Zharp.clearUser() })
        }
        setContentView(layout)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}

class DetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Detail screen"; setPadding(48, 48, 48, 48) })
    }
}
