# OteTV browser worker: a minimal native CEF host for the official YouTube embedded player (brief section 4, Gate D).
# JCEF hardcodes no_sandbox, so the Java binding cannot meet the sandbox requirement; this worker keeps the same IPC
# contract as the media worker and never disables the Chromium sandbox.
#
#   cmake -S browser-worker -B browser-worker/build -G Ninja -DCMAKE_BUILD_TYPE=Release
#   cmake --build browser-worker/build
#
# CEF_ROOT must point at an extracted CEF binary distribution (see runtime-packaging/fetch_cef.py).
cmake_minimum_required(VERSION 3.21)
project(otetv_browser_worker LANGUAGES C CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
  # The guarded proxy and process plumbing are POSIX code; a Windows port needs its own sandbox bootstrap
  # (cef_sandbox) and socket layer, and has not been built or tested.
  message(FATAL_ERROR "The OteTV browser worker currently builds on Linux only (see docs/feasibility.md).")
endif()

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(CEF_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/cef" CACHE PATH "Extracted CEF binary distribution")
set(OTETV_WORKER_VERSION "dev" CACHE STRING "Version reported in HELLO_ACK")
list(APPEND CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)

add_subdirectory(${CEF_LIBCEF_DLL_WRAPPER_PATH} libcef_dll_wrapper)
SET_CEF_TARGET_OUT_DIR()
ADD_LOGICAL_TARGET("libcef_lib" "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")

set(WORKER_SOURCES
  src/main.cc
  src/ipc.cc
  src/policy.cc
  src/proxy.cc
  src/wrapper_page.cc
  src/browser_host.cc
  )

add_executable(otetv-browser-worker ${WORKER_SOURCES})
SET_EXECUTABLE_TARGET_PROPERTIES(otetv-browser-worker)
add_dependencies(otetv-browser-worker libcef_dll_wrapper)
target_compile_definitions(otetv-browser-worker PRIVATE OTETV_WORKER_VERSION="${OTETV_WORKER_VERSION}")
# CEF builds without exceptions; the worker's own protocol parser uses them, and none ever crosses a CEF frame.
target_compile_options(otetv-browser-worker PRIVATE -fexceptions)
# Only libcef and the wrapper: the worker is windowless (headless Ozone), so it needs no X11 link of its own.
target_link_libraries(otetv-browser-worker libcef_lib libcef_dll_wrapper ${CMAKE_DL_LIBS} pthread)
# Libraries sit next to the executable in the runtime bundle.
set_target_properties(otetv-browser-worker PROPERTIES
  INSTALL_RPATH "$ORIGIN"
  BUILD_WITH_INSTALL_RPATH TRUE
  RUNTIME_OUTPUT_DIRECTORY "${CEF_TARGET_OUT_DIR}")

COPY_FILES(otetv-browser-worker "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${CEF_TARGET_OUT_DIR}")
COPY_FILES(otetv-browser-worker "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${CEF_TARGET_OUT_DIR}")
