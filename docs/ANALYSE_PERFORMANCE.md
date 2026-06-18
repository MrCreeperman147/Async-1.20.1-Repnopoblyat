# Analyse de performance — Terres de droite V8

**Modpack :** Terres de droite V8 · Minecraft 1.20.1 · Forge 47.4.10 · 284 mods
**Source :** profil serveur spark `0OmRx5BopW` (fenêtre ~25 min, intervalle d'échantillonnage 4 ms)
**Mod de threading existant :** Async 0.1.12-alpha (fork Repnopoblyat) — déjà installé sur le serveur profilé

---

## 1. Verdict en une phrase

Le serveur est limité par le **ticking mono-thread des _block entities_ (machines)** — pas par les entités/mobs. Le mod Async (qui parallélise les entités) ne peut donc **pas** corriger ce serveur à lui seul : il adresse < 2 % du problème. Le vrai levier est la charge des machines Create / Mekanism / AE2 / EnderIO / ProjectRed.

> **Confirmé par un 2e profil de 2 min** (`iL6Ul1Z6pv`) : TPS ~15, MSPT médian 62 ms, CPU 8,9 %, `tickBlockEntities` = 69,9 % (identique). Create monte même à ~27 %, dont **~6 % de réseaux de fluides** (`FluidTransportBehaviour` + `PipeConnection.manageFlows` + `FluidNetwork.tick`). À noter : **ProjectRed + CB Multipart (~12 % dans le 1er profil) ont disparu du 2e** → charge **intermittente** dépendant d'un circuit logique actif ou non. Quand il tourne, il ajoute ~12 % d'un coup.

---

## 2. Chiffres clés

| Métrique | Valeur | Lecture |
|---|---|---|
| TPS (1m / 5m / 15m) | 16,0 / 15,0 / 15,0 | Sous l'objectif de 20. Le monde tourne ~25 % trop lentement. |
| MSPT médian | 58,5 ms | Au-dessus du budget de 50 ms/tick. Chaque tick déborde. |
| MSPT 95e pct | 82,3 ms | Pics réguliers à ~12 TPS ressentis. |
| **CPU process** | **~8,5 %** | **Décisif.** Le serveur rame alors que le CPU est quasi inutilisé. |
| RAM process | 5 / 12 Go (42 %) | La mémoire n'est pas le problème. |

**Le point central : 15 TPS avec 8,5 % de CPU.** C'est la signature d'un goulet **mono-thread** : un seul cœur sature pendant que tous les autres dorment. C'est *a priori* le scénario idéal pour du multithreading — la question est de savoir *ce qui* sature ce cœur unique.

---

## 3. Où part le temps — call-tree principal

```
ServerLevel.tick()                                     83,5 %
└─ Level.tickBlockEntities()                           69,9 %   ◄── LE bottleneck
   └─ LevelChunk … BoundTickingBlockEntity.tick()      59,2 %
      ├─ Create  SmartBlockEntityTicker.tick()         21,5 %
      │   └─ FluidTransportBehaviour.tick()             ~5,9 %
      │       └─ PipeConnection.manageSource()          ~3,2 %   (réseaux de fluides Create)
      └─ (Mekanism / AE2 / EnderIO / GregTech / …)      reste
```

**`tickBlockEntities()` = 69,9 % du tick serveur.** Le ticking des entités vivantes (mobs), c'est-à-dire exactement ce que parallélise Async, ne représente qu'une fraction marginale du temps restant.

---

## 4. Coût par mod (vue « Sources » spark)

| # | Mod | % du tick | Nature de la charge |
|---|---|---|---|
| 1 | **create** | **29,7 %** | Réseaux cinétiques, comportements de block entities, pipes fluides |
| 2 | **mekanism** | **19,5 %** | Machines, câbles, traitement de recettes, QIO |
| 3 | **ae2** | **16,6 %** | Tick réseau ME, stockage, énergie |
| 4 | createappliedkinetics | 9,1 % | Block entities (addon Create) |
| 5 | enderio | 8,1 % | Conduits |
| 6 | **cb_multipart** | 6,0 % | Multipart / portes logiques |
| 7 | **projectred_integration** | 5,9 % | Portes logiques |
| 8 | ae2additions | 4,0 % | Réseau ME |
| 9 | gtceu (GregTech) | 3,6 % | Machines |
| — | async (déjà installé) | 1,6 % | overhead du mod lui-même |
| — | **mobs cumulés** (alexscaves 0,43 %, scp 0,22 %, alexsmobs 0,01 %, mowzies 0,01 %, born_in_chaos 0,01 %…) | **< 1 %** | entités vivantes |

> Note : les pourcentages se chevauchent (un mod en appelle un autre), donc ils ne s'additionnent pas à 100 %. Le **classement** est ce qui compte.

**Lecture :** les 9 premiers postes sont tous des mods de **machines / réseaux**. Les mobs — le terrain d'Async — sont en bas de tableau, sous le bruit.

---

## 5. Pourquoi adapter Async ne réglera pas ce serveur

Async parallélise `tickNonPassenger(entity)` — le tick des **entités**. Il ne touche **pas** `tickBlockEntities()`. Or :

- Le tick des entités pèse une fraction minime ici (mobs < 1 %).
- La preuve empirique est dans le profil lui-même : **Async est déjà installé** sur le serveur mesuré, et celui-ci est quand même à 15 TPS.
- De plus, `create:*` est déjà forcé sur le thread principal par Async — ce qui est correct, mais signifie que le gros de Create (qui est de toute façon en block entities, pas en entités) reste mono-thread.

Conclusion : Async reste utile comme **filet** si tu ajoutes plus tard beaucoup de mobs, mais ce n'est **pas** le levier principal pour ce pack.

---

## 6. Plan d'attaque (3 fronts, par ROI/risque croissant)

### Front A — Réduire la charge des machines · gain sûr, risque nul, applicable aujourd'hui

Aucune ligne de code : ce sont des réglages et de l'identification in-game. À faire en premier.

1. **Create — réseaux de fluides (~6 % à eux seuls).** Les `PipeConnection.manageSource` tournent en continu. Pistes :
   - Repérer les grosses pompes/réseaux de pipes qui brassent du fluide en boucle ; remplacer les longs réseaux de pipes par des cuves/tanks tampons.
   - Limiter les contraptions et trains actifs simultanément (Steam'n'Rails / Threaded Trains).
2. **ProjectRed Integration + CB Multipart (~12 % combinés).** Ce sont des **portes logiques** qui tickent en boucle. C'est souvent **un ou deux gros circuits** chez un joueur. → Localiser via spark (vue par chunk) ou en demandant aux joueurs, puis simplifier/supprimer.
3. **AE2 (16,6 %).** Réduire le nombre de devices qui tickent : limiter les sous-réseaux, espacer les intervalles de crafting/export bus, regrouper le stockage. Vérifier les bus d'import/export configurés trop agressivement.
4. **Mekanism / EnderIO / GregTech.** Réduire les machines qui tournent à vide et les longs réseaux de câbles/conduits. Préférer le QIO Mekanism aux longues lignes de logistique.
5. **Profilage ciblé par chunk.** Relancer spark avec `/spark profiler --timeout 120` puis utiliser la vue par chunk pour pointer *les bases* responsables, et agir dessus chirurgicalement.

> Ces actions n'augmentent pas le TPS au-delà de 20, mais elles réduisent directement le MSPT et suppriment les pics.

### Front B — Paralléliser les block entities · le vrai levier, **risque élevé**

C'est le seul axe de *threading* qui adresse réellement les 70 %. C'est aussi le terrain le plus dangereux qui soit avec Create + AE2 + Mekanism (accès concurrents au monde, aux capabilities, aux réseaux → désyncs, corruption de sauvegarde).

- **Pas de solution Forge 1.20.1 fiable clé en main** pour ce pack (MCMT et dérivés sont réputés crash-prone précisément avec ces mods).
- **Bonne nouvelle :** le code Async contient déjà toute la plomberie nécessaire (`ForkJoinPool`, suivi des threads, fallback synchrone, infra mixin, collections concurrentes). C'est une base solide pour **étendre** vers les block entities plutôt que repartir de zéro.
- **Approche prudente recommandée** (cf. README, section roadmap) : ne paralléliser que des familles de block entities *isolables et lisibles seules* (ex. machines purement « consommatrices » sans interaction de voisinage dans le même tick), en gardant Create/AE2/Mekanism sur le thread principal au début, puis élargir mod par mod avec tests de non-régression.

### Front C — Async entités, proprement adapté · complément mineur, déjà fait

Fait dans ce dépôt : version Async alignée Forge 47.4.10 et configurée pour ce pack (cf. §7). Bénéfice attendu faible aujourd'hui (mobs < 1 %), mais utile comme assurance et si la population de mobs augmente.

---

## 7. Ce qui a été fait dans ce dépôt (repnopti)

- Import de la base **Async (fork Repnopoblyat)** comme socle de threading.
- `gradle.properties` : `forge_version` aligné sur **47.4.10** (version exacte du modpack).
- `AsyncConfig.java` : liste `unsupportedMods` **élargie et adaptée au pack** — tous les mods à entités « exotiques » (Create & addons, Immersive Vehicles, TaCZ/armes, boss à IA custom, etc.) sont forcés sur le thread principal. Coût en perf quasi nul ici (mobs < 1 %) et gros gain en stabilité.
- Configuration serveur recommandée fournie : voir `docs/CONFIG_SERVEUR.md`.

---

## 8. Prochaines étapes proposées

1. **Aujourd'hui (Front A) :** relancer un spark `--timeout 120`, ouvrir la vue par chunk, identifier les 2–3 bases responsables des portes logiques ProjectRed et des réseaux de fluides Create, agir dessus. Gain immédiat sur le MSPT, zéro risque.
2. **Court terme (Front C) :** compiler ce dépôt, remplacer la version Async installée par celle-ci, vérifier l'absence de crash sur le pack complet.
3. **Moyen terme (Front B) :** décider si on investit dans un module de parallélisation des block entities. Si oui : prototype sur une seule famille de machines à faible risque, sur monde de test, avec comparaison spark avant/après. À ne lancer qu'avec sauvegardes et environnement de test dédié.

---

## 9. Environnement serveur (panel Pterodactyl + `spark health`)

Confirmations tirées du panel d'hébergement et d'un `spark health` exécuté en direct :

| Élément | Constat | Conséquence |
|---|---|---|
| **Cœurs CPU** | ~48 logiques (CPU panel ~425 % ↔ 8,9 % spark), **aucun cap** (`∞`) | ~44 cœurs **dorment**. Headroom énorme — mais exploitable **seulement** par une parallélisation des block entities (Front B). |
| **Usage CPU process** | 7-8 % | Un seul thread saturé. Goulet mono-thread confirmé une 3e fois. |
| **Heap** | `-Xms12G -Xmx12G`, conteneur 30 Go, **flags Aikar (G1GC)** | Heap à 67 %, GC sain (mémoire plate, pas de dents de scie). **Rien à régler côté JVM/RAM.** |
| **`spark health` TPS** | 15,4 / 15,3 / 15,2 / 15,3 / 15,3 | Surcharge **continue et stable**, pas d'à-coups. |
| **`spark health` tick** | min 56 / med 62 / 95e 87 / **max 164 ms** | **Aucun gel multi-seconde** : le max reste à 164 ms. |
| **Console** « Can't keep up, ~4700 ms / ~94 ticks behind » toutes les ~19 s | = **dérive cumulée** d'un serveur à ~62 ms/tick (≈16 ms de retard/tick → seuil atteint toutes les ~19 s), **pas** un freeze périodique | Symptôme de la surcharge continue déjà diagnostiquée. Pas de cause cachée (ni GC, ni I/O bloquante). |

**Bilan environnement :** la machine est largement dimensionnée (48 cœurs, 30 Go) et bien configurée (Aikar). Le problème n'est ni la RAM, ni le GC, ni l'hébergeur — c'est purement le **tick mono-thread des block entities**. Ajouter des ressources n'aidera pas ; seul le Front A (réduire la charge) ou le Front B (la paralléliser) déplacera l'aiguille.
