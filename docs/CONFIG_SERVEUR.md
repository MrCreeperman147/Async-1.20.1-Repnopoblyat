# Réglages serveur recommandés — Terres de droite V8

Actions **sans code, sans risque**, à appliquer en premier (Front A de l'analyse).
Objectif : réduire le MSPT (actuellement ~58 ms médian, pics 82 ms) en baissant la charge de `tickBlockEntities` (70 % du tick).

> Ordre conseillé : 1) re-profiler par chunk → 2) localiser les coupables → 3) régler. Ne pas régler à l'aveugle.

---

## 0. Re-profiler pour localiser (5 min, indispensable)

```
/spark profiler --timeout 120
```

Ouvrir le rapport, onglet **« Sources »** puis surtout la vue **par chunk** : elle pointe *les bases physiques* responsables. C'est ce qui transforme « Create coûte 30 % » en « la base de tel joueur coûte 30 % ». On agit ensuite chirurgicalement.

---

## 1. ProjectRed Integration + CB Multipart (~12 % du tick)

Ce sont des **portes logiques** qui tickent en boucle — typiquement 1–2 gros circuits.

- Identifier le(s) chunk(s) via spark, puis demander au(x) joueur(s) de simplifier les circuits logiques massifs (compteurs, horloges rapides, matrices de portes).
- Les horloges qui pulsent chaque tick sont les pires : remplacer par des temporisateurs plus lents quand c'est possible.
- C'est le **meilleur rapport gain/effort** du pack : ~12 % concentrés sur peu de blocs.

## 2. Create — réseaux de fluides (~6 %)

`FluidTransportBehaviour` / `PipeConnection.manageSource` tournent en continu sur chaque pipe.

- Raccourcir les longs réseaux de pipes ; insérer des **cuves tampons** pour casser les chaînes.
- Éviter les pompes qui brassent un fluide en circuit fermé sans raison.
- Limiter le nombre de **contraptions et trains actifs** simultanément.

## 3. AE2 (16,6 %)

- Réduire le nombre de bus d'import/export et **espacer leurs intervalles** (mode lent quand le débit n'a pas besoin d'être instantané).
- Regrouper les sous-réseaux ; chaque device qui ticke coûte.
- Surveiller les boucles d'auto-crafting laissées actives.

## 4. Mekanism / EnderIO / GregTech

- Couper/retirer les machines qui tournent **à vide**.
- Raccourcir les longs réseaux de câbles et conduits.
- Mekanism : privilégier le **QIO** aux longues lignes logistiques.

## 5. Mods utilitaires « someaddon » déjà présents — à vérifier

Le pack inclut déjà beaucoup d'optimisations serveur de `someaddon` (Connectivity, Cupboard, Leaky, Chunk Sending, Better Chunk Loading, Smooth Chunk Save, Fast Async World Save, Memory Settings, Recipe Essentials). Vérifier qu'elles sont **activées** et bien réglées — elles n'apparaissent quasiment pas dans le profil, donc elles font déjà leur travail, mais valider qu'aucune n'est désactivée.

## 6. Allocation mémoire

RAM à 42 % (5/12 Go) : **pas besoin d'en ajouter**. Inutile de monter le `-Xmx`, ça n'aidera pas le TPS (le goulet est CPU mono-thread, pas la mémoire). Garder des flags GC sains (Aikar's flags).

---

## Ce qui NE servira à rien ici

- Ajouter de la RAM (mémoire non saturée).
- Mods d'optimisation **client/FPS** (Embeddium, Oculus…) — le problème est **serveur/TPS**, pas le rendu.
- Compter sur Async seul pour le TPS (parallélise les entités, pas les machines).
- Augmenter le nombre de cœurs **sans** parallélisation des block entities : le tick reste mono-thread.
