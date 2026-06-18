# Front A — Actions concrètes de réduction de charge

Réglages **issus des configs réelles du modpack** (Terres de droite V8), ciblés sur le bottleneck mesuré : `tickBlockEntities` = 70 % du tick. Aucun risque de crash ; ce sont des choix de réglage/gameplay.

> ⚠️ Sauvegarde le serveur avant. Applique **un changement à la fois**, puis relance un `/spark profiler --timeout 120` pour mesurer l'effet. Sinon impossible de savoir ce qui a aidé.

Les chemins sont relatifs au dossier `config/` du serveur.

---

## Priorité 1 — Chunks force-loadés · `ChickenChunks.cfg`

**Le levier systémique le plus puissant.** Chaque chunk force-loadé fait ticker ses machines **en permanence**, même sans joueur à proximité. Réglage actuel = très permissif :

| Clé | Actuel | Recommandé | Effet |
|---|---|---|---|
| `allowOffline` | `true` | `false` | Les bases ne tickent plus quand le propriétaire est **hors ligne**. Énorme gain de MSPT en heures creuses. |
| `offlineTimeout` | `0` | `5` | (si `allowOffline=false`) délai en minutes avant déchargement après déconnexion. |
| `totalAllowedChunks` | `5000` | `256` | 5000 chunks/joueur force-loadés est démesuré. 256 reste très confortable. |
| `chunksPerLoader` | `400` | `64` | Idem par loader. |

**Tradeoff :** avec `allowOffline=false`, les fermes/automatisations censées tourner 24/7 s'arrêtent quand personne n'est connecté. C'est une **décision de politique serveur**. Si tu veux garder l'automatisation continue, garde `allowOffline=true` mais **baisse quand même** `totalAllowedChunks`/`chunksPerLoader` — c'est ce qui plafonne la casse.

---

## Priorité 2 — GregTech World Accelerators · `gtceu.yaml`

```yaml
enableWorldAccelerators: true   # ← actuel
```

Les World Accelerators **accélèrent le tick des block entities voisines** (machines Create/Mekanism/AE2, cultures…). Sur un serveur déjà saturé à 70 % par les block entities, ils **multiplient directement** le coût du hotspot.

- **Option franche :** `enableWorldAccelerators: false`.
- **Option intermédiaire :** garder activé mais remplir `worldAcceleratorBlacklist` avec les block entities les plus lourdes pour empêcher leur accélération.

**Tradeoff :** c'est une fonctionnalité de gameplay (overclock de machines). Préviens les joueurs avant de la couper.

---

## Priorité 3 — AE2 temps de calcul de crafting · `ae2/common.json`

```json
"craftingCPU": { "craftingCalculationTimePerTick": 5 }   // ← actuel
```

AE2 s'autorise jusqu'à **5 ms par tick** pour calculer les jobs d'auto-crafting. Sur un MSPT déjà à 62 ms, un gros calcul d'autocraft ajoute des pics. Passer à **2** ou **3** lisse ces pics.

- `craftingSimulatedExtraction` est déjà à `false` (bon réglage perf) — **ne pas** le passer à `true`.
- `channels` reste `"default"` : garder, ça force les joueurs à des réseaux propres. Le passer à `"infinite"` empirerait la charge.

**Tradeoff :** les très gros calculs d'autocraft prennent un peu plus longtemps à démarrer. Sans impact sur la vitesse de craft elle-même.

---

## Priorité 4 — ProjectRed, brider les horloges logiques · `ProjectRed.cfg`

C'est la charge **intermittente** (~12 % dans le 1er profil, absente du 2e) : un circuit de portes logiques actif par moments.

```
[general]
I:"gate_min_timer_ticks"=4    # ← actuel (5 pulses/seconde possibles)
```

Monter à `10` (ou `20`) **plafonne la fréquence** des timers/horloges, qui sont la principale source de spam de portes. `auto_compile_tile_limit=20` (Fabrication) est déjà bas — bon.

**Tradeoff :** les circuits qui dépendent d'horloges très rapides changeront de timing. Acceptable dans la majorité des cas.

---

## Priorité 5 — Create, réseaux de fluides (in-game, pas de config)

Le hotspot **permanent** confirmé sur les deux profils (`FluidTransportBehaviour` + `PipeConnection.manageFlows` + `FluidNetwork.tick` ≈ **6 %**). Create n'expose **pas** de réglage de tick de fluide → ça se règle **en jeu** :

- Raccourcir les longs réseaux de pipes ; insérer des **cuves/tanks tampons** pour casser les longues chaînes (un réseau de fluide coûte proportionnellement à sa taille et à son activité).
- Supprimer les pompes qui brassent un fluide en **circuit fermé** sans utilité.
- Limiter le nombre de **contraptions et trains actifs** simultanément.

Pour localiser les bases coupables : `/spark profiler --timeout 120`, puis chercher dans l'arbre les chunks où `SmartBlockEntityTicker.tick` domine.

---

## Vérifications — optimisations déjà présentes (ne rien casser)

Le pack inclut déjà de bonnes optims serveur, **bien réglées** — à laisser activées :

- `leaky.json` → `improveItemPerformance: true`, auto-remove des items empilés à 160. ✅
- `connectivity.json`, `cupboard.json`, `smoothchunk.json`, `memorysettings.json`, `chunksending.json`, `betterchunkloading.json` → présents et actifs. ✅
- `aiimprovements-common.toml` → `replace_look_controller: true`. ✅ (peu d'effet ici, mobs < 1 %, mais sans coût.)

---

## Ce qui n'aidera PAS (rappel)

- Ajouter de la RAM : mémoire à ~55 % (6,6/12 Go), non saturée.
- Mods FPS/client (Embeddium, Oculus) : le problème est **serveur/TPS**.
- Async seul : parallélise les entités, pas les block entities.

---

## Ordre d'exécution conseillé

1. `ChickenChunks` (baisser les quotas + décider de `allowOffline`) → re-profiler.
2. `gtceu` World Accelerators → re-profiler.
3. `ae2` craftingCalculationTimePerTick = 2 → re-profiler.
4. Quand l'horloge ProjectRed est active : profiler, localiser la base, et/ou monter `gate_min_timer_ticks`.
5. En jeu : casser les gros réseaux de fluides Create avec des tampons.

Chaque étape isolée + mesurée = tu sais exactement ce qui paie.
