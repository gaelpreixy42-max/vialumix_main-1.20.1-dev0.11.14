VIALUMIX 1.20.1 - V18

Corrections depuis V17:
- Le stub etait compile en Java 21 (class major 65) alors que le projet cible Java 17 (major 61).
- Le stub contenait aussi une fausse classe Minecraft Screen, ce qui polluait le classpath et provoquait la cascade des 66 erreurs.
- L'API reelle de Mod Menu 7.2.2 est maintenant utilisee uniquement en compileOnly via le depot Maven Modrinth.
- Mod Menu reste optionnel au runtime.
- Sodium 0.5.13 et Iris 1.7.6 restent des JAR locaux comme dans la version qui compilait.
- Le mixin SodiumOptionsGUI reste le point d'interception du bouton Graphismes.

IMPORTANT:
Le build doit disposer d'un acces Internet pour recuperer l'API Mod Menu 7.2.2 si elle n'est pas deja dans le cache Gradle.
Mod Menu lui-meme n'est pas embarque dans Vialumix et reste un mod optionnel a placer dans .minecraft/mods pour le test.
