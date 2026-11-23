#pragma once

struct Item {
    int cents;
    Item* next;
};

class Cart {
public:
    explicit Cart(Item* first) : first_(first) {}
    int total() const;
    void checkTotal(int expected) const;

private:
    Item* first_;
};
