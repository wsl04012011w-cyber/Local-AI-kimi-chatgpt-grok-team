cmake_minimum_required(VERSION 3.22.1)
project(localai-jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The CI workflow downloads the pinned llama.cpp source into this directory.
get_filename_component(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp" ABSOLUTE)
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp nao encontrado em ${LLAMA_CPP_DIR}. "
        "Execute o workflow do GitHub Actions ou baixe a tag v0.3.0.")
endif()

# Build only the CPU inference libraries needed by this app.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Arquitetura portável para arm64; i8mm e KleidiAI são opt-in.
option(LOCALAI_ARM_I8MM "Ativar kernels ARM i8mm (somente CPUs compatíveis)" OFF)
option(LOCALAI_KLEIDIAI "Ativar kernels KleidiAI no arm64-v8a" OFF)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(LOCALAI_ARM_I8MM)
        set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm" CACHE STRING "" FORCE)
    else()
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    endif()
    if(LOCALAI_KLEIDIAI)
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    else()
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    endif()
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(llama-jni SHARED llama-jni.cpp)
target_include_directories(llama-jni PRIVATE "${LLAMA_CPP_DIR}/include")
target_link_libraries(llama-jni PRIVATE llama android log)
