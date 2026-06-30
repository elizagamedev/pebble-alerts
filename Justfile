emulator := "emery"
phone := "0.0.0.0"
zig_opts := "-Dpebble_sdk_path=" + justfile_directory() + "/.pebble-data/pebble-sdk/SDKs/current"

# Build
build:
    zig build {{zig_opts}}

# Run on the emulator
run:
    PEBBLE_EMULATOR={{emulator}} zig build {{zig_opts}} upload

# Install on a device
install:
    PEBBLE_PHONE={{phone}} zig build {{zig_opts}} upload
