cmake_minimum_required(VERSION 3.22.1)
project(shop CXX)

add_library(shop SHARED shop.cpp cart.cpp)
target_link_libraries(shop log)
