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
	val vExample = File(inOutDir, "ktc_user.cmake.example")
	if (!vExample.exists()) vExample.writeText(kSdl3ExampleCmake)
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

# ── SDL3 example ──────────────────────────────────────────────────────────────
#
# 1. Build & install SDL3 (or use a package manager such as vcpkg / Conan):
#      git clone https://github.com/libsdl-org/SDL.git  &&  cd SDL
#      cmake -B build -DCMAKE_BUILD_TYPE=Release  &&  cmake --build build --target install
#
# 2. If SDL3 was not installed to a standard prefix, point CMake at its config:
#      cmake -DSDL3_DIR=/path/to/SDL3/lib/cmake/SDL3 ..
#
# 3. Uncomment the lines below and re-run cmake.

# find_package(SDL3 REQUIRED CONFIG)
# target_link_libraries(${'$'}{PROJECT_NAME} PRIVATE SDL3::SDL3)
#
# On Windows the DLL must be next to the .exe at runtime.  A convenient way:
# add_custom_command(TARGET ${'$'}{PROJECT_NAME} POST_BUILD
#     COMMAND ${'$'}{CMAKE_COMMAND} -E copy_if_different
#         $<TARGET_FILE:SDL3::SDL3>
#         $<TARGET_FILE_DIR:${'$'}{PROJECT_NAME}>
# )

# ── Other library example (generic) ──────────────────────────────────────────
# find_package(SomeLib REQUIRED)
# target_link_libraries(${'$'}{PROJECT_NAME} PRIVATE SomeLib::SomeLib)
""".trimStart()
