emulator := "emery"
phone := "192.168.0.69"

# Build both pebble and android apps
build: build-pebble build-android

# Install both pebble and android apps
install: install-pebble install-android

# Build the Pebble app
build-pebble:
    pebble build

# Install the Pebble app on a device
install-pebble: build-pebble
    pebble install --phone {{phone}}

# View pebble logs
logs-pebble:
    pebble logs --phone {{phone}}

# Build the Android app
build-android:
    ./gradlew assembleDebug

# Install the Android app on a connected device
install-android:
    ./gradlew installDebug
    adb shell am start -n sh.eliza.pebble.calnotify/.MainActivity

# Pair ADB with Android over Wi-Fi
pair-android port code:
    adb pair {{phone}}:{{port}} {{code}}

# Format all sources
format:
    fd -e kt . | xargs ktlint -F
    fd -e c -e h . | xargs clang-format -i
    fd -e nix . | xargs nixfmt

# Clean all build files
clean:
    rm -rf .cache/ .gradle/ .kotlin/ build/ app/build/ \
    .lock-waf_linux_build compile_commands.json
