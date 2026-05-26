package com.axalotl.async.common.mixin.utils;

import com.axalotl.async.common.platform.PlatformUtils;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.Map;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class SynchronisePlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int FINAL_STATIC_PRIVATE_ABSTRACT = 0x1548; // final, static, private, abstract
    private static final int SYNCHRONIZED = 0x20; // synchronized

    private static final Set<String> MOD_CONDITIONAL_MIXINS = Set.of(
            "com.axalotl.async.common.mixin.entity.MekanismCapabilityMixin",
            "com.axalotl.async.common.mixin.entity.LeafcutterAntAIMixin"
    );

    private static final Map<String, String> MIXIN_TO_TARGET_CLASS = Map.of(
            "com.axalotl.async.common.mixin.entity.MekanismCapabilityMixin",
            "mekanism.common.capabilities.resolver.BasicCapabilityResolver",
            "com.axalotl.async.common.mixin.entity.LeafcutterAntAIMixin",
            "com.github.alexthe666.alexsmobs.entity.ai.LeafcutterAntAIForageLeaves"
    );

    private final Multimap<String, String> mixin2MethodsMap = ArrayListMultimap.create();
    private final Multimap<String, String> mixin2MethodsExcludeMap = ArrayListMultimap.create();
    private final TreeSet<String> syncAllSet = new TreeSet<>();

    @Override
    public void onLoad(String mixinPackage) {
        mixin2MethodsExcludeMap.put("com.axalotl.async.common.mixin.utils.SyncAllMixin", "net.minecraft.world.level.chunk.ChunkStatus.isOrAfter");
        syncAllSet.add("com.axalotl.async.common.mixin.utils.FastUtilsMixin");
        syncAllSet.add("com.axalotl.async.common.mixin.utils.SyncAllMixin");
    }

    @Override
    public String getRefMapperConfig() {
        return PlatformUtils.platformUsesRefmap() ? "async.refmap.json" : null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (MOD_CONDITIONAL_MIXINS.contains(mixinClassName)) {
            String targetClass = MIXIN_TO_TARGET_CLASS.get(mixinClassName);
            if (targetClass != null) {
                try {
                    Class.forName(targetClass, false, this.getClass().getClassLoader());
                    return true;
                } catch (ClassNotFoundException e) {
                    LOGGER.debug("Skipping mixin {} — target class {} not found (mod not loaded)",
                            mixinClassName, targetClass);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        Collection<String> targetMethods = mixin2MethodsMap.get(mixinClassName);
        Collection<String> excludedMethods = mixin2MethodsExcludeMap.get(mixinClassName);

        if (!targetMethods.isEmpty()) {
            applySynchronizeBit(targetClass, targetMethods, targetClassName);
        } else if (syncAllSet.contains(mixinClassName)) {
            for (MethodNode method : targetClass.methods) {
                if ((method.access & FINAL_STATIC_PRIVATE_ABSTRACT) == 0 && !method.name.equals("<init>") && !excludedMethods.contains(method.name)) {
                    method.access |= SYNCHRONIZED;
                    logSynchronize(method.name, targetClassName, mixinClassName);
                }
            }
        }
    }



    private void applySynchronizeBit(ClassNode targetClass, Collection<String> targetMethods, String targetClassName) {
        for (MethodNode method : targetClass.methods) {
            for (String targetMethod : targetMethods) {
                if (method.name.equals(targetMethod)) {
                    method.access |= SYNCHRONIZED;
                    logSynchronize(method.name, targetClassName, null);
                }
            }
        }
    }

    private void logSynchronize(String methodName, String targetClassName, String mixinClassName) {
        if (mixinClassName == null || !mixinClassName.equals("com.axalotl.async.mixin.utils.FastUtilsMixin")) {
            String message = "Setting synchronize bit for " + methodName + " in " + targetClassName + ".";
            LOGGER.debug(message);
        }
    }
}