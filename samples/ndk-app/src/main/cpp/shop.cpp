#include <jni.h>

#include "cart.h"

namespace {

int checkout(Item* first) {
    Cart cart(first);
    return cart.total();
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_zharp_sample_ndk_NativeShop_checkout(JNIEnv*, jclass, jint kind) {
    static Item last{250, nullptr};
    if (kind == 1) {
        // SIGABRT, with an abort message.
        Cart(&last).checkTotal(999);
        return 0;
    }
    // SIGSEGV: Cart::total() reads last.next->cents.
    return checkout(&last);
}
