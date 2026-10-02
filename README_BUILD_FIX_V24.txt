V24 - correction du script de build

Le probleme de V23 venait de tools\BUILD_AND_INSTALL.bat : il changeait le dossier courant vers tools\ puis cherchait gradlew.bat et .minecraft au mauvais endroit.

V24 definit ROOT=.. puis travaille depuis la racine du projet. Le JAR est installe dans .minecraft\mods et les runtimes dans .minecraft\vialumix. Les anciennes versions vialumix-*.jar sont supprimees avant installation.
