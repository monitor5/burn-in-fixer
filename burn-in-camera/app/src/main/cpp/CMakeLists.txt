cmake_minimum_required(VERSION 3.22.1)
project(pixelcore CXX)

add_library(pixelcore SHARED pixelcore.cpp)
target_compile_options(pixelcore PRIVATE -O3 -ffast-math -Wall)
target_link_libraries(pixelcore log)
