/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge.managers;

import lombok.Getter;
import lombok.NonNull;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;

import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.context.ContextSet;
import me.lucko.luckperms.api.event.cause.CreationCause;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.managers.GroupManager;
import me.lucko.luckperms.common.utils.ArgumentChecker;
import me.lucko.luckperms.common.utils.ImmutableCollectors;
import me.lucko.luckperms.sponge.LPSpongePlugin;
import me.lucko.luckperms.sponge.model.SpongeGroup;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import me.lucko.luckperms.sponge.service.proxy.LPSubject;
import me.lucko.luckperms.sponge.service.proxy.LPSubjectCollection;
import me.lucko.luckperms.sponge.service.references.SubjectReference;
import me.lucko.luckperms.sponge.timings.LPTiming;

import org.spongepowered.api.service.permission.PermissionService;

import co.aikar.timings.Timing;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SpongeGroupManager implements GroupManager, LPSubjectCollection {

    @Getter
    private final LPSpongePlugin plugin;

    private final LoadingCache<String, SpongeGroup> objects = Caffeine.newBuilder()
            .build(new CacheLoader<String, SpongeGroup>() {
                @Override
                public SpongeGroup load(String i) {
                    return apply(i);
                }

                @Override
                public SpongeGroup reload(String i, SpongeGroup t) {
                    return t; // Never needs to be refreshed.
                }
            });

    private final LoadingCache<String, LPSubject> subjectLoadingCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build(s -> {
                if (isLoaded(s)) {
                    return getIfLoaded(s).getSpongeData();
                }

                // Request load
                getPlugin().getStorage().createAndLoadGroup(s, CreationCause.INTERNAL).join();

                SpongeGroup group = getIfLoaded(s);
                if (group == null) {
                    getPlugin().getLog().severe("Error whilst loading group '" + s + "'.");
                    throw new RuntimeException();
                }

                return group.getSpongeData();
            });

    public SpongeGroupManager(LPSpongePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public SpongeGroup apply(String name) {
        return new SpongeGroup(name, plugin);
    }

    /* ------------------------------------------
     * Manager methods
     * ------------------------------------------ */

    @Override
    public Map<String, SpongeGroup> getAll() {
        return ImmutableMap.copyOf(objects.asMap());
    }

    @Override
    public SpongeGroup getOrMake(String id) {
        return objects.get(id.toLowerCase());
    }

    @Override
    public SpongeGroup getIfLoaded(String id) {
        return objects.getIfPresent(id.toLowerCase());
    }

    @Override
    public boolean isLoaded(String id) {
        return objects.asMap().containsKey(id.toLowerCase());
    }

    @Override
    public void unload(String id) {
        if (id != null) {
            objects.invalidate(id.toLowerCase());
        }
    }

    @Override
    public void unload(Group t) {
        if (t != null) {
            unload(t.getId());
        }
    }

    @Override
    public void unloadAll() {
        objects.invalidateAll();
    }

    /* ------------------------------------------
     * SubjectCollection methods
     * ------------------------------------------ */

    @Override
    public String getIdentifier() {
        return PermissionService.SUBJECTS_GROUP;
    }

    @Override
    public LuckPermsService getService() {
        return plugin.getService();
    }

    @Override
    public LPSubject get(@NonNull String id) {
        // Special Sponge method. This call will actually load the group from the datastore if not already present.

        try (Timing ignored = plugin.getTimings().time(LPTiming.GROUP_COLLECTION_GET)) {
            id = id.toLowerCase();
            if (ArgumentChecker.checkNameWithSpace(id)) {
                plugin.getLog().warn("Couldn't get group subject for id: " + id + " (invalid name)");
                return plugin.getService().getFallbackGroupSubjects().get(id); // fallback to transient collection
            }

            try {
                return subjectLoadingCache.get(id);
            } catch (Exception e) {
                e.printStackTrace();
                plugin.getLog().warn("Couldn't get group subject for id: " + id);
                return plugin.getService().getFallbackGroupSubjects().get(id); // fallback to the transient collection
            }
        }
    }

    @Override
    public boolean hasRegistered(@NonNull String id) {
        id = id.toLowerCase();
        return !ArgumentChecker.checkName(id) && isLoaded(id);
    }

    @Override
    public Collection<LPSubject> getSubjects() {
        return objects.asMap().values().stream().map(SpongeGroup::getSpongeData).collect(ImmutableCollectors.toImmutableList());
    }

    @Override
    public Map<LPSubject, Boolean> getWithPermission(@NonNull ContextSet contexts, @NonNull String node) {
        return objects.asMap().values().stream()
                .map(SpongeGroup::getSpongeData)
                .filter(sub -> sub.getPermissionValue(contexts, node) != Tristate.UNDEFINED)
                .collect(ImmutableCollectors.toImmutableMap(sub -> sub, sub -> sub.getPermissionValue(contexts, node).asBoolean()));
    }

    @Override
    public SubjectReference getDefaultSubject() {
        return SubjectReference.of("defaults", getIdentifier());
    }

    @Override
    public boolean getTransientHasPriority() {
        return true;
    }
}
