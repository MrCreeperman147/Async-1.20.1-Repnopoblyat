# CLAUDE.md — contexte projet pour Claude Code

> Ce fichier est lu automatiquement par Claude Code à l'ouverture du dossier.
> Il résume tout le travail déjà fait en session Cowork et oriente la suite.

## Objectif du projet

Mod de performance **Forge 1.20.1 / 47.4.10** pour le modpack serveur **« Terres de droite V8 »** (284 mods, lourd en Create/Mekanism/AE2/GregTech). Base technique : fork **Async** (multithreading d'entités, package `com.axalotl.async`, modid `async`). But à terme : étendre l'optimisation au-delà des entités.

## Diagnostic (déjà établi — ne pas refaire l'analyse de zéro)

Profilé via spark (2 rapports) + `spark health` + panel Pterodactyl. Conclusions solides :

- Serveur à **~15 TPS** (objectif 20), MSPT médian ~62 ms. **CPU process ~8 %** sur **~48 cœurs** → goulet **mono-thread**, ~44 cœurs inutilisés.
- **`tickBlockEntities` = 70 % du tick.** Le ticking des **block entities (machines)** est LE bottleneck. Les entités/mobs = **< 1 %**.
- Top mods : Create ~29 % (dont **réseaux de fluides ~6 %** : `FluidTransportBehaviour`/`PipeConnection`/`FluidNetwork`), Mekanism ~19 %, AE2 ~12-16 %, EnderIO ~8 %, ProjectRed+CB Multipart ~12 % (intermittent).
- JVM/RAM **sains** (12 Go heap, flags Aikar G1GC, GC propre, tick max 164 ms → pas de freeze). Rien à régler côté machine.

**Conséquence clé :** le multithreading d'**entités** (ce que fait Async) n'adresse que < 2 % du problème ici. Le vrai levier de threading serait la parallélisation des **block entities** (Front B).

Détails complets dans :
- `docs/ANALYSE_PERFORMANCE.md` — analyse, chiffres, call-tree, plan 3 fronts, §9 environnement serveur.
- `docs/FRONT_A_ACTIONS.md` — réglages config exacts (fichier/clé/valeur) tirés des vraies configs du pack.
- `docs/CONFIG_SERVEUR.md` — vue d'ensemble Front A.

## Plan en 3 fronts

- **Front A** (sûr, immédiat, *non-code*) : réduire la charge machines via config + in-game. Principaux leviers identifiés : ChickenChunks (quotas énormes + `allowOffline`), GregTech `enableWorldAccelerators: true`, AE2 `craftingCalculationTimePerTick`, ProjectRed timers, réseaux de fluides Create. → voir `docs/FRONT_A_ACTIONS.md`.
- **Front B** (le vrai levier, **risque élevé**, *code*) : paralléliser le ticking des block entities. C'est le gros chantier de dev. Voir roadmap ci-dessous.
- **Front C** (fait) : Async entités adapté au pack (cf. « État actuel »).

## État actuel du code (ce qui a été modifié)

Base Async importée/présente. Adaptations déjà appliquées pour ce pack :
- `gradle.properties` → `forge_version=47.4.10` (aligné modpack).
- `AsyncConfig.java` → `unsupportedMods` élargie : Create & addons, Immersive Vehicles, TaCZ/armes, boss à IA custom, etc. forcés sur le thread principal (gratuit en perf ici, gros gain stabilité).
- Le reste du moteur Async est inchangé.

## Architecture du code (base Async)

- `com.axalotl.async.common.ParallelProcessor` — **cœur du moteur**. `ForkJoinPool tickPool`, `callEntityTick()`, `shouldTickSynchronously()`, `postEntityTick()` (barrière qui draine les `CompletableFuture` + `pollTask()`), `asyncSpawnForChunk()`, suivi des threads (`isAsyncThread`/`isMainThread`), blacklist runtime.
- `com.axalotl.async.common.config.AsyncConfig` — config : `unsupportedMods`, `synchronizedEntities` (exact + wildcards `modid:*`), caches.
- `com.axalotl.async.common.parallelised.*` — collections concurrentes (fastutil wrappers), utils.
- Mixins : `src/main/resources/async.common.mixins.json` (packages `entity/`, `server/`, `world/`, `spawn/`, `utils/`) et `async.forge.mixins.json`.
- Commandes `/async` : `common/commands/` (config, stats).
- Split `common/` (logique + platform abstraite) et `forge/` (impl Forge).

Stack : Java 17, Mixin 0.8.5 + MixinExtras 0.5.0 + MixinSquared 0.3.7-beta.2, Parchment mappings.

## Commandes

```bash
./gradlew build            # compile + jar dans build/libs/
./gradlew runServer        # serveur de dev (test local)
./gradlew genIntellijRuns  # configs de run IntelliJ
```

## Roadmap Front B — parallélisation des block entities (à concevoir avec prudence)

Objectif : déplacer une partie du travail de `Level.tickBlockEntities()` hors du thread principal, en réutilisant la plomberie existante (`ForkJoinPool`, suivi de threads, fallback synchrone, collections concurrentes).

Approche incrémentale recommandée :
1. **Mixin sur `Level.tickBlockEntities()`** (ou le wrapper `LevelChunk$BoundTickingBlockEntity`) pour router certaines block entities vers `tickPool`, calqué sur `callEntityTick`.
2. **Whitelist ultra-restrictive au départ** : ne paralléliser qu'une famille isolable et lisible seule (ex. machines « consommatrices » sans interaction de voisinage intra-tick). **Garder Create / AE2 / Mekanism / EnderIO sur le thread principal au début** (accès concurrents monde/capabilities/réseaux = corruption).
3. Réutiliser `synchronizedEntities`-style → créer un équivalent `synchronizedBlockEntities` (blacklist par classe/modid).
4. **Mesurer spark avant/après** sur monde de test, avec sauvegardes. Élargir mod par mod seulement après tests de non-régression.

⚠️ Très risqué (désyncs, corruption de save). Jamais en prod sans environnement de test dédié.

## Garde-fous

- **Serveur de prod** (`37.187.27.13`) : lecture/diagnostic seulement. Pas de modif sans accord explicite de l'utilisateur.
- Toujours sauvegarder le monde avant de tester un build.
- Repo git distant : `gitlab.com/repno-crew/repnopti`.
