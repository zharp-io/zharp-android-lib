package io.zharp.android.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import io.opentelemetry.sdk.common.Clock
import java.util.WeakHashMap

/**
 * Activity lifecycle: foreground/background (sessions, sending before the app may be killed) and
 * screen views. Looks up the running SDK on each callback, so it's registered once per process.
 */
internal class Lifecycle(
    private val runtime: () -> Runtime?,
    private val trackScreens: Boolean,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    /** When each activity began appearing: created, or brought back from the background. */
    private val appearing = WeakHashMap<Activity, Long>()

    private val fragments: FragmentScreens? =
        if (trackScreens && FragmentSupport.available()) guarded(null, "Fragment tracking") { FragmentScreens(runtime) } else null

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        guarded(Unit, "onActivityCreated") {
            appearing[activity] = now()
            fragments?.register(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        guarded(Unit, "onActivityStarted") {
            started++
            if (started == 1) runtime()?.session?.onForeground()
            if (!appearing.containsKey(activity)) appearing[activity] = now()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        guarded(Unit, "onActivityResumed") {
            val start = appearing.remove(activity) ?: return@guarded
            val name = activity.javaClass.simpleName
            runtime()?.user?.screen = name
            if (!trackScreens) return@guarded
            val decor = activity.window?.peekDecorView()
            whenDrawn(decor) { runtime()?.screenView(name, "activity", start, now()) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        guarded(Unit, "onActivityStopped") {
            started = maxOf(0, started - 1)
            // A rotation stops and restarts the activity; that's not leaving the app.
            if (started == 0 && !activity.isChangingConfigurations) {
                val current = runtime() ?: return@guarded
                current.session.onBackground()
                // A backgrounded app can be killed at any moment: send now.
                current.flush()
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        appearing.remove(activity)
    }

    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    companion object {
        fun now(): Long = Clock.getDefault().now()

        /** Run [block] when [view] next draws (its first frame), or now if there's no view. */
        fun whenDrawn(view: View?, block: () -> Unit) {
            if (view == null) {
                block()
                return
            }
            val observer = view.viewTreeObserver
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    guarded(Unit, "Ending a screen view") {
                        val live = view.viewTreeObserver
                        if (live.isAlive) live.removeOnPreDrawListener(this)
                        block()
                    }
                    return true
                }
            })
        }
    }
}
