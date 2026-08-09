# SDL3 KotlinToC module - cmake injected automatically when SDL3 module is active.
# ─────────────────────────────────────────────────────────────────────────────
# Tries find_package first (uses a locally installed SDL3 if available),
# then falls back to FetchContent so no manual installation is needed.
# To override the git tag or repo, set the cache variables in your ktc_user.cmake:
#   set(SDL3_GIT_TAG  "release-3.x.y" CACHE STRING "SDL3 git tag" FORCE)
#   set(SDL3_GIT_REPO "https://..." CACHE STRING "SDL3 repo" FORCE)
# ─────────────────────────────────────────────────────────────────────────────

set(SDL3_GIT_TAG  "release-3.2.14" CACHE STRING "SDL3 git tag used by FetchContent")
set(SDL3_GIT_REPO "https://github.com/libsdl-org/SDL.git" CACHE STRING "SDL3 git repository")

find_package(SDL3 QUIET CONFIG)

if(NOT SDL3_FOUND)
    message(STATUS "SDL3 not found locally - fetching ${SDL3_GIT_TAG} from ${SDL3_GIT_REPO}")
    include(FetchContent)
    FetchContent_Declare(
        SDL3
        GIT_REPOSITORY "${SDL3_GIT_REPO}"
        GIT_TAG        "${SDL3_GIT_TAG}"
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(SDL3)
endif()

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
