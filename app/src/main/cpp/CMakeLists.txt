cmake_minimum_required(VERSION 3.22)
project(vulkan_preprocess)

set(CMAKE_CXX_STANDARD 17)

# Vulkan 头文件 (NDK 自带)
find_package(Vulkan REQUIRED)

add_library(vulkan_preprocess SHARED
    vulkan_preprocessor.cpp
    vulkan_jni.cpp
)

target_link_libraries(vulkan_preprocess
    Vulkan::Vulkan
    android
    log
)
