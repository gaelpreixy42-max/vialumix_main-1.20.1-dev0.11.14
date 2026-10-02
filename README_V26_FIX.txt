Vialumix v26 - correction du chemin du projet

IMPORTANT:
tools/BUILD_AND_INSTALL.bat se trouve dans le dossier tools/.
La racine du projet est donc son dossier parent, pas tools\.

La v26 utilise automatiquement le dossier parent du script.
Le nom du dossier principal (dev0.11.14, dev0.11.15, etc.) peut donc changer
sans casser les chemins.

Le JAR est installé dans:
<racine>\.minecraft\mods\

Le backend natif est installé dans:
<racine>\.minecraft\vialumix\vialumix.dll

Les anciens vialumix-*.jar sont supprimés avant l'installation du nouveau.
