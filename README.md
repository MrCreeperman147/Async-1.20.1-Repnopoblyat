<div align="center">

# Async 1.20.1 - Minecraft Entity Multi-Threading Mod ⚙️

</div>

**Async** is a Forge mod for Minecraft 1.20.1 that improves entity performance by processing them in parallel across multiple CPU cores and threads.

## ⚠️ Important

**Async** is currently in alpha and is experimental. It may cause incorrect entity behavior or crashes. Always back up your world before installing.
**This fork is modified for personnal use.**

---

## 💡 Key Benefits

- ⚡ **Improved TPS** — Maintains stable tick times even with large numbers of entities.
- 🚀 **Multithreading** — Distributes entity ticking across all available CPU cores using a `ForkJoinPool`.
- 🔒 **Safe fallback** — Entities that are incompatible with async processing are automatically kept on the main thread. Known incompatible mods (configurable via `unsupportedMods`) have their entire namespace synchronized on startup.
- 🎲 **Async Random Ticks** *(Experimental)* — Processes chunk random ticks asynchronously for additional performance gains.

---

## 📊 Performance Comparison (9000 Villagers)

> ⚠️ Benchmark run on 1.21.4 — results on 1.20.1 may differ.

| Configuration               | TPS  | MSPT   |
|-----------------------------|------|--------|
| **Lithium + Async**         | 20   | 41.8   |
| **Lithium (without Async)** | 4.4  | 225.4  |
| **Purpur**                  | 5.72 | 176.18 |

<details>
<summary>Test configuration</summary>

- **Processor**: AMD Ryzen 9 7950X3D
- **RAM**: 64 GB (16 GB allocated to the server)
- **Minecraft Version**: 1.21.4
- **Entities**: 9000 Villagers
- **Mods**: Concurrent Chunk Management Engine, Fabric API, FerriteCore, Lithium, ScalableLux, ServerCore, StackDeobfuscator, TT20, Tectonic, Very Many Players, Fabric Carpet

</details>

---

## ⚠️ Incompatible Mods (1.20.1)

Entities from incompatible mods can be forced onto the main thread via the `synchronizedEntities` config or the `/async config synchronizedEntities add` command. Entire mod namespaces can be synchronized with the `modid:*` wildcard.

The `unsupportedMods` list in `async.toml` auto-synchronizes a mod's full namespace on startup if the mod is detected. It defaults to `["create", "fowlplay"]`.

*Found an incompatible mod? Please report it on [this fork's GitHub](https://github.com/MrCreeperman147/Async-1.20.1-Repnopoblyat/issues), not on the upstream tracker.*

---

## 🔧 Commands

All commands require operator level 4 unless otherwise noted.

**Config**
- `/async config toggle` — Enable or disable Async at runtime (no restart needed).
- `/async config reload` — Reload `async.toml` from disk without restarting.
- `/async config setAsyncEntitySpawn <true|false>` — Enable or disable parallel mob spawn processing. **Not compatible with Carpet mod's `lagFreeSpawning` rule.**
- `/async config setAsyncRandomTicks <true|false>` — Enable or disable async random tick processing *(experimental)*.
- `/async config synchronizedEntities` — List all currently synchronized entities.
- `/async config synchronizedEntities add <entity|namespace:*>` — Force an entity type or an entire mod namespace onto the main thread.
- `/async config synchronizedEntities remove <entity|namespace:*>` — Remove an entity type or namespace from the synchronized list.

**Stats** *(available to all players)*
- `/async stats` — Show current status: enabled state, MSPT, thread count, async entity ratio.
- `/async stats entity` — Show per-dimension entity counts (sync vs async).
- `/async stats entity <n>` — Show the top `n` entity types by count, with their sync/async status.

---

## 📥 Download

Available at [Releases](https://github.com/MrCreeperman147/Async-1.20.1-Repnopoblyat/releases).

---

## 📭 Feedback

Use this fork's [issue tracker](https://github.com/MrCreeperman147/Async-1.20.1-Repnopoblyat/issues) for bugs specific to 1.20.1.


---

## 🙌 Acknowledgements
Forked from [Async-1.20.1](https://github.com/Bliss-tbh/Async-1.20.1), based on [MCMTFabric](https://modrinth.com/mod/mcmtfabric), itself based on [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT). Thanks to Grider, jediminer543, and all contributors to the upstream Async project.
