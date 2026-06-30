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
        pkgs = nixpkgs.legacyPackages.${system};

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
          ];

          shellHook = ''
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
