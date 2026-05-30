package com.axalotl.async.common;

import com.axalotl.async.common.config.AsyncConfig;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ParallelProcessor {
    public static final Logger LOGGER = LogManager.getLogger(ParallelProcessor.class);

    @Getter
    @Setter
    private static MinecraftServer server;

    public static AtomicInteger currentEntities = new AtomicInteger();
    private static final AtomicInteger threadPoolID = new AtomicInteger();
    public static ForkJoinPool tickPool;
    public static final ConcurrentLinkedQueue<CompletableFuture<?>> taskQueue = new ConcurrentLinkedQueue<>();
    // Safety limit to avoid unbounded growth of pending async tasks under extreme load
    public static final int MAX_PENDING_TASKS = 10000;
    private static final Set<UUID> blacklistedEntity = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> portalTickSyncMap = new ConcurrentHashMap<>();
    private static final Map<String, Set<WeakReference<Thread>>> mcThreadTracker = new ConcurrentHashMap<>();

    private static volatile boolean isShuttingDown = false;

    // ========== PARALLEL SPAWN SYSTEM ==========

    private static final Object ENTITY_ADD_LOCK = new Object();
    private static final ConcurrentLinkedQueue<CompletableFuture<Void>> spawnQueue = new ConcurrentLinkedQueue<>();

    /**
     * Some entities have ticking code that is not thread safe, or have weird interactions with the world that can cause
     * concurrency issues. For now, we'll just blacklist those entities from being ticked asynchronously. In the future,
     * we may want to look into making some of these entities work with async ticking, but for now this is a good start.
     */
    /*
    public static final Set<Class<?>> BLOCKED_ENTITIES = Set.of(
            FallingBlockEntity.class,
            Shulker.class,
            Boat.class
    );
    */
    public static void setupThreadPool(int parallelism, Class<?> asyncClass) {
        isShuttingDown = false;
        mcThreadTracker.remove("Async-Tick"); // clear any old references to avoid memory leaks and false positives in isAsyncThread() after reloads

        ForkJoinPool.ForkJoinWorkerThreadFactory tickThreadFactory = pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("Async-Tick-Pool-Thread-" + threadPoolID.getAndIncrement());
            registerThread("Async-Tick", worker);
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY);
            worker.setContextClassLoader(asyncClass.getClassLoader());
            return worker;
        };

        tickPool = new ForkJoinPool(parallelism, tickThreadFactory, (t, e) ->
                LOGGER.error("Uncaught exception in thread {}: {}", t.getName(), e), true);
        LOGGER.info("Initialized Pool with {} threads", parallelism);
    }

    public static void registerThread(String poolName, Thread thread) {
        mcThreadTracker
                .computeIfAbsent(poolName, key -> ConcurrentHashMap.newKeySet())
                .add(new WeakReference<>(thread));
    }

    private static boolean isThreadInPool(Thread thread) {
        Set<WeakReference<Thread>> refs = mcThreadTracker.getOrDefault("Async-Tick", Set.of());
        boolean found = false;
        for (Iterator<WeakReference<Thread>> it = refs.iterator(); it.hasNext(); ) {
            Thread t = it.next().get();
            if (t == null) {
                it.remove(); // clean dead references
            } else if (t == thread) {
                found = true;
                // no break because we want to clean up all dead references while we're at it
            }
        }
        return found;
    }

    /**
     * Returns true if the current thread is one of the async tick pool threads.
     * NOTE: this is the OPPOSITE of the vanilla isSameThread() / isMainThread() semantics.
     * Use {@link #isMainThread()} to check for the server thread.
     */
    public static boolean isAsyncThread() {
        return isThreadInPool(Thread.currentThread());
    }

    /**
     * Returns true if the current thread is the Minecraft server (main) thread.
     */
    public static boolean isMainThread() {
        MinecraftServer srv = server;
        return srv != null && Thread.currentThread() == srv.getRunningThread();
    }

    public static void callEntityTick(ServerLevel world, Entity entity) {
        if (isShuttingDown) {
            tickSynchronously(world, entity);
            return;
        }

        if (shouldTickSynchronously(entity)) {
            tickSynchronously(world, entity);
        } else {
            if (!tickPool.isShutdown() && !tickPool.isTerminated()) {
                if (taskQueue.size() >= MAX_PENDING_TASKS) {
                    LOGGER.warn("Pending task queue full ({}) — running entity tick synchronously for {}", taskQueue.size(), entity.getUUID());
                    tickSynchronously(world, entity);
                } else {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                            performAsyncEntityTick(world, entity), tickPool
                    ).exceptionally(e -> {
                        logEntityError("Error in async tick, switching to synchronous", entity, e);
                        blacklistedEntity.add(entity.getUUID());
                        return null;
                    });
                    taskQueue.add(future);
                }
            } else {
                tickSynchronously(world, entity);
            }
        }
    }

    public static boolean shouldTickSynchronously(Entity entity) {
        if (entity.level().isClientSide()) {
            return true;
        }

        UUID entityId = entity.getUUID();
        boolean requiresSyncTick = AsyncConfig.isDisabled ||
                entity instanceof Projectile ||
                entity instanceof AbstractMinecart ||
                entity instanceof ServerPlayer ||
                //_ENTITIES.contains(entity.getClass()) ||
                (entity instanceof FallingBlockEntity || entity instanceof Shulker || entity instanceof Boat) || //cover child classes of the blocked entities
                blacklistedEntity.contains(entityId) ||
                AsyncConfig.isEntitySynchronized(EntityType.getKey(entity.getType()));

        if (requiresSyncTick) {
            return true;
        }

        Integer[] shouldSync = {null};
        portalTickSyncMap.compute(entityId, (id, ticksLeft) -> {
            if (ticksLeft != null) {
                if (ticksLeft > 0) {
                    shouldSync[0] = ticksLeft - 1;
                    return ticksLeft - 1;
                }
                return null; // remove from map when it hits 0
            }
            return null;
        });

        if (shouldSync[0] != null) {
            return true;
        }

        if (isPortalTickRequired(entity)) {
            portalTickSyncMap.put(entityId, 39);
            return true;
        }

        return false;
    }

    private static boolean isPortalTickRequired(Entity entity) {
        return entity.isInsidePortal; //TODO: AT
    }

    private static void tickSynchronously(ServerLevel world, Entity entity) {
        try {
            world.tickNonPassenger(entity);
        } catch (Exception e) {
            logEntityError("Error during synchronous tick", entity, e);
        }
    }

    private static void performAsyncEntityTick(ServerLevel world, Entity entity) {
        currentEntities.incrementAndGet();
        try {
            world.tickNonPassenger(entity);
        } finally {
            currentEntities.decrementAndGet();
        }
    }

    public static Object getEntityAddLock() {
        return ENTITY_ADD_LOCK;
    }

    public static void asyncSpawnForChunk(
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawner.SpawnState spawnState,
            boolean spawnAnimals, boolean spawnMonsters, boolean rareSpawn)
    {
        if (!chunk.loaded) { //TODO: AT
            return;
        }

        if (isShuttingDown || AsyncConfig.isDisabled || !AsyncConfig.isAsyncSpawnEnabled) {
            NaturalSpawner.spawnForChunk(level, chunk, spawnState, spawnAnimals, spawnMonsters, rareSpawn);
            return;
        }

        if (!spawnAnimals && !spawnMonsters && !rareSpawn) {
            return;
        }

        //TODO: I might be schitzo but spawnState might also need to be copied cause concurrency or sum shi. bool may also need copy but we ball

        if (spawnQueue.size() >= MAX_PENDING_TASKS) {
            LOGGER.warn("Spawn queue full ({}). Running spawn for chunk {} synchronously", spawnQueue.size(), chunk.getPos());
            NaturalSpawner.spawnForChunk(level, chunk, spawnState, spawnAnimals, spawnMonsters, rareSpawn);
            return;
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                NaturalSpawner.spawnForChunk(level, chunk, spawnState, spawnAnimals, spawnMonsters, rareSpawn), tickPool
        ).exceptionally(e -> {
            LOGGER.error("Error in async spawn for chunk {}: {}", chunk.getPos(), e.getMessage());
            return null;
        });

        spawnQueue.add(future);
    }

    public static void asyncDespawn(Entity entity) {
        if (isShuttingDown || AsyncConfig.isDisabled || !AsyncConfig.isAsyncSpawnEnabled) {
            entity.checkDespawn();
            return;
        }

        if (taskQueue.size() >= MAX_PENDING_TASKS) {
            LOGGER.warn("Pending task queue full ({}). Running despawn synchronously for {}", taskQueue.size(), entity.getUUID());
            entity.checkDespawn();
            return;
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(
                entity::checkDespawn, tickPool
        ).exceptionally(e -> {
            LOGGER.error("Error in async spawn tick, switching to synchronous", e);
            entity.checkDespawn();
            return null;
        });

        taskQueue.add(future);
    }

    public static void addTask(CompletableFuture<?> future) {
        if (taskQueue.size() >= MAX_PENDING_TASKS) {
            LOGGER.warn("addTask: task queue full ({}). Cancelling added task.", taskQueue.size());
            try {
                future.cancel(true);
            } catch (Exception ignored) {
            }
            return;
        }
        taskQueue.add(future);
    }

    public static void postEntityTick() {
        if (AsyncConfig.isDisabled) return;

        List<CompletableFuture<?>> entityTasks = new ArrayList<>();
        CompletableFuture<?> future;
        while ((future = taskQueue.poll()) != null) {
            entityTasks.add(future);
        }

        List<CompletableFuture<?>> spawnTasks = new ArrayList<>();
        CompletableFuture<Void> spawnFuture;
        while ((spawnFuture = spawnQueue.poll()) != null) {
            spawnTasks.add(spawnFuture);
        }

        List<CompletableFuture<?>> allTasks = new ArrayList<>(entityTasks.size() + spawnTasks.size());
        allTasks.addAll(entityTasks);
        allTasks.addAll(spawnTasks);

        if (allTasks.isEmpty()) {
            return;
        }

        CompletableFuture<Void> allTasksFuture = CompletableFuture.allOf(
                allTasks.toArray(new CompletableFuture[0])
        );

        while (!allTasksFuture.isDone()) {
            boolean didWork = false;
            for (ServerLevel world : server.getAllLevels()) {
                try {
                    didWork |= world.getChunkSource().pollTask();
                } catch (java.util.NoSuchElementException ignored) {
                    // pollTask() -> runTask() -> queue.remove() throws NoSuchElementException when empty; ignore
                }
            }

            if (!didWork) {
                try {
                    // Wait briefly on the CompletableFuture to avoid busy spinning. Timeout will wake periodically to poll tasks again.
                    allTasksFuture.get(10, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // expected timeout to re-check pending tasks
                } catch (Exception e) {
                    LOGGER.error("Unexpected exception while waiting for async tasks to complete", e);
                    break;
                }
            }
        }

        // Ne pas appeler pollTask() inconditionnellement ici :
        // runTask() appelle queue.remove() sans vérifier isEmpty() -> NoSuchElementException si vide.
        // La boucle while ci-dessus a déjà drainé tout le travail en attente.
    }

    public static void stop() {
        isShuttingDown = true;

        List<CompletableFuture<?>> remaining = new ArrayList<>();
        CompletableFuture<?> f;
        while ((f = taskQueue.poll()) != null) {
            remaining.add(f);
        }
        CompletableFuture<Void> sf;
        while ((sf = spawnQueue.poll()) != null) {
            remaining.add(sf);
        }

        if (!remaining.isEmpty()) {
            CompletableFuture.allOf(remaining.toArray(new CompletableFuture[0])).join();
        }

        if (tickPool != null) {
            tickPool.shutdown();
            boolean quiesced = tickPool.awaitQuiescence(10, TimeUnit.SECONDS);
            if (!quiesced) {
                LOGGER.warn("The pool did not stop in time! Forcing shutdown...");
                tickPool.shutdownNow();
            }
        }

        AsyncConfig.clearCaches();
        blacklistedEntity.clear();
        portalTickSyncMap.clear();
    }

    private static void logEntityError(String message, Entity entity, Throwable e) {
        LOGGER.error("{} Entity Type: {}, UUID: {}", message, entity.getType().toString(), entity.getUUID(), e);
    }
}