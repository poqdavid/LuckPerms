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

package me.lucko.luckperms.common.caching;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.caching.MetaData;
import me.lucko.luckperms.api.caching.PermissionData;
import me.lucko.luckperms.api.caching.UserData;
import me.lucko.luckperms.common.calculators.CalculatorFactory;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.utils.ExtractedContexts;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Holds an easily accessible cache of a user's data in a number of contexts
 */
@RequiredArgsConstructor
public class UserCache implements UserData {


    /**
     * The user whom this data instance is representing
     */
    private final User user;

    /**
     * A provider of {@link me.lucko.luckperms.common.calculators.PermissionCalculator}s for the instance
     */
    private final CalculatorFactory calculatorFactory;

    private final LoadingCache<Contexts, PermissionCache> permission = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<Contexts, PermissionCache>() {
                @Override
                public PermissionCache load(Contexts contexts) {
                    return calculatePermissions(contexts);
                }

                @Override
                public PermissionCache reload(Contexts contexts, PermissionCache oldData) {
                    oldData.comparePermissions(user.exportNodes(ExtractedContexts.generate(contexts), true));
                    return oldData;
                }
            });

    private final LoadingCache<Contexts, MetaCache> meta = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<Contexts, MetaCache>() {
                @Override
                public MetaCache load(Contexts contexts) {
                    return calculateMeta(contexts);
                }

                @Override
                public MetaCache reload(Contexts contexts, MetaCache oldData) {
                    oldData.loadMeta(user.accumulateMeta(null, null, ExtractedContexts.generate(contexts)));
                    return oldData;
                }
            });

    @Override
    public PermissionData getPermissionData(@NonNull Contexts contexts) {
        return permission.get(contexts);
    }

    @Override
    public MetaData getMetaData(@NonNull Contexts contexts) {
        return meta.get(contexts);
    }

    @Override
    public PermissionCache calculatePermissions(@NonNull Contexts contexts) {
        PermissionCache data = new PermissionCache(contexts, user, calculatorFactory);
        data.setPermissions(user.exportNodes(ExtractedContexts.generate(contexts), true));
        return data;
    }

    @Override
    public MetaCache calculateMeta(@NonNull Contexts contexts) {
        MetaCache data = new MetaCache();
        data.loadMeta(user.accumulateMeta(null, null, ExtractedContexts.generate(contexts)));
        return data;
    }

    @Override
    public void recalculatePermissions(@NonNull Contexts contexts) {
        permission.refresh(contexts);
    }

    @Override
    public void recalculateMeta(@NonNull Contexts contexts) {
        meta.refresh(contexts);
    }

    @Override
    public void recalculatePermissions() {
        Set<Contexts> keys = ImmutableSet.copyOf(permission.asMap().keySet());
        keys.forEach(permission::refresh);
    }

    @Override
    public void recalculateMeta() {
        Set<Contexts> keys = ImmutableSet.copyOf(meta.asMap().keySet());
        keys.forEach(meta::refresh);
    }

    @Override
    public void preCalculate(@NonNull Set<Contexts> contexts) {
        contexts.forEach(this::preCalculate);
    }

    @Override
    public void preCalculate(@NonNull Contexts contexts) {
        permission.get(contexts);
        meta.get(contexts);
    }

    public void invalidateCache() {
        permission.invalidateAll();
        meta.invalidateAll();
    }

    @Override
    public void invalidatePermissionCalculators() {
        permission.asMap().values().forEach(PermissionData::invalidateCache);
    }

    public void cleanup() {
        permission.cleanUp();
        meta.cleanUp();
    }

}
