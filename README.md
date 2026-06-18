# Repnopti — optimisation serveur pour « Terres de droite V8 »

Mod de performance Forge **1.20.1 / 47.4.10** pour le modpack *Terres de droite V8* (284 mods).
Socle technique : base **Async** (multithreading d'entités, fork Repnopoblyat) adaptée à ce pack, point de départ vers une optimisation plus large du tick serveur.

## TL;DR — résultat de l'analyse spark

Le serveur tourne à **~15 TPS** (au lieu de 20) avec un **CPU à ~8,5 %** : goulet **mono-thread** caractérisé.
Mais le temps part à **70 % dans le ticking des _block entities_** (machines), pas dans les entités/mobs (< 1 %).

➡️ Conséquence : le multithreading d'entités (Async) n'est **pas** le levier principal ici. Voir l'analyse complète :

- **[`docs/ANALYSE_PERFORMANCE.md`](docs/ANALYSE_PERFORMANCE.md)** — diagnostic complet, chiffres, plan en 3 fronts.
- **[`docs/FRONT_A_ACTIONS.md`](docs/FRONT_A_ACTIONS.md)** — ⭐ actions concrètes (fichier/clé/valeur exacts) tirées des vraies configs du pack. À appliquer en premier.
- **[`docs/CONFIG_SERVEUR.md`](docs/CONFIG_SERVEUR.md)** — vue d'ensemble plus légère du Front A.

## Plan en 3 fronts (résumé)

| Front | Cible | Gain | Risque | État |
|---|---|---|---|---|
| **A** | Réduire la charge machines (config, in-game) | Sûr et immédiat | Nul | Documenté → à appliquer |
| **B** | Paralléliser les block entities | Le vrai levier (70 %) | **Élevé** | Roadmap ci-dessous |
| **C** | Async entités, adapté au pack | Mineur (mobs < 1 %) | Faible | **Fait** |

## Ce qui a déjà été adapté (Front C)

- `gradle.properties` → `forge_version=47.4.10` (aligné sur le modpack).
- `AsyncConfig.java` → liste `unsupportedMods` élargie : tous les mods à entités complexes (Create & addons, Immersive Vehicles, TaCZ/armes, boss à IA custom…) forcés sur le thread principal. Quasi gratuit en perf ici, gros gain de stabilité.

> Le reste du code Async (moteur `ForkJoinPool`, mixins, commandes `/async`) est conservé tel quel depuis la base existante.

## Roadmap — Front B (parallélisation block entities)

Le code Async fournit déjà la plomberie (`ForkJoinPool`, suivi de threads, fallback synchrone, infra mixin, collections concurrentes). L'extension prudente consisterait à :

1. Prototyper sur **une seule famille** de block entities à faible risque (machines « consommatrices » sans interaction de voisinage intra-tick), Create/AE2/Mekanism restant sur le thread principal.
2. Mesurer spark avant/après sur **monde de test** avec sauvegardes.
3. Élargir mod par mod uniquement après tests de non-régression.

⚠️ La parallélisation des block entities avec Create + AE2 + Mekanism est intrinsèquement risquée (accès concurrents monde/capabilities/réseaux). À ne jamais déployer en prod sans environnement de test dédié.

## Build

```bash
./gradlew build      # jar dans build/libs/
```

Projet Forge 1.20.1, Java 17, Mixin 0.8.5 + MixinExtras + MixinSquared.

## Crédits

Socle Async : Axalotl, Alchemy, Bliss, FurryMileon, Grider, jediminer543, MrCreeperman147 — basé sur MCMTFabric / JMT-MCMT.
Adaptation modpack & analyse de performance : projet Repnopti. Licence : CC0-1.0 (voir `LICENSE`).
