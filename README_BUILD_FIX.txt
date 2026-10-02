Vialumix 1.20.1 - v16

Correction v15 -> v16:
- VideoOptionsScreen constructor for Minecraft 1.20.1 uses (Screen parent, GameOptions options).
- The chooser now opens the original VideoOptionsScreen with the correct argument order.
- The isolated .minecraft instance remains inside this project and is not installed into the user's real .minecraft.

Build:
- Run RUN_AFTER_BUILD.bat
- The resulting Vialumix JAR is copied to this project's .minecraft/mods/
