# ktc_user.cmake — SDL3 integration for Sdl3Test
# ─────────────────────────────────────────────────────────────────────────────
# SDL3 is found via its CMake config package.
# If SDL3 is not installed to a standard prefix, point CMake at it:
#   cmake -DSDL3_DIR=/path/to/SDL3/lib/cmake/SDL3 -B out/_cmake -S out/
# ─────────────────────────────────────────────────────────────────────────────

find_package(SDL3 REQUIRED CONFIG)

target_link_libraries(${PROJECT_NAME} PRIVATE SDL3::SDL3)

# SDL_MAIN_HANDLED prevents SDL from redefining main() as SDL_main.
# The KTC transpiler generates its own main.c, so we own the entry point.
target_compile_definitions(${PROJECT_NAME} PRIVATE SDL_MAIN_HANDLED)

# On Windows: copy the SDL3 DLL next to the executable so it can be found at runtime.
if(WIN32)
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:SDL3::SDL3>
            $<TARGET_FILE_DIR:${PROJECT_NAME}>
        COMMENT "Copying SDL3.dll next to executable"
    )
endif()
