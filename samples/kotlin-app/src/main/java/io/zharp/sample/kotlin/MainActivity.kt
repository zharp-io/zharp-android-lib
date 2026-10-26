package io.zharp.sample.kotlin

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.zharp.logs.ZharpLogger

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = if (ZharpLogger.isActive()) "Logging to Zharp" else "Not logging — set zharp.apiKey in local.properties"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Clear of the status and title bars, which newer Android draws over the content.
            fitsSystemWindows = true
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(button("Send info") {
                ZharpLogger.info("Checkout opened", mapOf("cartItems" to 3, "currency" to "GHS"))
            })
            addView(button("Send warning") {
                ZharpLogger.warn("Slow network detected", mapOf("latencyMs" to 3200))
            })
            addView(button("Send caught error") {
                try {
                    error("Card declined")
                } catch (e: IllegalStateException) {
                    ZharpLogger.error("Payment failed", e, mapOf("orderId" to 99))
                }
            })
            addView(button("Set user context") {
                ZharpLogger.setContext(mapOf("userId" to 42))
                ZharpLogger.info("User signed in")
            })
            addView(button("Crash the app") {
                throw IllegalStateException("Sample crash from Kotlin")
            })
        }
        setContentView(layout)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
