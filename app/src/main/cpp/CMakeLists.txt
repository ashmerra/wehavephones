cmake_minimum_required(VERSION 3.22.1)

Project(WeHavePhones)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
# Align LOAD segments for 16 KB page-size devices. NDK 22 defaults to 4 KB, and
# Android warns about unaligned libraries in debuggable builds.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_subdirectory(patchelf)
add_subdirectory(adrenotools)
add_subdirectory(proot)
add_subdirectory(virglrenderer)
add_subdirectory(OpenXR-SDK)

add_library(wehavephones SHARED
            xr/engine.c
            xr/framebuffer.c
            xr/input.c
            xr/main.c
            xr/math.c
            xr/renderer.c
            wehavephones/drawable.c
            wehavephones/gpu_image.c
            wehavephones/sysvshared_memory.c
            wehavephones/xconnector_epoll.c
            wehavephones/xsocket_output.c
            wehavephones/alsa_client.c
            wehavephones/vulkan.c)

target_link_libraries(wehavephones
                      log
                      android
                      jnigraphics
                      openxr_loader
                      aaudio
                      EGL
                      GLESv2
                      GLESv3
                      adrenotools)

# Implicit Vulkan layer loaded by Wine from the app's native library directory
# on GPUs without native BCn (Pixel 8a, Mali-G715); see VulkanCompatLayer.java.
add_library(wehavephones_vulkan_compat SHARED wehavephones_vulkan_compat/wehavephones_vulkan_compat.c)
target_compile_options(wehavephones_vulkan_compat PRIVATE -fvisibility=hidden)
target_link_libraries(wehavephones_vulkan_compat dl)

# LD_PRELOAD for Wine processes on Android 17+ (Pixel 8a); see
# GuestProgramLauncherComponent.
add_library(wehavephones_fdsan SHARED wehavephones_fdsan/wehavephones_fdsan.c)
target_link_libraries(wehavephones_fdsan dl)
