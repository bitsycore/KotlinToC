# SDL3 for a ktc2 program: the SDL3 installed on the system when CMake finds one, otherwise
# built, as a static library, from the sources ktc2 fetched for the module.
# ${KTC2_TARGET} is the program, ${KTC2_FETCH_SDL3} where the sources are.

if(NOT TARGET SDL3::SDL3 AND NOT KTC2_SDL3_FROM_SOURCE)
	find_package(SDL3 CONFIG QUIET)
endif()

if(NOT TARGET SDL3::SDL3)
	set(SDL_SHARED OFF CACHE BOOL "" FORCE)
	set(SDL_STATIC ON CACHE BOOL "" FORCE)
	set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
	set(SDL_TESTS OFF CACHE BOOL "" FORCE)
	set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
	set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
	add_subdirectory("${KTC2_FETCH_SDL3}" "${CMAKE_BINARY_DIR}/sdl3" EXCLUDE_FROM_ALL)
endif()

target_link_libraries(${KTC2_TARGET} PRIVATE SDL3::SDL3)
