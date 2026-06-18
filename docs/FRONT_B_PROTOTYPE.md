# Front B — Prototype : parallélisation du ticking des block entities

> ⚠️ **PROTOTYPE À HAUT RISQUE.** Désactivé par défaut. Ne jamais activer en prod.
> À tester uniquement sur un monde jetable avec sauvegarde. Voir « Garde-fous » dans `CLAUDE.md`.

## Rappel du diagnostic

`tickBlockEntities` = **~70 % du tick** sur « Terres de droite V8 ». C'est LE bottleneck.
Le multithreading d'entités d'Async n'adresse que < 2 % du problème. Ce prototype attaque
le vrai levier : sortir une partie du ticking des machines du thread principal, en réutilisant
la plomberie Async existante (`ForkJoinPool`, suivi de threads, barrière, fallback synchrone).

## Baseline mesurée (2026-06-18, serveur de test, flag OFF)

Profil spark sur le serveur de test (sans joueur, build 0.1.13, Front B désactivé) :
**https://spark.lucko.me/68r6j3cNf0** — TPS 6.5/9.9/14.9, MSPT médian ~115 ms.

- `ServerLevel.tick()` = 74.8 % du thread serveur, dont **`tickBlockEntities()` = 55.7 %**.
- Entités (via Async) = **1.4 %** → confirme que Front B est le bon levier.
- Le mixin est dans le chemin : `ParallelProcessor.callBlockEntityTick` enveloppe 47.5 % du thread.

Coût des BE par mod (% thread serveur) :

| Mod | % |
|---|---|
| Create (`SmartBlockEntityTicker`) | 16.8 |
| GregTech (`IMachineBlock`) | 5.8 |
| EnderIO (`ConduitBlock`) | ~5.2 |
| Mekanism (multiples, fragmenté) | ~8 |
| Mystical Agriculture (`HarvesterBlock`) | 1.5 |

⚠️ **Implication :** le coût est concentré dans les mods que ce prototype dit de garder sur le thread
principal au début (réseaux/voisinage). Une whitelist conservatrice (fours vanilla) validera la
correction mais ne donnera quasi aucun gain TPS ici. Le vrai gain implique de paralléliser Create /
GregTech / Mekanism / EnderIO — soit le terrain à plus haut risque.

## Ce que fait le prototype

Il calque **exactement** le mécanisme entité d'Async :

| Entités (existant)                    | Block entities (Front B)                |
|---------------------------------------|-----------------------------------------|
| `ServerLevelMixin` redirige `EntityTickList.forEach` | `LevelBlockEntityTickMixin` redirige `TickingBlockEntity.tick()` |
| `ParallelProcessor.callEntityTick`    | `ParallelProcessor.callBlockEntityTick` |
| `shouldTickSynchronously(Entity)`     | `shouldTickBESynchronously(String type)`|
| `postEntityTick()` (barrière)         | `postBlockEntityTick()` (barrière)      |
| blacklist runtime par UUID            | blacklist runtime par **type** de BE    |
| `synchronizedEntities` (blacklist)    | `parallelBlockEntities` (**whitelist**) |

### Flux

1. Mixin sur `Level.tickBlockEntities()` :
   - `@Redirect` sur l'unique appel `TickingBlockEntity.tick()` → `callBlockEntityTick(level, bte)`.
   - `@Inject(RETURN)` → `postBlockEntityTick()` qui draine les `CompletableFuture` (en aidant
     le pool via `chunkSource.pollTask()`, comme la barrière entité).
2. `callBlockEntityTick` lit `bte.getType()` (= id du `BlockEntityType`, ex. `create:mechanical_press`).
   - flag off / désactivé / client / type non whitelisté / type blacklisté runtime → **tick synchrone vanilla**.
   - sinon → soumission au `tickPool`, future ajouté à `beTaskQueue`.
3. Si un tick async jette : log + **le type entier passe en synchrone** pour les ticks suivants
   (on err vers la sûreté ; on ne peut pas rejouer un tick déjà à moitié exécuté).

## Configuration

Fichier de config Forge `async-common.toml`, section « Async Config » :

```toml
# Drapeau maître Front B. OFF par défaut. Tant que false => ticking BE 100% vanilla.
enableAsyncBlockEntities = false

# WHITELIST. Vide = rien en async même si le flag est ON (comportement sûr).
#   "modid:type" = un BlockEntityType précis      ("minecraft:furnace")
#   "modid:*"    = tous les BlockEntityType d'un mod ("minecraft:*")
parallelBlockEntities = []
```

`/async stats` affiche désormais l'état Front B et la taille de la whitelist.

## Procédure de test recommandée

1. **Sauvegarder** le monde de test.
2. Activer `enableAsyncBlockEntities = true`, whitelist **vide** → vérifier 0 régression
   (le flag seul ne doit rien changer : tout reste synchrone).
3. Ajouter **une seule** famille isolable, sans interaction de voisinage intra-tick. Bons candidats
   de départ : block entities « consommatrices » pures (four/brûleur-like). **Garder Create / AE2 /
   Mekanism / EnderIO hors whitelist** au début (accès concurrents monde/capabilities/réseaux).
4. `spark profiler` avant/après sur charge comparable. Comparer la part de `tickBlockEntities`.
5. Surveiller les logs : tout `Error in async block entity tick (type ...)` = ce type est désormais
   auto-blacklisté → à retirer de la whitelist et à analyser.
6. Élargir **un mod / un type à la fois**, avec test de non-régression à chaque étape.

## Hazards connus (pourquoi c'est risqué)

- **`pendingBlockEntityTickers` est un `ArrayList` non thread-safe.** Si une BE ajoute une autre
  BE pendant son tick async (placement de bloc), l'ajout concurrent peut corrompre la liste.
  → ne whitelister que des BE qui ne placent pas de blocs/BE pendant leur tick. *Durcissement
  futur possible : rendre `pendingBlockEntityTickers` concurrente via mixin (comme `navigatingMobs`).*
- **`setBlock` / neighbor updates** depuis un thread async = accès concurrent au monde/sections →
  désync ou corruption. Les machines à réseau (fluides/énergie/items) sont les pires cas.
- **Capabilities Forge** souvent non thread-safe.
- **Profiler vanilla** (`getProfiler()`) non thread-safe s'il est *actif* (`/debug`, F3+L).
  En exploitation normale il est `InactiveProfiler` (no-op) — spark échantillonne à part, donc OK.
  Ne pas lancer le profiler vanilla pendant un test Front B.

## Fichiers touchés

- `common/config/AsyncConfig.java` — flag `enableAsyncBE`, whitelist `parallelBlockEntities`,
  caches + `isBlockEntityParallel(String)`.
- `forge/config/AsyncConfigForge.java` — entrées de spec + load/save.
- `common/ParallelProcessor.java` — `callBlockEntityTick`, `shouldTickBESynchronously`,
  `performAsyncBlockEntityTick`, `postBlockEntityTick`, `beTaskQueue`, blacklist runtime, `currentBlockEntities`.
- `common/mixin/world/LevelBlockEntityTickMixin.java` — redirect + barrière.
- `resources/async.common.mixins.json` — enregistrement du mixin.
- `common/commands/StatsCommand.java` — ligne d'état Front B.

## Pistes de durcissement (post-prototype)

- `synchronizedBlockEntities` (blacklist) en complément de la whitelist pour exclure finement.
- Sous-commandes `/async be add|remove|list` pour itérer sans redémarrage.
- Rendre `pendingBlockEntityTickers` concurrente.
- Regrouper les BE par chunk/section et ticker par lots pour réduire l'overhead de soumission.
