cmake_minimum_required(VERSION 3.22.1)
project(pixelcore CXX)

add_library(pixelcore SHARED pixelcore.cpp)
target_compile_options(pixelcore PRIVATE -O3 -ffast-math -Wall)
target_link_libraries(pixelcore log)
# 16KB 페이지 기기(Android 15+) 호환: LOAD 세그먼트를 16KB로 정렬한다(NDK 27은 기본 4KB).
target_link_options(pixelcore PRIVATE "-Wl,-z,max-page-size=16384")
