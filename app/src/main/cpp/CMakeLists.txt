cmake_minimum_required(VERSION 3.22.1)
project(ringpad C)

add_library(ringpad SHARED pad_device.c)

find_library(log-lib log)
target_link_libraries(ringpad ${log-lib})
