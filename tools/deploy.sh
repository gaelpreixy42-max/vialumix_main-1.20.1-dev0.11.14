#!/usr/bin/env bash
# Builds the mod and installs it (replacing the previous jar) in the project's .minecraft/mods and in the
# GDLauncher instance. Also installs native/build-x64/Release/vialumix.dll when present.
set -e
export JAVA_HOME="C:/Program Files/Java/jdk-17"
cd "$(dirname "$0")/.."
INSTANCE="/c/Users/gael/AppData/Roaming/gdlauncher_carbon/data/instances/Vialumix - Fabric 1.20.1/instance"
JAR=build/libs/vialumix-0.1.0-alpha-v20-remap.jar
./gradlew remapJar --console=plain -q || ./gradlew remapJar --console=plain -q
for dir in ".minecraft/mods" "$INSTANCE/mods"; do
  if ! rm -f "$dir"/vialumix-*.jar 2>/dev/null; then echo "WARNING: $dir is locked (close Minecraft, then re-run tools/deploy.sh)"; continue; fi
  cp "$JAR" "$dir/"
  echo "jar -> $dir"
done
DLL=native/build-x64/Release/vialumix.dll
if [ -f "$DLL" ]; then
  for dir in ".minecraft/vialumix" "$INSTANCE/vialumix"; do
    cp -f "$DLL" "$dir/vialumix.dll" && echo "dll -> $dir" || echo "WARNING: could not copy dll to $dir (game running?)"
  done
fi
