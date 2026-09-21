cmake_minimum_required(VERSION 4.1)

project(OtpPush LANGUAGES C)

include(GNUInstallDirs)

# Where are binaries installed
set(INSTALL_BIN_DIR "${CMAKE_INSTALL_FULL_BINDIR}")

# Where to run cargo
set(CARGO_TARGET_DIR ${CMAKE_BINARY_DIR}/cargo_target)

# Tell CMake to build Rust binary
add_custom_target(build_rust ALL
    COMMAND cargo build --release --target-dir ${CARGO_TARGET_DIR}
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
)

# Rust binary full path
set(OTP_PUSH_SERVER_BIN ${CARGO_TARGET_DIR}/release/server)

# Configure files
configure_file(${CMAKE_SOURCE_DIR}/pkg/lib/systemd/user/otp-push-server@.service.in
    ${CMAKE_BINARY_DIR}/lib/systemd/user/otp-push-server@.service
    @ONLY
)

# Install executables
install(PROGRAMS
        ${OTP_PUSH_SERVER_BIN}
    TYPE BIN
    RENAME otp-push-server
)

# Install lib files
install(FILES
        ${CMAKE_BINARY_DIR}/lib/systemd/user/otp-push-server@.service
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/systemd/user
)

install(FILES
        ${CMAKE_SOURCE_DIR}/LICENSE
    DESTINATION ${CMAKE_INSTALL_DATADIR}/licenses/otp-push-server
)
