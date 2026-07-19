emulator := "diorite"
phone := "192.168.0.69"
store_file := env_var_or_default("KEYSTORE_FILE", env_var("HOME") + "/.AndroidKeyStore/calnotify.jks")
store_pass := env_var_or_default("KEYSTORE_PASSWORD", "")
key_pass := env_var_or_default("KEY_PASSWORD", store_pass)
key_alias := env_var_or_default("KEY_ALIAS", "calnotify")

# Build both pebble and android apps (debug mode)
build: build-pebble build-android

# Build both pebble and android apps (release mode)
build-release: build-pebble-release build-android-release

# Install both pebble and android apps
install: install-pebble install-android

# Build the Pebble app
build-pebble:
    pebble build --debug

# Build a release version of the Pebble app
build-pebble-release:
    pebble build

# Install the Pebble app on a device
install-pebble: build-pebble
    pebble install --phone {{phone}}

# Run the Pebble app in an emulator
run-pebble-emulator:
    PEBBLE_EMULATOR=1 pebble build --debug
    pebble install --emulator {{emulator}} --logs

# Generate the keystore
make-keystore:
    #!/usr/bin/env bash
    if [ -z "{{store_file}}" ] \
    || [ -z "{{key_alias}}" ] \
    || [ -z "{{store_pass}}" ] \
    || [ -z "{{key_pass}}" ]
    then
      echo "error: keystore variables not specified" >&2
      exit 1
    fi

    if [ -f "{{store_file}}" ]; then
      echo "error: keystore already exists" >&2
      exit 1
    fi

    mkdir -p "$(dirname "{{store_file}}")"
    chmod 700 "$(dirname "{{store_file}}")"

    keytool -genkeypair -v \
        -keystore "{{store_file}}" \
        -alias "{{key_alias}}" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 36500 \
        -storepass "{{store_pass}}" \
        -keypass "{{key_pass}}" \
        -dname "CN=Eliza Velasquez"

    chmod 600 "{{store_file}}"

# View pebble logs
logs-pebble:
    pebble logs --phone {{phone}}

# Build the Android app
build-android:
    ./gradlew assembleDebug

# Build a signed release version of the Android app
build-android-release:
    #!/usr/bin/env bash
    if [ -z "{{store_file}}" ] \
    || [ -z "{{key_alias}}" ] \
    || [ -z "{{store_pass}}" ] \
    || [ -z "{{key_pass}}" ]
    then
      echo "error: keystore variables not specified" >&2
      exit 1
    fi

    if [ ! -f "{{store_file}}" ]; then
      echo "error: keystore doesn't exist" >&2
      exit 1
    fi

    export KEYSTORE_FILE="{{store_file}}" \
           KEYSTORE_PASSWORD="{{store_pass}}" \
           KEY_PASSWORD="{{key_pass}}" \
           KEY_ALIAS="{{key_alias}}"

    set -x
    ./gradlew assembleRelease

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
