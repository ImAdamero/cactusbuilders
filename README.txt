Cactus Builder 1.6.1 (Fabric, Minecraft 1.21.11, mapowania Yarn)

Budowa (potrzebna Java 21 + internet):
  Linux/Mac:  ./gradlew build
  Windows:    gradlew.bat build
  Wynik: build/libs/cactusbuilder-1.6.1.jar  (NIE ten z -sources)
  Wrzuc jar do .minecraft/mods razem z Fabric API (1.21.11).

Jesli Gradle zglosi blad wersji (loom / fabric api / loader), wygeneruj szablon na
https://fabricmc.net/develop/template/ (1.21.11, Yarn) i przepisz wersje do gradle.properties.

Klawisze: G = start / pauza, H = reset sesji, J = pokaz / ukryj HUD (zmienisz w Opcje > Sterowanie).
