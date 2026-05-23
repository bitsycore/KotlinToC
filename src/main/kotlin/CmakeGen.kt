package com.bitsycore.ktc

import java.io.File

// ==================
// MARK: CMake generation
// ==================

/* Generates CMakeLists.txt and a ktc_user.cmake.example alongside the C output. */
internal fun writeCmakeFiles(
	inOutDir:      File,
	inExeName:     String,   // target executable name
	inKtcSources:  List<String>,  // paths relative to inOutDir, e.g. "ktc/core/ktc_core.c"
	inUserSources: List<String>,  // paths relative to inOutDir, e.g. "com/example/Point.c"
	) {
	File(inOutDir, "CMakeLists.txt").writeText(buildCmakeLists(inExeName, inKtcSources, inUserSources))
	}

/* Build the CMakeLists.txt content. */
private fun buildCmakeLists(
	inExeName:    String,
	inKtcSrcs:   List<String>,
	inUserSrcs:  List<String>,
	): String = buildString {

	appendLine("cmake_minimum_required(VERSION 3.16)")
	appendLine("project($inExeName C)")
	appendLine()

	// ── Build type ──────────────────────────────────────────────────────
	appendLine("# Default build type when none is specified on the command line.")
	appendLine("# Override with: cmake -DCMAKE_BUILD_TYPE=Debug ..")
	appendLine("if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)")
	appendLine("    set(CMAKE_BUILD_TYPE \"Release\" CACHE STRING")
	appendLine("        \"Build type: Debug | Release | RelWithDebInfo | MinSizeRel\" FORCE)")
	appendLine("    set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS")
	appendLine("        \"Debug\" \"Release\" \"RelWithDebInfo\" \"MinSizeRel\")")
	appendLine("endif()")
	appendLine()

	// ── Static libc ─────────────────────────────────────────────────────
	appendLine("# Link C runtime statically.  Override with: cmake -DKTC_STATIC_LIBC=ON ..")
	appendLine("option(KTC_STATIC_LIBC \"Link the C runtime library statically\" OFF)")
	appendLine()

	// ── Sources ──────────────────────────────────────────────────────────
	appendLine("# ── Sources ───────────────────────────────────────────────────────────")
	appendLine("set(KTC_SOURCES")
	appendLine("    # ktc runtime")
	for (vSrc in inKtcSrcs) appendLine("    $vSrc")
	if (inUserSrcs.isNotEmpty()) {
		appendLine("    # generated user code")
		for (vSrc in inUserSrcs) appendLine("    $vSrc")
		}
	appendLine(")")
	appendLine()

	// ── Output directory ────────────────────────────────────────────────
	// Place the exe in the source dir (= out/) so test runners can find it predictably,
	// regardless of generator (multi-config generators would otherwise add a subdir).
	appendLine("set(CMAKE_RUNTIME_OUTPUT_DIRECTORY \"\${CMAKE_SOURCE_DIR}\")")
	appendLine("set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_DEBUG \"\${CMAKE_SOURCE_DIR}\")")
	appendLine("set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE \"\${CMAKE_SOURCE_DIR}\")")
	appendLine("set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO \"\${CMAKE_SOURCE_DIR}\")")
	appendLine("set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL \"\${CMAKE_SOURCE_DIR}\")")
	appendLine()

	// ── Target ───────────────────────────────────────────────────────────
	appendLine("add_executable(\${PROJECT_NAME} \${KTC_SOURCES})")
	appendLine()

	// ── C standard ──────────────────────────────────────────────────────
	appendLine("set_property(TARGET \${PROJECT_NAME} PROPERTY C_STANDARD 11)")
	appendLine("set_property(TARGET \${PROJECT_NAME} PROPERTY C_STANDARD_REQUIRED ON)")
	appendLine()

	// ── Include paths ────────────────────────────────────────────────────
	appendLine("# -iquote equivalent: headers are resolved relative to the output root.")
	appendLine("target_include_directories(\${PROJECT_NAME} PRIVATE \"\${CMAKE_CURRENT_SOURCE_DIR}\")")
	appendLine()

	// ── Static libc linkage ──────────────────────────────────────────────
	appendLine("if(KTC_STATIC_LIBC)")
	appendLine("    if(MSVC)")
	appendLine("        set_property(TARGET \${PROJECT_NAME} PROPERTY")
	appendLine("            MSVC_RUNTIME_LIBRARY \"MultiThreaded\$<\$<CONFIG:Debug>:Debug>\")")
	appendLine("    else()")
	appendLine("        target_link_options(\${PROJECT_NAME} PRIVATE -static)")
	appendLine("    endif()")
	appendLine("endif()")
	appendLine()

	// ── User hook ────────────────────────────────────────────────────────
	appendLine("# ── External libraries ────────────────────────────────────────────────")
	appendLine("# Copy ktc_user.cmake.example to ktc_user.cmake and add your own")
	appendLine("# find_package / target_link_libraries calls there.")
	appendLine("# That file is never overwritten by the transpiler.")
	appendLine("include(\"\${CMAKE_CURRENT_SOURCE_DIR}/ktc_user.cmake\" OPTIONAL)")
	}

// SDL3 example shown in ktc_user.cmake.example
private val kSdl3ExampleCmake = """
# ktc_user.cmake.example
# ─────────────────────────────────────────────────────────────────────────────
# Copy this file to  ktc_user.cmake  (same directory) and adapt it.
# It is included by the generated CMakeLists.txt after the main target is
# defined, so you can call target_link_libraries / target_include_directories
# / target_compile_definitions freely.
#
# This file is NEVER overwritten by the transpiler — your edits are safe.
# ─────────────────────────────────────────────────────────────────────────────

# ── SDL3 example (find locally, auto-fetch if not installed) ──────────────────

# Uncomment to override the pinned tag or repository URL:
# set(SDL3_GIT_TAG  "release-3.2.14" CACHE STRING "SDL3 git tag")
# set(SDL3_GIT_REPO "https://github.com/libsdl-org/SDL.git" CACHE STRING "SDL3 repo")

# find_package(SDL3 QUIET CONFIG)
# if(NOT SDL3_FOUND)
#     set(SDL3_GIT_TAG  "release-3.2.14")
#     set(SDL3_GIT_REPO "https://github.com/libsdl-org/SDL.git")
#     message(STATUS "SDL3 not found locally — fetching ${'$'}{SDL3_GIT_TAG}")
#     include(FetchContent)
#     FetchContent_Declare(SDL3
#         GIT_REPOSITORY "${'$'}{SDL3_GIT_REPO}"
#         GIT_TAG        "${'$'}{SDL3_GIT_TAG}"
#         GIT_SHALLOW    TRUE
#     )
#     FetchContent_MakeAvailable(SDL3)
# endif()
#
# target_link_libraries(${'$'}{PROJECT_NAME} PRIVATE SDL3::SDL3)
# target_compile_definitions(${'$'}{PROJECT_NAME} PRIVATE SDL_MAIN_HANDLED)
#
# # Windows: copy SDL3.dll next to the exe
# if(WIN32)
#     add_custom_command(TARGET ${'$'}{PROJECT_NAME} POST_BUILD
#         COMMAND ${'$'}{CMAKE_COMMAND} -E copy_if_different
#             $<TARGET_FILE:SDL3::SDL3>
#             $<TARGET_FILE_DIR:${'$'}{PROJECT_NAME}>
#     )
# endif()

# ── Other library example (generic) ──────────────────────────────────────────
# find_package(SomeLib REQUIRED)
# target_link_libraries(${'$'}{PROJECT_NAME} PRIVATE SomeLib::SomeLib)
""".trimStart()
