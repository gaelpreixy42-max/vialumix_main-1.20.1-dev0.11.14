Vialumix v28

Correction principale:
CMake utilisait le generateur NMake Makefiles avec -A x64, combinaison
invalide. Le build natif essaie maintenant explicitement:
  Visual Studio 17 2022 + x64
puis:
  Visual Studio 16 2019 + x64

Si aucun des deux n'est disponible, le script demande l'installation
de "Desktop development with C++" via Visual Studio Installer.

Le chemin du projet reste relatif au script et ne depend pas du nom
dev0.xx du dossier principal.

Objectif:
  <racine>\.minecraft\vialumix\vialumix.dll
