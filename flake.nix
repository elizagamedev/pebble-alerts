{
  description = "Pebble Calendar Alerts dev environment";

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
          platformVersions = [
            # TODO: Do we need both of these?
            "36"
            "24"
          ];
          buildToolsVersions = [ "35.0.0" ];
        };

        pebble-wrapper = pkgs.writeShellScriptBin "pebble" ''
          exec ${pkgs.uv}/bin/uv run --with pebble-tool pebble "$@"
        '';
      in
      {
        devShells.default = pkgs.mkShellNoCC {
          buildInputs = with pkgs; [
            android-studio
            androidComposition.androidsdk
            jdk17
            just
            kotlin-language-server
            ktlint
            llvmPackages.clang-unwrapped
            nodejs
            pebble-wrapper
            python3
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

            # TODO: make this respect XDG_DATA_HOME
            if [ ! -d ~/.local/share/pebble-sdk/SDKs/current ]; then
              pebble sdk install latest
            fi
          '';
        };
      }
    );
}
