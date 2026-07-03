{
  description = "Pebble Time 2 Zig dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "36" "24" ];
          buildToolsVersions = [ "35.0.0" ];
        };

        pebble-wrapper = pkgs.writeShellScriptBin "pebble" ''
          exec uv run --with pebble-tool env XDG_DATA_HOME="$PWD/.pebble-data" pebble "$@"
        '';
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            just
            nodejs
            pebble-wrapper
            python3
            uv
            zig
            jdk17
            android-studio
            androidComposition.androidsdk
            kotlin-language-server
            zls
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk17.home}"
            export ANDROID_SDK_ROOT="${androidComposition.androidsdk}/libexec/android-sdk"
            export ANDROID_HOME="${androidComposition.androidsdk}/libexec/android-sdk"
            echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
            export LD_LIBRARY_PATH="${
              pkgs.lib.makeLibraryPath (
                with pkgs;
                [
                  SDL2
                  alsa-lib
                  glib
                  libpng
                  libpulseaudio
                  pixman
                  stdenv.cc.cc.lib
                  zlib
                ]
              )
            }:$LD_LIBRARY_PATH"

            if [ ! -d .pebble-data/pebble-sdk/SDKs/current ]; then
              pebble sdk install latest
            fi
          '';
        };
      }
    );
}
