package com.axalotl.async.common.parallelised.utils;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.platform.PlatformUtils;

import java.util.ArrayList;
import java.util.List;

public class ModCompatibility {
    public static List<String> addUnsupportedMods() {
        final List<String> entries = new ArrayList<>();
        for (String modId : AsyncConfig.unsupportedMods.getValue()) {
            if (PlatformUtils.isModLoaded(modId)) {
                entries.add(modId + ":*");
                AsyncConfig.LOGGER.info("[Async] Mod '{}' detected — adding '{}:*' to synchronized entities.", modId, modId);
            }
        }
        return entries;
    }
}