Purpose

This file documents repository-specific build/test/lint commands, the high-level architecture, and conventions that are not obvious from single-file reads. Keep it short and factual — intended for Copilot and AI sessions that will modify or add code in this repo.

1) Build, test, and lint commands

- Full build (exact command used by CI):
  - Windows: gradlew.bat clean processIncludeJars build --stacktrace
  - Unix/macOS: ./gradlew clean processIncludeJars build --stacktrace
- Make wrapper executable on *nix: chmod +x ./gradlew
- Build artifact only: ./gradlew assemble
- Run a development server (Forge run configuration):
  - ./gradlew runServer     (server dev run; server run configuration already passes --nogui by default)
  - To explicitly run headless: ./gradlew runServer --args='--nogui'
  - ./gradlew runClient     (client dev run)
- Run a single Gradle test:
  - Unix: ./gradlew test --tests "com.axalotl.async.YourTestClass"
  - Windows: gradlew.bat test --tests "com.axalotl.async.YourTestClass"
  - Run a single test method (Unix): ./gradlew test --tests "com.axalotl.async.YourTestClass.yourMethod"
  - Note: There are currently no test sources in the repository; Gradle test is a no-op unless tests are added.
- Linting / static analysis:
  - No dedicated linter/checkstyle/spotless tasks are configured in the project. Use the Gradle build (./gradlew check) if you add standard checks.

CI notes
- GitHub Actions workflow at .github/workflows/build.yml uses JDK 22 and runs: ./gradlew clean processIncludeJars build
- CI installs libtinfo5 on the runner and caches Gradle artifacts. Artifacts copied in CI come from neoforge/build/libs and fabric/build/libs in that workflow.

Environment & Java
- The Gradle toolchain is configured from gradle.properties (java_version). Current repo sets java_version=17.
- CI uses JDK 22 in GitHub Actions (see .github/workflows/build.yml). Both values matter: Gradle toolchain's java_version governs compilation/runtime compatibility declared by the project, while the CI runner's JDK (22) is the runtime used in the workflow.
- For local development either match your IDE JDK to gradle.properties (17) or rely on Gradle toolchains to provision a compatible JDK.

2) High-level architecture (big picture)

- Project type: Single Gradle-based Minecraft Forge mod (targeting Minecraft 1.20.1, Parchment mappings) using Sponge Mixin.
- Broad layers:
  - com.axalotl.async.common — Core platform-agnostic logic: mixins, ParallelProcessor (thread pool + async tick/spawn logic), AsyncConfig (runtime config and caches), commands and shared utils.
  - com.axalotl.async.forge — Forge-specific glue: AsyncForge (mod entry point), ForgePermissions, AsyncConfigForge (platform config binding), event listeners and command registration.
  - src/main/resources — mod metadata (mods.toml, pack.mcmeta), mixin configs: async.common.mixins.json and async.forge.mixins.json, and access transformer file.
- Threading model: ParallelProcessor creates a ForkJoinPool to offload entity ticking and spawning. It tracks async threads, blacklisted entities, task queues, and cooperates with mixins which redirect vanilla calls into ParallelProcessor.
- Config and runtime toggles: AsyncConfig exposes Map.Entry-backed defaults and volatile boolean flags that mixins read directly; platform-specific config loaders (e.g., AsyncConfigForge) populate these and call AsyncConfig.onConfigLoaded().

Resource expansion
- processResources expands properties (mod id, version, java_version, etc.) into pack.mcmeta, META-INF/mods.toml, mixins JSON files (async.common.mixins.json, async.forge.mixins.json) and the refmap (async.refmap.json). When adding mixins or changing mod_id, ensure matching entries are present and processResources continues to expand them.

3) Key conventions and gotchas (repo-specific patterns)

- Platform abstraction:
  - Use ModPlatform / PlatformUtils for platform-specific behavior (saveConfig, reloadConfig, isModLoaded, platformUsesRefmap). Do not bypass these abstractions for platform-specific operations.
- Config shape and mixin visibility:
  - AsyncConfig exposes Map.Entry fields and volatile primitives (isDisabled, isAsyncSpawnEnabled, isAsyncRandomTicksEnabled). Mixins read these volatile fields directly — renaming, changing types, or removing volatility will break runtime visibility across threads.
  - Call AsyncConfig.onConfigLoaded() after platform config loads to rebuild caches and update volatile flags.
- Where config lives and how to change it:
  - Defaults and sync logic are defined in src/main/java/com/axalotl/async/common/config/AsyncConfig.java. Platform binding is in src/main/java/com/axalotl/async/forge/config/AsyncConfigForge.java (loadConfig/SPEC).
- Synchronized entities:
  - Synchronized entities support namespace wildcards: "modid:*". Use AsyncCommand (/async config synchronizedEntities add/remove) or modify synchronizedEntities in the config then call rebuildCaches/saveConfig as implemented.
  - AsyncConfig caches entity sync checks — call AsyncConfig.clearCaches() or rebuildCaches() when changing synchronizedEntities programmatically.
- Mixins:
  - Two mixin configs control injections: async.common.mixins.json (core) and async.forge.mixins.json (forge-specific). When adding mixins, register them in the appropriate JSON and ensure processResources expands properties (build.gradle).
  - Keep mixin changes minimal and prefer adding platform-specific mixins into the forge package only. Mixins are a fragile touchpoint — read the mixin config and injection targets carefully.
- Thread pool and classloader:
  - ParallelProcessor.setupThreadPool(getParallelism(), this.getClass()) sets thread context classloader to the mod classloader. When creating new threads or executors, preserve that pattern for classloading correctness.
- Error handling and fallback:
  - When async ticks throw uncaught exceptions, entities are blacklisted and the code falls back to synchronous ticking. Expect blacklistedEntity usage and Portal tick sync behavior (portalTickSyncMap).
- Run-time flags and commands:
  - Runtime toggles are exposed via AsyncCommand and persisted via PlatformUtils.saveConfig(). Useful commands: /async config toggle, /async config reload, /async config setAsyncEntitySpawn, /async config setAsyncRandomTicks, /async stats.

Files and locations to inspect first (for new work)
- build.gradle, gradle.properties, settings.gradle
- src/main/java/com/axalotl/async/common/ParallelProcessor.java
- src/main/java/com/axalotl/async/common/config/AsyncConfig.java
- src/main/java/com/axalotl/async/common/AsyncCommon.java
- src/main/java/com/axalotl/async/forge/AsyncForge.java
- src/main/resources/async.common.mixins.json and async.forge.mixins.json
- .github/workflows/build.yml (CI build steps)

Other AI/assistant configs
- No CLAUDE.md, .cursorrules, AGENTS.md, .windsurfrules, CONVENTIONS.md, .clinerules, or similar AI assistant config files were found in the repository root. Use README.md + CI + the files listed above as primary sources.

Notes for Copilot sessions
- Prefer small, surgical edits. Mixins and threading code are fragile; changes should be verified in a dev run (./gradlew runServer) before merge.
- When adding config keys read by mixins, follow the AsyncConfig pattern (Map.Entry + volatile fields + rebuildCaches()/onConfigLoaded()).
- When touching threading code, ensure ParallelProcessor thread naming and classloader-preservation are respected.

---

If anything important is missing for your workflows (examples: recommended Gradle tasks, curated run configurations, or known mod interactions to call out), say which area to expand and this file will be updated.
