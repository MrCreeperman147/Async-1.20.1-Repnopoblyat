package com.axalotl.async.common.config;

import com.axalotl.async.common.commands.AsyncCommand;
import com.axalotl.async.common.parallelised.utils.ModCompatibility;
import com.axalotl.async.common.platform.PlatformUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class AsyncConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger("Async Config");

    //TODO: should probably get the values before so we don't spam .getValue() but shouldn't impact perf that much riggggghhtt??!?!?!?!??!!?!?!?!
    public static Map.Entry<String, Boolean> disabled = new AbstractMap.SimpleEntry<>("disabled", false);
    public static Map.Entry<String, Integer> maxThreads = new AbstractMap.SimpleEntry<>("paraMax", -1);
    public static Map.Entry<String, Boolean> enableAsyncSpawn = new AbstractMap.SimpleEntry<>("enableAsyncSpawn", true);
    public static Map.Entry<String, Boolean> enableAsyncRandomTicks = new AbstractMap.SimpleEntry<>("enableAsyncRandomTicks", false);
    // Liste adaptée au modpack "Terres de droite V8" (Forge 47.4.10, MC 1.20.1).
    // Sur ce pack, le coût des entités/mobs est négligeable (<1% du tick d'après le profil spark) :
    // synchroniser largement les mods à entités "exotiques" est donc quasi gratuit en perf et évite
    // les crashs/désyncs liés à du ticking d'entité non thread-safe. On force ces namespaces sur le thread principal.
    public static Map.Entry<String, List<String>> unsupportedMods = new AbstractMap.SimpleEntry<>("unsupportedMods", new ArrayList<>(List.of(
            // Create + addons à entités (contraptions, trains, wagons, canons)
            "create", "createbigcannons", "cbc_ballistics", "cbc_cw", "createmissiles", "railways", "createthreadedtrains",
            // Véhicules immersifs (MTS/IV) — entités véhicules très complexes
            "mts", "iav", "iavm", "iavn", "mtsofficialpack", "mcsp", "warbornrenewed",
            "auweschveb", "auweschvebh", "auweschvebm", "auweschvebx",
            // Armes à feu / balistique
            "tacz", "taczaddon", "superbwarfare", "daffas_arsenal", "ballistix", "ritchiesprojectilelib", "nuclearscience",
            // Mobs / boss à IA custom
            "alexsmobs", "alexscaves", "born_in_chaos_v1", "mowziesmobs", "scp_ultimate", "twilightforest",
            "vampirism", "touhou_little_maid", "faunify", "tropicraft", "blue_skies", "survival_instinct",
            // Divers entités spéciales (tombes, sentinelles, etc.)
            "tombstone", "securitycraft",
            // Conservé de l'upstream
            "fowlplay"
    )));
    public static Map.Entry<String, Set<String>> synchronizedEntities = new AbstractMap.SimpleEntry<>("synchronizedEntities", getDefaultSynchronizedEntities());

    // ===== Front B (PROTOTYPE) : parallélisation du ticking des block entities =====
    // Drapeau maître, OFF par défaut. Tant qu'il est false, le ticking BE reste 100% vanilla
    // (le mixin route vers un tick synchrone identique à l'original).
    public static Map.Entry<String, Boolean> enableAsyncBE = new AbstractMap.SimpleEntry<>("enableAsyncBlockEntities", false);
    // WHITELIST ultra-restrictive : SEULS les types listés ici ticquent en parallèle.
    // Format identique à synchronizedEntities :
    //   - "modid:type"  = un BlockEntityType précis (ex. "minecraft:furnace")
    //   - "modid:*"     = tous les BlockEntityType d'un mod
    // Whitelist vide (défaut) + flag ON => toujours rien en async (sûr). On opte-in famille par famille,
    // sur un monde de TEST uniquement. Voir docs/FRONT_B_PROTOTYPE.md pour les hazards.
    public static Map.Entry<String, Set<String>> parallelBlockEntities = new AbstractMap.SimpleEntry<>("parallelBlockEntities", new HashSet<>());

    // Caches
    private static final Map<ResourceLocation, Boolean> syncCache = new ConcurrentHashMap<>();
    private static final Set<String> exactEntities = new HashSet<>();
    private static final Set<String> namespaceWildcards = new HashSet<>();

    // Caches pour la whitelist block entities (clé = id type "modid:type" sous forme String)
    private static final Map<String, Boolean> beCache = new ConcurrentHashMap<>();
    private static final Set<String> beExact = new HashSet<>();
    private static final Set<String> beNamespaceWildcards = new HashSet<>();

    // These are used by mixins so need to be volatile to ensure visibility across threads
    public static volatile boolean isDisabled = false;
    public static volatile boolean isAsyncSpawnEnabled = true;
    public static volatile boolean isAsyncRandomTicksEnabled = false;
    public static volatile boolean isAsyncBEEnabled = false;


    public static Set<String> getDefaultSynchronizedEntities() {
        final Set<String> defaultSynchronizedEntities = new HashSet<>(ModCompatibility.addUnsupportedMods());
        defaultSynchronizedEntities.addAll(Set.of(
                "minecraft:tnt",
                "minecraft:item",
                "minecraft:experience_orb",
                "minecraft:falling_block",
                "minecraft:shulker",
                "minecraft:boat",
                "minecraft:chest_boat"
        ));
        return defaultSynchronizedEntities;
    }


    public static int getParallelism() {
        if (maxThreads.getValue() <= 0) return Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), maxThreads.getValue()));
    }

    public static boolean isNamespaceWildcard(String input) {
        if (input == null) return false;
        int colon = input.indexOf(':');
        if (colon <= 0) return false;
        return input.substring(colon + 1).equals("*");
    }

    public static boolean existsNamespace(String namespace, CommandSourceStack source) {
        for (ResourceLocation id : AsyncCommand.getEntityAccess(source).keySet()) {
            if (id.getNamespace().equals(namespace)) return true;
        }
        return false;
    }

    public static boolean matchesExistingNamespaceWildcard(String input, CommandSourceStack source) {
        if (!isNamespaceWildcard(input)) return false;
        String ns = input.substring(0, input.indexOf(':'));
        return existsNamespace(ns, source);
    }

    public static void syncEntity(String entity) {
        if (synchronizedEntities.getValue().add(entity)) {
            rebuildCaches();
            PlatformUtils.saveConfig();
            LOGGER.info("Added sync entity: {}", entity);
        } else {
            LOGGER.warn("Entity already synchronized: {}", entity);
        }
    }

    public static void removeEntity(String entity) {
        if (synchronizedEntities.getValue().remove(entity)) {
            rebuildCaches();
            PlatformUtils.saveConfig();
            LOGGER.info("Removed sync entity: {}", entity);
        } else {
            LOGGER.warn("Entity not found: {}", entity);
        }
    }

    private static void rebuildCaches() {
        syncCache.clear();
        exactEntities.clear();
        namespaceWildcards.clear();

        for (String entry : synchronizedEntities.getValue()) {
            if (isNamespaceWildcard(entry)) {
                String ns = entry.substring(0, entry.indexOf(':'));
                namespaceWildcards.add(ns);
            } else {
                exactEntities.add(entry);
            }
        }
    }

    private static void rebuildBECaches() {
        beCache.clear();
        beExact.clear();
        beNamespaceWildcards.clear();

        for (String entry : parallelBlockEntities.getValue()) {
            if (isNamespaceWildcard(entry)) {
                beNamespaceWildcards.add(entry.substring(0, entry.indexOf(':')));
            } else {
                beExact.add(entry);
            }
        }
    }

    /**
     * Whitelist : retourne true si ce type de block entity ("modid:type") doit ticker en parallèle.
     * typeId provient de {@code TickingBlockEntity.getType()} (= BlockEntityType registry id).
     */
    public static boolean isBlockEntityParallel(String typeId) {
        if (typeId == null) return false;

        Boolean cached = beCache.get(typeId);
        if (cached != null) return cached;

        boolean result = beExact.contains(typeId);
        if (!result) {
            int colon = typeId.indexOf(':');
            String ns = colon > 0 ? typeId.substring(0, colon) : "";
            result = beNamespaceWildcards.contains(ns);
        }

        beCache.put(typeId, result);
        return result;
    }

    public static boolean isEntitySynchronized(ResourceLocation entityId) {
        Boolean cached = syncCache.get(entityId);
        if (cached != null) return cached;

        String idString = entityId.toString();
        if (exactEntities.contains(idString)) {
            syncCache.put(entityId, true);
            return true;
        }

        if (namespaceWildcards.contains(entityId.getNamespace())) {
            syncCache.put(entityId, true);
            return true;
        }

        syncCache.put(entityId, false);
        return false;
    }

    public static void onConfigLoaded() {
        rebuildCaches();
        rebuildBECaches();

        isDisabled = disabled.getValue();
        isAsyncSpawnEnabled = enableAsyncSpawn.getValue();
        isAsyncRandomTicksEnabled = enableAsyncRandomTicks.getValue();
        isAsyncBEEnabled = enableAsyncBE.getValue();

        LOGGER.info("Configuration loaded.");
    }

    public static void clearCaches() {
        syncCache.clear();
        beCache.clear();
    }
}
