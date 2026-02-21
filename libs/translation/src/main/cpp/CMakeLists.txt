cmake_minimum_required(VERSION 3.22.1)

project("argos_jni")

# 1.3.03 - Realization: Import prebuilt libraries
add_library(ctranslate2 SHARED IMPORTED)
set_target_properties(ctranslate2 PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libctranslate2.so)

add_library(sentencepiece SHARED IMPORTED)
set_target_properties(sentencepiece PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsentencepiece.so)

add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libomp.so)

# CTranslate2 requires C++17 (uses std::variant, std::optional, etc.)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include paths for headers
include_directories(include)

add_library(
        argos_jni
        SHARED
        argos_translator_jni.cpp)

# Enforce C++17 on the target as well
target_compile_features(argos_jni PRIVATE cxx_std_17)

find_library(
        log-lib
        log)

target_link_libraries(
        argos_jni
        ${log-lib}
        ctranslate2
        sentencepiece
        omp
)
