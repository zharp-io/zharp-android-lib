package io.zharp.android.internal

import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.WeakHashMap

/**
 * Screen views for AndroidX Fragments. The library is optional: this class is only loaded after
 * [FragmentSupport.available] said the app has it.
 */
internal class FragmentScreens(private val runtime: () -> Runtime?) : FragmentManager.FragmentLifecycleCallbacks() {
    private val appearing = WeakHashMap<Fragment, Long>()

    fun register(activity: Activity) {
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }
    }

    override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
        appearing[f] = Lifecycle.now()
    }

    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
        if (!appearing.containsKey(f)) appearing[f] = Lifecycle.now()
    }

    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        guarded(Unit, "onFragmentResumed") {
            val start = appearing.remove(f) ?: return@guarded
            val view = f.view
            // Headless fragments (retained state, image loaders) and navigation containers aren't screens.
            if (view == null || f.javaClass.simpleName in CONTAINERS) return@guarded
            val name = f.javaClass.simpleName.ifEmpty { f.javaClass.name }
            runtime()?.user?.screen = name
            Lifecycle.whenDrawn(view) { runtime()?.screenView(name, "fragment", start, Lifecycle.now()) }
        }
    }

    override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
        appearing.remove(f)
    }

    override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
        appearing.remove(f)
    }

    companion object {
        private val CONTAINERS = setOf("NavHostFragment")
    }
}

/** Kept apart from [FragmentScreens]: asking must not load a class whose superclass may be missing. */
internal object FragmentSupport {
    fun available(): Boolean = try {
        Class.forName("androidx.fragment.app.FragmentManager\$FragmentLifecycleCallbacks")
        true
    } catch (missing: Throwable) {
        false
    }
}
