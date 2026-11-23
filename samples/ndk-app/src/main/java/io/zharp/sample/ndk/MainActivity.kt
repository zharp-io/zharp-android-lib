package io.zharp.sample.ndk

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout

/**
 * Two buttons that crash in C++. Scripted runs pass `--es crash segv` (or `abort`): it crashes
 * a moment after the screen is shown, and the next launch reports it.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply {
            text = "Crash in C++ (null pointer)"
            setOnClickListener { NativeShop.checkout(0) }
        })
        layout.addView(Button(this).apply {
            text = "Crash in C++ (abort)"
            setOnClickListener { NativeShop.checkout(1) }
        })
        setContentView(layout)

        when (intent.getStringExtra("crash")) {
            "segv" -> Handler(Looper.getMainLooper()).postDelayed({ NativeShop.checkout(0) }, 1_500)
            "abort" -> Handler(Looper.getMainLooper()).postDelayed({ NativeShop.checkout(1) }, 1_500)
        }
    }
}
