package io.zharp.sample.ndk

object NativeShop {
    init {
        System.loadLibrary("shop")
    }

    /** 0: a null pointer read in C++ (SIGSEGV); 1: a failed check (SIGABRT with a message). */
    @JvmStatic
    external fun checkout(kind: Int): Int
}
