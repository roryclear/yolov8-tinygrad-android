cmake_minimum_required(VERSION 3.10.2)
project("yolov8tinygradandroid")

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
    ${log-lib}
    ${android-lib}
    vulkan
)
