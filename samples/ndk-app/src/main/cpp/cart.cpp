#include "cart.h"

#include <android/log.h>
#include <cstdlib>

int Cart::total() const {
    int sum = 0;
    for (Item* item = first_; item != nullptr; item = item->next) sum += item->cents;
    // An empty cart was never meant to get here: this reads through a null pointer.
    return sum + first_->next->cents;
}

void Cart::checkTotal(int expected) const {
    int sum = 0;
    for (Item* item = first_; item != nullptr; item = item->next) sum += item->cents;
    if (sum != expected) {
        __android_log_assert("sum != expected", "shop", "Cart total %d, expected %d", sum, expected);
    }
}
