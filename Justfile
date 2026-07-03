emulator := "emery"
phone := "192.168.0.205"
zig_opts := "-Dpebble_sdk_path=" + justfile_directory() + "/.pebble-data/pebble-sdk/SDKs/current"

# Build both pebble and android apps
build: build-pebble build-android

# Install both pebble and android apps
install: install-pebble install-android

# Build the Pebble app
build-pebble:
    zig build {{zig_opts}}

# Run the Pebble app on the emulator
run-pebble:
    PEBBLE_EMULATOR={{emulator}} zig build {{zig_opts}} upload

# Install the Pebble app on a device
install-pebble:
    PEBBLE_PHONE={{phone}} zig build {{zig_opts}} upload

# Build the Android app
build-android:
    ./gradlew assembleDebug

# Install the Android app on a connected device
install-android:
    ./gradlew installDebug
    adb shell am start -n sh.eliza.pebble.calnotify/.MainActivity
