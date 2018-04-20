/*******************************************************************************
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 *     The aion network project leverages useful source code from other
 *     open source projects. We greatly appreciate the effort that was
 *     invested in these projects and we thank the individual contributors
 *     for their work. For provenance information and contributors
 *     please see <https://github.com/aionnetwork/aion/wiki/Contributors>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 *     Aion foundation.
 *     <ether.camp> team through the ethereumJ library.
 *     Ether.Camp Inc. (US) team through Ethereum Harmony.
 *     John Tromp through the Equihash solver.
 *     Samuel Neves through the BLAKE2 implementation.
 *     Zcash project team.
 *     Bitcoinj team.
 ******************************************************************************/
package org.aion.db.generic;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.aion.base.db.IByteArrayKeyValueDatabase;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * @author Alexandra Roatis
 */
public class CachedReadsDatabaseV3 implements IByteArrayKeyValueDatabase {

    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.DB.name());

    /** Underlying database implementation. */
    protected IByteArrayKeyValueDatabase database;

    /** Keeps track of the entries that have been modified. */
    private LoadingCache<ByteArrayWrapper, Optional<byte[]>> loadingCache = null;

    public CachedReadsDatabaseV3(IByteArrayKeyValueDatabase _database) {
        database = _database;
    }

    /**
     * Assists in setting up the underlying cache for the current instance.
     *
     * @param size
     * @param enableStats
     */
    private void setupLoadingCache(final long size, final boolean enableStats) {
        // Use CacheBuilder to create the cache.
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();

        // Set the size.
        // Actually when size is 0, we make it unbounded
        if (size != 0) {
            builder.maximumSize(size);
        }

        // Enable stats if passed in.
        if (enableStats) {
            builder.recordStats();
        }

        // Utilize CacheBuilder and pass in the parameters to create the cache.
        this.loadingCache = builder.build(new CacheLoader<ByteArrayWrapper, Optional<byte[]>>() {
            @Override
            public Optional<byte[]> load(ByteArrayWrapper keyToLoad) {
                // It is safe to say keyToLoad is not null or the data is null.
                // Load from the data source.
                return database.get(keyToLoad.getData());
            }
        });
    }

    /**
     * For testing the lock functionality of public methods.
     * Used to ensure that locks are released after normal or exceptional execution.
     *
     * @return {@code true} when the resource is locked,
     *         {@code false} otherwise
     */
    @Override
    public boolean isLocked() {
        return database.isLocked();
    }

    // IDatabase functionality -----------------------------------------------------------------------------------------

    @Override
    public boolean open() {
        if (isOpen()) {
            return true;
        }

        boolean open = database.open();

        // setup cache only id database was opened successfully
        if (open) {
            setupLoadingCache(1024, true);
        }

        return open;
    }

    @Override
    public void close() {
        try {
            // close database
            database.close();
        } finally {
            // clear the cache
            loadingCache.invalidateAll();
        }
    }

    @Override
    public boolean commit() {
        loadingCache.invalidateAll();
        return database.commit();
    }

    @Override
    public void compact() {
        database.compact();
    }

    @Override
    public Optional<String> getName() {
        return database.getName();
    }

    @Override
    public Optional<String> getPath() {
        return database.getPath();
    }

    @Override
    public boolean isOpen() {
        return database.isOpen();
    }

    @Override
    public boolean isClosed() {
        return database.isClosed();
    }

    @Override
    public boolean isAutoCommitEnabled() {
        return database.isAutoCommitEnabled();
    }

    @Override
    public boolean isPersistent() {
        return database.isPersistent();
    }

    @Override
    public boolean isCreatedOnDisk() {
        return database.isCreatedOnDisk();
    }

    @Override
    public long approximateSize() {
        return database.approximateSize();
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "-v3 over " + this.database.toString();
    }

    // IKeyValueStore functionality ------------------------------------------------------------------------------------

    @Override
    public boolean isEmpty() {
        if (loadingCache.size() > 0) {
            return false;
        } else {
            return database.isEmpty();
        }
    }

    @Override
    public Set<byte[]> keys() {
        return database.keys();
    }

    @Override
    public Optional<byte[]> get(byte[] k) {
        Optional<byte[]> val;

        try {
            val = loadingCache.get(ByteArrayWrapper.wrap(k));
            LOG.debug(getName().get() + " > " + loadingCache.stats().toString());
        } catch (ExecutionException e) {
            LOG.error(getName().get() + " -> Cannot load from cache.", e);
            return database.get(k);
        }

        return val;
    }

    @Override
    public void put(byte[] k, byte[] v) {
        loadingCache.put(ByteArrayWrapper.wrap(k), Optional.of(v));
        database.put(k, v);
    }

    @Override
    public void delete(byte[] k) {
        loadingCache.invalidate(ByteArrayWrapper.wrap(k));
        database.delete(k);
    }

    @Override
    public void putBatch(Map<byte[], byte[]> inputMap) {
        loadingCache.invalidateAll();
        database.putBatch(inputMap);
    }

    @Override
    public void putToBatch(byte[] k, byte[] v) {
        database.putToBatch(k, v);
    }

    @Override
    public void commitBatch() {
        loadingCache.invalidateAll();
        database.commitBatch();
    }

    @Override
    public void deleteBatch(Collection<byte[]> keys) {
        loadingCache.invalidateAll();
        database.deleteBatch(keys);
    }

    @Override
    public void drop() {
        loadingCache.invalidateAll();
        database.drop();
    }
}