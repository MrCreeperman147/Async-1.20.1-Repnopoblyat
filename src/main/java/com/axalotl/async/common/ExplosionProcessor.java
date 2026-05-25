package com.axalotl.async.common;

import net.minecraft.world.level.Explosion;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ExplosionProcessor {

    public record ExplosionTask(Explosion explosion, boolean spawnParticles) {}

    private static final LinkedBlockingQueue<ExplosionTask> workQueue = new LinkedBlockingQueue<>();
    private static volatile boolean running = false;
    private static Thread workerThread;

    public static void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(ExplosionProcessor::processQueue, "Async-Explosion-Processor");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public static void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    public static void queueExplosion(Explosion explosion, boolean spawnParticles) {
        workQueue.add(new ExplosionTask(explosion, spawnParticles));
    }

    private static void processQueue() {
        while (running) {
            try {
                // poll() avec timeout au lieu de take() :
                // take() bloque le thread indéfiniment si aucune explosion ne survient,
                // ce qui déclenche le ServerHangWatchdog si c'est le Server Thread.
                ExplosionTask task = workQueue.poll(50, TimeUnit.MILLISECONDS);
                if (task == null) continue;

                // Phase 1 — calcul des dégâts (CPU-intensif, safe en async)
                task.explosion().explode();

                // Phase 2 — effets finaux (sons, particules, drops, block damage)
                // Doit s'exécuter sur le Server Thread : accès non thread-safe aux
                // registres de blocs, aux joueurs proches, et aux chunk dirty flags.
                net.minecraft.server.MinecraftServer srv = ParallelProcessor.getServer();
                if (srv != null) {
                    final ExplosionTask t = task;
                    srv.execute(() -> t.explosion().finalizeExplosion(t.spawnParticles()));
                } else {
                    // Fallback si le serveur n'est pas encore initialisé
                    task.explosion().finalizeExplosion(task.spawnParticles());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}