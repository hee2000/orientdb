/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.index.sbtreebonsai.local;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.concur.lock.OLockManager;
import com.orientechnologies.common.concur.lock.OPartitionedLockManager;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.types.OModifiableInteger;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OSBTreeBonsaiLocalException;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;
import com.orientechnologies.orient.core.storage.index.sbtree.local.OSBTree;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.Change;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.OBonsaiCollectionPointer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.locks.Lock;

/**
 * Tree-based dictionary algorithm. Similar to {@link OSBTree} but uses sub-pages of disk cache that is more efficient for small
 * data
 * structures.
 * Oriented for usage of several instances inside of one file.
 * Creation of several instances that represent the same collection is not allowed.
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @author Artem Orobets
 * @see OSBTree
 * @since 1.6.0
 */
public final class OSBTreeBonsaiLocal<K, V> extends ODurableComponent implements OSBTreeBonsai<K, V> {
  private static final OLockManager<Long> FILE_LOCK_MANAGER = new OPartitionedLockManager<>();

  private static final int                  PAGE_SIZE  = OGlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;
  private static final OBonsaiBucketPointer SYS_BUCKET = new OBonsaiBucketPointer(0, 0);

  private OBonsaiBucketPointer rootBucketPointer;

  private final Comparator<? super K> comparator = ODefaultComparator.INSTANCE;

  private volatile Long fileId = -1L;

  private OBinarySerializer<K> keySerializer;
  private OBinarySerializer<V> valueSerializer;

  public OSBTreeBonsaiLocal(final String name, final String dataFileExtension, final OAbstractPaginatedStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension);
  }

  public void create(final OBinarySerializer<K> keySerializer, final OBinarySerializer<V> valueSerializer) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(-1L);
      try {
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;

        if (isFileExists(getFullName())) {
          this.fileId = openFile(getFullName());
        } else {
          this.fileId = addFile(getFullName(), atomicOperation);
        }

        initAfterCreate(atomicOperation);
      } finally {
        lock.unlock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  private void initAfterCreate(final OAtomicOperation atomicOperation) throws IOException {
    initSysBucket(atomicOperation);

    final AllocationResult allocationResult = allocateBucketForWrite(atomicOperation);
    final OCacheEntry rootCacheEntry = allocationResult.getCacheEntry();
    this.rootBucketPointer = allocationResult.getPointer();

    OSBTreeBonsaiBucket<K, V> rootBucket = null;
    try {
      rootBucket = new OSBTreeBonsaiBucket<>(rootCacheEntry, this.rootBucketPointer.getPageOffset());
      rootBucket.init(true, keySerializer.getId(), valueSerializer.getId());
      rootBucket.setTreeSize(0);
    } finally {
      releasePageFromWrite(rootBucket, atomicOperation);
    }
  }

  @Override
  public final long getFileId() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return fileId;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public final OBonsaiBucketPointer getRootBucketPointer() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return rootBucketPointer;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public final OBonsaiCollectionPointer getCollectionPointer() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        return new OBonsaiCollectionPointer(fileId, rootBucketPointer);
      } finally {
        lock.unlock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final V get(final K key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final BucketSearchResult bucketSearchResult = findBucket(key);
        if (bucketSearchResult.itemIndex < 0) {
          return null;
        }

        final OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

        final OCacheEntry keyBucketCacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
        try {
          final OSBTreeBonsaiBucket<K, V> keyBucket = new OSBTreeBonsaiBucket<>(keyBucketCacheEntry, bucketPointer.getPageOffset());
          return keyBucket.getEntry(bucketSearchResult.itemIndex, keySerializer, valueSerializer).value;
        } finally {
          releasePageFromRead(keyBucketCacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during retrieving  of sbtree with name " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final boolean put(final K key, final V value) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
      try {
        BucketSearchResult bucketSearchResult = findBucket(key);
        OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

        OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
        OSBTreeBonsaiBucket<K, V> keyBucket = new OSBTreeBonsaiBucket<>(keyBucketCacheEntry, bucketPointer.getPageOffset());

        final boolean itemFound = bucketSearchResult.itemIndex >= 0;
        if (itemFound) {
          keyBucket.updateValue(bucketSearchResult.itemIndex, valueSerializer.serializeNativeAsWhole(value), keySerializer);
        } else {
          int insertionIndex = -bucketSearchResult.itemIndex - 1;

          while (!keyBucket.insertEntry(insertionIndex,
              new OSBTreeBonsaiBucket.SBTreeEntry<>(OBonsaiBucketPointer.NULL, OBonsaiBucketPointer.NULL, key, value),
              keySerializer, valueSerializer)) {
            releasePageFromWrite(keyBucket, atomicOperation);

            bucketSearchResult = splitBucket(bucketSearchResult.path, insertionIndex, key, atomicOperation);
            bucketPointer = bucketSearchResult.getLastPathItem();

            insertionIndex = bucketSearchResult.itemIndex;

            keyBucketCacheEntry = loadPageForWrite(fileId, bucketSearchResult.getLastPathItem().getPageIndex(), false);

            keyBucket = new OSBTreeBonsaiBucket<>(keyBucketCacheEntry, bucketPointer.getPageOffset());
          }
        }

        releasePageFromWrite(keyBucket, atomicOperation);

        if (!itemFound) {
          updateSize(1, atomicOperation);
        }

        return true;
      } finally {
        lock.unlock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  public void close() {
    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      readCache.closeFile(fileId, true, writeCache);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes all entries from bonsai tree. Put all but the root page to free list for further reuse.
   */
  @Override
  public final void clear() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
      try {
        final Queue<OBonsaiBucketPointer> subTreesToDelete = new LinkedList<>();

        OSBTreeBonsaiBucket<K, V> rootBucket = null;
        final OCacheEntry cacheEntry = loadPageForWrite(fileId, rootBucketPointer.getPageIndex(), false);
        try {
          rootBucket = new OSBTreeBonsaiBucket<>(cacheEntry, rootBucketPointer.getPageOffset());

          addChildrenToQueue(subTreesToDelete, rootBucket);

          rootBucket.shrink(0, keySerializer, valueSerializer);
          rootBucket = new OSBTreeBonsaiBucket<>(cacheEntry, rootBucketPointer.getPageOffset());
          rootBucket.init(true, keySerializer.getId(), valueSerializer.getId());

          rootBucket.setTreeSize(0);
        } finally {
          releasePageFromWrite(rootBucket, atomicOperation);
        }

        recycleSubTrees(subTreesToDelete, atomicOperation);
      } finally {
        lock.unlock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  private void addChildrenToQueue(final Queue<OBonsaiBucketPointer> subTreesToDelete, final OSBTreeBonsaiBucket<K, V> rootBucket) {
    if (!rootBucket.isLeaf()) {
      final int size = rootBucket.size();
      if (size > 0) {
        subTreesToDelete.add(rootBucket.getEntry(0, keySerializer, valueSerializer).leftChild);
      }

      for (int i = 0; i < size; i++) {
        final OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry = rootBucket.getEntry(i, keySerializer, valueSerializer);
        subTreesToDelete.add(entry.rightChild);
      }
    }
  }

  private void recycleSubTrees(final Queue<OBonsaiBucketPointer> subTreesToDelete, final OAtomicOperation atomicOperation)
      throws IOException {
    OBonsaiBucketPointer head = OBonsaiBucketPointer.NULL;
    final OBonsaiBucketPointer tail = subTreesToDelete.peek();

    int bucketCount = 0;
    while (!subTreesToDelete.isEmpty()) {
      OSBTreeBonsaiBucket<K, V> bucket = null;
      final OBonsaiBucketPointer bucketPointer = subTreesToDelete.poll();
      final OCacheEntry cacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
      try {
        bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());

        addChildrenToQueue(subTreesToDelete, bucket);

        bucket.setFreeListPointer(head);
        if (bucket.size() > 0) {
          bucket.shrink(0, keySerializer, valueSerializer);
        }
        bucket.setDeleted();
        head = bucketPointer;
      } finally {
        releasePageFromWrite(bucket, atomicOperation);
      }
      bucketCount++;
    }

    if (head.isValid()) {
      OSysBucket sysBucket = null;
      final OCacheEntry sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);
      try {
        sysBucket = new OSysBucket(sysCacheEntry);

        assert tail != null;
        attachFreeListHead(tail, sysBucket.getFreeListHead(), atomicOperation);
        sysBucket.setFreeListHead(head);
        sysBucket.setFreeListLength(sysBucket.freeListLength() + bucketCount);

      } finally {
        releasePageFromWrite(sysBucket, atomicOperation);
      }
    }
  }

  private void attachFreeListHead(final OBonsaiBucketPointer bucketPointer, final OBonsaiBucketPointer freeListHead,
      final OAtomicOperation atomicOperation) throws IOException {
    OSBTreeBonsaiBucket<K, V> bucket = null;
    final OCacheEntry cacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
    try {
      bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());

      bucket.setFreeListPointer(freeListHead);
    } finally {
      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  /**
   * Deletes a whole tree. Puts all its pages to free list for further reuse.
   */
  @Override
  public final void delete() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
      try {
        final Queue<OBonsaiBucketPointer> subTreesToDelete = new LinkedList<>();
        subTreesToDelete.add(rootBucketPointer);
        recycleSubTrees(subTreesToDelete, atomicOperation);
      } finally {
        lock.unlock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  public boolean load(final OBonsaiBucketPointer rootBucketPointer) {
    final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
    try {
      this.rootBucketPointer = rootBucketPointer;

      this.fileId = openFile(getFullName());

      final OCacheEntry rootCacheEntry = loadPageForRead(this.fileId, this.rootBucketPointer.getPageIndex(), false);
      try {
        final OSBTreeBonsaiBucket<K, V> rootBucket = new OSBTreeBonsaiBucket<>(rootCacheEntry,
            this.rootBucketPointer.getPageOffset());
        //noinspection unchecked
        keySerializer = (OBinarySerializer<K>) storage.getComponentsFactory().binarySerializerFactory
            .getObjectSerializer(rootBucket.getKeySerializerId());
        //noinspection unchecked
        valueSerializer = (OBinarySerializer<V>) storage.getComponentsFactory().binarySerializerFactory
            .getObjectSerializer(rootBucket.getValueSerializerId());

        return !rootBucket.isDeleted();

      } finally {
        releasePageFromRead(rootCacheEntry);
      }

    } catch (final IOException e) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException("Exception during loading of sbtree " + fileId, this), e);
    } finally {
      lock.unlock();
    }
  }

  private void updateSize(final long diffSize, final OAtomicOperation atomicOperation) throws IOException {
    OSBTreeBonsaiBucket<K, V> rootBucket = null;
    final OCacheEntry rootCacheEntry = loadPageForWrite(fileId, rootBucketPointer.getPageIndex(), false);

    try {
      rootBucket = new OSBTreeBonsaiBucket<>(rootCacheEntry, rootBucketPointer.getPageOffset());
      rootBucket.setTreeSize(rootBucket.getTreeSize() + diffSize);
    } finally {
      releasePageFromWrite(rootBucket, atomicOperation);
    }
  }

  @Override
  public final long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final OCacheEntry rootCacheEntry = loadPageForRead(fileId, rootBucketPointer.getPageIndex(), false);
        try {
          final OSBTreeBonsaiBucket rootBucket = new OSBTreeBonsaiBucket<>(rootCacheEntry, rootBucketPointer.getPageOffset());
          return rootBucket.getTreeSize();
        } finally {
          releasePageFromRead(rootCacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during retrieving of size of index " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final V remove(final K key) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireExclusiveLock(fileId);
      try {
        final BucketSearchResult bucketSearchResult = findBucket(key);
        if (bucketSearchResult.itemIndex < 0) {
          return null;
        }

        final OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

        final OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);
        final V removed;

        OSBTreeBonsaiBucket<K, V> keyBucket = null;
        try {
          keyBucket = new OSBTreeBonsaiBucket<>(keyBucketCacheEntry, bucketPointer.getPageOffset());

          removed = keyBucket.getEntry(bucketSearchResult.itemIndex, keySerializer, valueSerializer).value;

          keyBucket.removeLeafEntry(bucketSearchResult.itemIndex, keySerializer.getObjectSize(key),
              valueSerializer.getObjectSize(removed));
        } finally {
          releasePageFromWrite(keyBucket, atomicOperation);
        }
        updateSize(-1, atomicOperation);
        return removed;
      } finally {
        lock.unlock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  @Override
  public final Collection<V> getValuesMinor(final K key, final boolean inclusive, final int maxValuesToFetch) {
    final List<V> result = new ArrayList<>(100);

    loadEntriesMinor(key, inclusive, entry -> {
      result.add(entry.getValue());
      return maxValuesToFetch <= -1 || result.size() < maxValuesToFetch;
    });

    return result;
  }

  @Override
  public final void loadEntriesMinor(final K key, final boolean inclusive, final RangeResultListener<K, V> listener) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final BucketSearchResult bucketSearchResult = findBucket(key);

        OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();
        int index;
        if (bucketSearchResult.itemIndex >= 0) {
          index = inclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex - 1;
        } else {
          index = -bucketSearchResult.itemIndex - 2;
        }

        boolean firstBucket = true;
        do {
          final OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
          try {
            final OSBTreeBonsaiBucket<K, V> bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());
            if (!firstBucket) {
              index = bucket.size() - 1;
            }

            for (int i = index; i >= 0; i--) {
              if (!listener.addResult(bucket.getEntry(i, keySerializer, valueSerializer))) {
                return;
              }
            }

            bucketPointer = bucket.getLeftSibling();

            firstBucket = false;

          } finally {
            releasePageFromRead(cacheEntry);
          }
        } while (bucketPointer.getPageIndex() >= 0);
      } finally {
        lock.unlock();
      }
    } catch (final IOException ioe) {
      throw OException.wrapException(
          new OSBTreeBonsaiLocalException("Error during fetch of minor values for key " + key + " in sbtree " + getName(), this),
          ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final Collection<V> getValuesMajor(final K key, final boolean inclusive, final int maxValuesToFetch) {
    final List<V> result = new ArrayList<>(100);

    loadEntriesMajor(key, inclusive, true, entry -> {
      result.add(entry.getValue());
      return maxValuesToFetch <= -1 || result.size() < maxValuesToFetch;
    });

    return result;
  }

  /**
   * Load all entries with key greater then specified key.
   *
   * @param key       defines
   * @param inclusive if true entry with given key is included
   */
  @Override
  public final void loadEntriesMajor(final K key, final boolean inclusive, final boolean ascSortOrder,
      final RangeResultListener<K, V> listener) {
    if (!ascSortOrder) {
      throw new IllegalStateException("Descending sort order is not supported.");
    }

    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final BucketSearchResult bucketSearchResult = findBucket(key);
        OBonsaiBucketPointer bucketPointer = bucketSearchResult.getLastPathItem();

        int index;
        if (bucketSearchResult.itemIndex >= 0) {
          index = inclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
        } else {
          index = -bucketSearchResult.itemIndex - 1;
        }

        do {
          final OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
          try {
            final OSBTreeBonsaiBucket<K, V> bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());
            final int bucketSize = bucket.size();
            for (int i = index; i < bucketSize; i++) {
              if (!listener.addResult(bucket.getEntry(i, keySerializer, valueSerializer))) {
                return;
              }

            }

            bucketPointer = bucket.getRightSibling();
            index = 0;
          } finally {
            releasePageFromRead(cacheEntry);
          }

        } while (bucketPointer.getPageIndex() >= 0);
      } finally {
        lock.unlock();
      }
    } catch (final IOException ioe) {
      throw OException.wrapException(
          new OSBTreeBonsaiLocalException("Error during fetch of major values for key " + key + " in sbtree " + getName(), this),
          ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final Collection<V> getValuesBetween(final K keyFrom, final boolean fromInclusive, final K keyTo,
      final boolean toInclusive, final int maxValuesToFetch) {
    final List<V> result = new ArrayList<>(100);
    loadEntriesBetween(keyFrom, fromInclusive, keyTo, toInclusive, entry -> {
      result.add(entry.getValue());
      return maxValuesToFetch <= 0 || result.size() < maxValuesToFetch;
    });

    return result;
  }

  @Override
  public final K firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final LinkedList<PagePathItemUnit> path = new LinkedList<>();

        OBonsaiBucketPointer bucketPointer = rootBucketPointer;

        OCacheEntry cacheEntry = loadPageForRead(fileId, rootBucketPointer.getPageIndex(), false);
        int itemIndex = 0;
        try {
          OSBTreeBonsaiBucket<K, V> bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());

          while (true) {
            if (bucket.isLeaf()) {
              if (bucket.isEmpty()) {
                if (path.isEmpty()) {
                  return null;
                } else {
                  final PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex + 1;
                }
              } else {
                return bucket.getKey(0, keySerializer);
              }
            } else {
              if (bucket.isEmpty() || itemIndex > bucket.size()) {
                if (path.isEmpty()) {
                  return null;
                } else {
                  final PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex + 1;
                }
              } else {
                path.add(new PagePathItemUnit(bucketPointer, itemIndex));

                if (itemIndex < bucket.size()) {
                  final OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry = bucket.getEntry(itemIndex, keySerializer, valueSerializer);
                  bucketPointer = entry.leftChild;
                } else {
                  final OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry = bucket
                      .getEntry(itemIndex - 1, keySerializer, valueSerializer);
                  bucketPointer = entry.rightChild;
                }

                itemIndex = 0;
              }
            }

            releasePageFromRead(cacheEntry);

            cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);

            bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());
          }
        } finally {
          releasePageFromRead(cacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final K lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final LinkedList<PagePathItemUnit> path = new LinkedList<>();

        OBonsaiBucketPointer bucketPointer = rootBucketPointer;
        OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
        OSBTreeBonsaiBucket<K, V> bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());

        int itemIndex = bucket.size() - 1;
        try {
          while (true) {
            if (bucket.isLeaf()) {
              if (bucket.isEmpty()) {
                if (path.isEmpty()) {
                  return null;
                } else {
                  final PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex - 1;
                }
              } else {
                return bucket.getKey(bucket.size() - 1, keySerializer);
              }
            } else {
              if (itemIndex < -1) {
                if (!path.isEmpty()) {
                  final PagePathItemUnit pagePathItemUnit = path.removeLast();

                  bucketPointer = pagePathItemUnit.bucketPointer;
                  itemIndex = pagePathItemUnit.itemIndex - 1;
                } else {
                  return null;
                }
              } else {
                path.add(new PagePathItemUnit(bucketPointer, itemIndex));

                if (itemIndex > -1) {
                  final OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry = bucket.getEntry(itemIndex, keySerializer, valueSerializer);
                  bucketPointer = entry.rightChild;
                } else {
                  final OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry = bucket.getEntry(0, keySerializer, valueSerializer);
                  bucketPointer = entry.leftChild;
                }

                itemIndex = OSBTreeBonsaiBucket.MAX_BUCKET_SIZE_BYTES + 1;
              }
            }

            releasePageFromRead(cacheEntry);

            cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);

            bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());
            if (itemIndex == OSBTreeBonsaiBucket.MAX_BUCKET_SIZE_BYTES + 1) {
              itemIndex = bucket.size() - 1;
            }
          }
        } finally {
          releasePageFromRead(cacheEntry);
        }
      } finally {
        lock.unlock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OSBTreeBonsaiLocalException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  @Override
  public final void loadEntriesBetween(final K keyFrom, final boolean fromInclusive, final K keyTo, final boolean toInclusive,
      final RangeResultListener<K, V> listener) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        final BucketSearchResult bucketSearchResultFrom = findBucket(keyFrom);

        final OBonsaiBucketPointer bucketPointerFrom = bucketSearchResultFrom.getLastPathItem();

        final int indexFrom;
        if (bucketSearchResultFrom.itemIndex >= 0) {
          indexFrom = fromInclusive ? bucketSearchResultFrom.itemIndex : bucketSearchResultFrom.itemIndex + 1;
        } else {
          indexFrom = -bucketSearchResultFrom.itemIndex - 1;
        }

        final BucketSearchResult bucketSearchResultTo = findBucket(keyTo);
        final OBonsaiBucketPointer bucketPointerTo = bucketSearchResultTo.getLastPathItem();

        final int indexTo;
        if (bucketSearchResultTo.itemIndex >= 0) {
          indexTo = toInclusive ? bucketSearchResultTo.itemIndex : bucketSearchResultTo.itemIndex - 1;
        } else {
          indexTo = -bucketSearchResultTo.itemIndex - 2;
        }

        int startIndex = indexFrom;
        int endIndex;
        OBonsaiBucketPointer bucketPointer = bucketPointerFrom;

        resultsLoop:
        while (true) {

          final OCacheEntry cacheEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
          try {
            final OSBTreeBonsaiBucket<K, V> bucket = new OSBTreeBonsaiBucket<>(cacheEntry, bucketPointer.getPageOffset());
            if (!bucketPointer.equals(bucketPointerTo)) {
              endIndex = bucket.size() - 1;
            } else {
              endIndex = indexTo;
            }

            for (int i = startIndex; i <= endIndex; i++) {
              if (!listener.addResult(bucket.getEntry(i, keySerializer, valueSerializer))) {
                break resultsLoop;
              }

            }

            if (bucketPointer.equals(bucketPointerTo)) {
              break;
            }

            bucketPointer = bucket.getRightSibling();
            if (bucketPointer.getPageIndex() < 0) {
              break;
            }

          } finally {
            releasePageFromRead(cacheEntry);
          }

          startIndex = 0;
        }
      } finally {
        lock.unlock();
      }
    } catch (final IOException ioe) {
      throw OException.wrapException(new OSBTreeBonsaiLocalException(
          "Error during fetch of values between key " + keyFrom + " and key " + keyTo + " in sbtree " + getName(), this), ioe);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void flush() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
      try {
        writeCache.flush();
      } finally {
        lock.unlock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private BucketSearchResult splitBucket(final List<OBonsaiBucketPointer> path, final int keyIndex, final K keyToInsert,
      final OAtomicOperation atomicOperation) throws IOException {
    final OBonsaiBucketPointer bucketPointer = path.get(path.size() - 1);

    OSBTreeBonsaiBucket<K, V> bucketToSplit = null;
    final OCacheEntry bucketEntry = loadPageForWrite(fileId, bucketPointer.getPageIndex(), false);

    try {
      bucketToSplit = new OSBTreeBonsaiBucket<>(bucketEntry, bucketPointer.getPageOffset());

      final boolean splitLeaf = bucketToSplit.isLeaf();
      final int bucketSize = bucketToSplit.size();

      final int indexToSplit = bucketSize >>> 1;
      final K separationKey = bucketToSplit.getKey(indexToSplit, keySerializer);
      final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

      final int startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getRawEntry(i, keySerializer, valueSerializer));
      }

      if (!bucketPointer.equals(rootBucketPointer)) {
        OSBTreeBonsaiBucket<K, V> newRightBucket = null;
        final AllocationResult allocationResult = allocateBucketForWrite(atomicOperation);
        final OCacheEntry rightBucketEntry = allocationResult.getCacheEntry();
        final OBonsaiBucketPointer rightBucketPointer = allocationResult.getPointer();

        try {
          newRightBucket = new OSBTreeBonsaiBucket<>(rightBucketEntry, rightBucketPointer.getPageOffset());
          newRightBucket.init(splitLeaf, keySerializer.getId(), valueSerializer.getId());
          newRightBucket.addAll(rightEntries);

          bucketToSplit.shrink(indexToSplit, keySerializer, valueSerializer);

          if (splitLeaf) {
            final OBonsaiBucketPointer rightSiblingBucketPointer = bucketToSplit.getRightSibling();

            newRightBucket.setRightSibling(rightSiblingBucketPointer);
            newRightBucket.setLeftSibling(bucketPointer);

            bucketToSplit.setRightSibling(rightBucketPointer);

            if (rightSiblingBucketPointer.isValid()) {
              final OCacheEntry rightSiblingBucketEntry = loadPageForWrite(fileId, rightSiblingBucketPointer.getPageIndex(), false);

              final OSBTreeBonsaiBucket<K, V> rightSiblingBucket = new OSBTreeBonsaiBucket<>(rightSiblingBucketEntry,
                  rightSiblingBucketPointer.getPageOffset());
              try {
                rightSiblingBucket.setLeftSibling(rightBucketPointer);
              } finally {
                releasePageFromWrite(rightSiblingBucket, atomicOperation);
              }
            }
          }

          OBonsaiBucketPointer parentBucketPointer = path.get(path.size() - 2);
          OCacheEntry parentCacheEntry = loadPageForWrite(fileId, parentBucketPointer.getPageIndex(), false);

          OSBTreeBonsaiBucket<K, V> parentBucket = null;
          try {
            parentBucket = new OSBTreeBonsaiBucket<>(parentCacheEntry, parentBucketPointer.getPageOffset());
            final OSBTreeBonsaiBucket.SBTreeEntry<K, V> parentEntry = new OSBTreeBonsaiBucket.SBTreeEntry<>(bucketPointer,
                rightBucketPointer, separationKey, null);

            int insertionIndex = parentBucket.find(separationKey, keySerializer);
            assert insertionIndex < 0;

            insertionIndex = -insertionIndex - 1;
            while (!parentBucket.insertEntry(insertionIndex, parentEntry, keySerializer, valueSerializer)) {
              releasePageFromWrite(parentBucket, atomicOperation);

              final BucketSearchResult bucketSearchResult = splitBucket(path.subList(0, path.size() - 1), insertionIndex,
                  separationKey, atomicOperation);

              parentBucketPointer = bucketSearchResult.getLastPathItem();
              parentCacheEntry = loadPageForWrite(fileId, parentBucketPointer.getPageIndex(), false);

              insertionIndex = bucketSearchResult.itemIndex;

              parentBucket = new OSBTreeBonsaiBucket<>(parentCacheEntry, parentBucketPointer.getPageOffset());
            }

          } finally {
            releasePageFromWrite(parentBucket, atomicOperation);
          }

        } finally {
          releasePageFromWrite(newRightBucket, atomicOperation);
        }

        final ArrayList<OBonsaiBucketPointer> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

        if (comparator.compare(keyToInsert, separationKey) < 0) {
          resultPath.add(bucketPointer);
          return new BucketSearchResult(keyIndex, resultPath);
        }

        resultPath.add(rightBucketPointer);
        if (splitLeaf) {
          return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
        }
        return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);

      } else {
        final long treeSize = bucketToSplit.getTreeSize();

        final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

        for (int i = 0; i < indexToSplit; i++) {
          leftEntries.add(bucketToSplit.getRawEntry(i, keySerializer, valueSerializer));
        }

        final AllocationResult leftAllocationResult = allocateBucketForWrite(atomicOperation);
        final OCacheEntry leftBucketEntry = leftAllocationResult.getCacheEntry();
        final OBonsaiBucketPointer leftBucketPointer = leftAllocationResult.getPointer();

        OSBTreeBonsaiBucket<K, V> newLeftBucket = null;
        final AllocationResult rightAllocationResult = allocateBucketForWrite(atomicOperation);
        final OCacheEntry rightBucketEntry = rightAllocationResult.getCacheEntry();
        final OBonsaiBucketPointer rightBucketPointer = rightAllocationResult.getPointer();
        try {
          newLeftBucket = new OSBTreeBonsaiBucket<>(leftBucketEntry, leftBucketPointer.getPageOffset());
          newLeftBucket.init(splitLeaf, keySerializer.getId(), valueSerializer.getId());
          newLeftBucket.addAll(leftEntries);

          if (splitLeaf) {
            newLeftBucket.setRightSibling(rightBucketPointer);
          }
        } finally {
          releasePageFromWrite(newLeftBucket, atomicOperation);
        }

        OSBTreeBonsaiBucket<K, V> newRightBucket = null;
        try {
          newRightBucket = new OSBTreeBonsaiBucket<>(rightBucketEntry, rightBucketPointer.getPageOffset());
          newRightBucket.init(splitLeaf, keySerializer.getId(), valueSerializer.getId());
          newRightBucket.addAll(rightEntries);

          if (splitLeaf) {
            newRightBucket.setLeftSibling(leftBucketPointer);
          }
        } finally {
          releasePageFromWrite(newRightBucket, atomicOperation);
        }

        bucketToSplit = new OSBTreeBonsaiBucket<>(bucketEntry, bucketPointer.getPageOffset());
        bucketToSplit.shrink(0, keySerializer, valueSerializer);

        if (bucketToSplit.isLeaf()) {
          bucketToSplit.convertToNonLeaf();
        }

        bucketToSplit.setTreeSize(treeSize);

        bucketToSplit
            .insertEntry(0, new OSBTreeBonsaiBucket.SBTreeEntry<>(leftBucketPointer, rightBucketPointer, separationKey, null),
                keySerializer, valueSerializer);

        final ArrayList<OBonsaiBucketPointer> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

        logPageOperations(bucketToSplit, atomicOperation);

        if (comparator.compare(keyToInsert, separationKey) < 0) {
          resultPath.add(leftBucketPointer);
          return new BucketSearchResult(keyIndex, resultPath);
        }

        resultPath.add(rightBucketPointer);

        if (splitLeaf) {
          return new BucketSearchResult(keyIndex - indexToSplit, resultPath);
        }

        return new BucketSearchResult(keyIndex - indexToSplit - 1, resultPath);
      }

    } finally {
      releasePageFromWrite(bucketToSplit, atomicOperation);
    }
  }

  private BucketSearchResult findBucket(final K key) throws IOException {
    OBonsaiBucketPointer bucketPointer = rootBucketPointer;
    final ArrayList<OBonsaiBucketPointer> path = new ArrayList<>(100);

    while (true) {
      path.add(bucketPointer);
      final OCacheEntry bucketEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);

      final OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry;
      try {
        final OSBTreeBonsaiBucket<K, V> keyBucket = new OSBTreeBonsaiBucket<>(bucketEntry, bucketPointer.getPageOffset());
        final int index = keyBucket.find(key, keySerializer);

        if (keyBucket.isLeaf()) {
          return new BucketSearchResult(index, path);
        }

        if (index >= 0) {
          entry = keyBucket.getEntry(index, keySerializer, valueSerializer);
        } else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            entry = keyBucket.getEntry(insertionIndex - 1, keySerializer, valueSerializer);
          } else {
            entry = keyBucket.getEntry(insertionIndex, keySerializer, valueSerializer);
          }
        }

      } finally {
        releasePageFromRead(bucketEntry);
      }

      if (comparator.compare(key, entry.key) >= 0) {
        bucketPointer = entry.rightChild;
      } else {
        bucketPointer = entry.leftChild;
      }
    }
  }

  private void initSysBucket(final OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);
    if (sysCacheEntry == null) {
      sysCacheEntry = addPage(fileId, false);
      assert sysCacheEntry.getPageIndex() == SYS_BUCKET.getPageIndex();
    }

    OSysBucket sysBucket = null;
    try {
      sysBucket = new OSysBucket(sysCacheEntry);
      if (sysBucket.isInitialized()) {
        releasePageFromWrite(sysBucket, atomicOperation);

        sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);

        sysBucket = new OSysBucket(sysCacheEntry);
        sysBucket.init();
      }
    } finally {
      releasePageFromWrite(sysBucket, atomicOperation);
    }
  }

  private AllocationResult allocateBucketForWrite(final OAtomicOperation atomicOperation) throws IOException {
    OCacheEntry sysCacheEntry = loadPageForWrite(fileId, SYS_BUCKET.getPageIndex(), false);
    if (sysCacheEntry == null) {
      sysCacheEntry = addPage(fileId, false);
      assert sysCacheEntry.getPageIndex() == SYS_BUCKET.getPageIndex();
    }

    OSysBucket sysBucket = null;
    try {
      sysBucket = new OSysBucket(sysCacheEntry);
      if (sysBucket.freeListLength() > 0) {
        return reuseBucketFromFreeList(sysBucket);
      } else {
        final OBonsaiBucketPointer freeSpacePointer = sysBucket.getFreeSpacePointer();
        if (freeSpacePointer.getPageOffset() + OSBTreeBonsaiBucket.MAX_BUCKET_SIZE_BYTES > PAGE_SIZE) {
          final OCacheEntry cacheEntry = addPage(fileId, false);
          final long pageIndex = cacheEntry.getPageIndex();
          sysBucket.setFreeSpacePointer(new OBonsaiBucketPointer(pageIndex, OSBTreeBonsaiBucket.MAX_BUCKET_SIZE_BYTES));

          return new AllocationResult(new OBonsaiBucketPointer(pageIndex, 0), cacheEntry);
        } else {
          sysBucket.setFreeSpacePointer(new OBonsaiBucketPointer(freeSpacePointer.getPageIndex(),
              freeSpacePointer.getPageOffset() + OSBTreeBonsaiBucket.MAX_BUCKET_SIZE_BYTES));
          final OCacheEntry cacheEntry = loadPageForWrite(fileId, freeSpacePointer.getPageIndex(), false);

          return new AllocationResult(freeSpacePointer, cacheEntry);
        }
      }
    } finally {
      releasePageFromWrite(sysBucket, atomicOperation);
    }
  }

  private AllocationResult reuseBucketFromFreeList(final OSysBucket sysBucket) throws IOException {
    final OBonsaiBucketPointer oldFreeListHead = sysBucket.getFreeListHead();
    assert oldFreeListHead.isValid();

    final OCacheEntry cacheEntry = loadPageForWrite(fileId, oldFreeListHead.getPageIndex(), false);
    final OSBTreeBonsaiBucket<K, V> bucket = new OSBTreeBonsaiBucket<>(cacheEntry, oldFreeListHead.getPageOffset());

    sysBucket.setFreeListHead(bucket.getFreeListPointer());
    sysBucket.setFreeListLength(sysBucket.freeListLength() - 1);

    return new AllocationResult(oldFreeListHead, cacheEntry);
  }

  @Override
  public final int getRealBagSize(final Map<K, Change> changes) {
    final Map<K, Change> notAppliedChanges = new HashMap<>(changes);
    final OModifiableInteger size = new OModifiableInteger(0);
    loadEntriesMajor(firstKey(), true, true, entry -> {
      final Change change = notAppliedChanges.remove(entry.getKey());
      final int result;

      final Integer treeValue = (Integer) entry.getValue();
      if (change == null) {
        result = treeValue;
      } else {
        result = change.applyTo(treeValue);
      }

      size.increment(result);
      return true;
    });

    for (final Change change : notAppliedChanges.values()) {
      size.increment(change.applyTo(0));
    }

    return size.intValue();
  }

  @Override
  public final OBinarySerializer<K> getKeySerializer() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return keySerializer;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public final OBinarySerializer<V> getValueSerializer() {
    final Lock lock = FILE_LOCK_MANAGER.acquireSharedLock(fileId);
    try {
      return valueSerializer;
    } finally {
      lock.unlock();
    }
  }

  private static final class AllocationResult {
    private final OBonsaiBucketPointer pointer;
    private final OCacheEntry          cacheEntry;

    private AllocationResult(final OBonsaiBucketPointer pointer, final OCacheEntry cacheEntry) {
      this.pointer = pointer;
      this.cacheEntry = cacheEntry;
    }

    private OBonsaiBucketPointer getPointer() {
      return pointer;
    }

    private OCacheEntry getCacheEntry() {
      return cacheEntry;
    }
  }

  private static final class BucketSearchResult {
    private final int                             itemIndex;
    private final ArrayList<OBonsaiBucketPointer> path;

    private BucketSearchResult(final int itemIndex, final ArrayList<OBonsaiBucketPointer> path) {
      this.itemIndex = itemIndex;
      this.path = path;
    }

    final OBonsaiBucketPointer getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {
    private final OBonsaiBucketPointer bucketPointer;
    private final int                  itemIndex;

    private PagePathItemUnit(final OBonsaiBucketPointer bucketPointer, final int itemIndex) {
      this.bucketPointer = bucketPointer;
      this.itemIndex = itemIndex;
    }
  }

  public void debugPrintBucket(final PrintStream writer) throws IOException {
    final ArrayList<OBonsaiBucketPointer> path = new ArrayList<>(100);
    path.add(rootBucketPointer);
    debugPrintBucket(rootBucketPointer, writer, path);
  }

  @SuppressWarnings({ "resource", "StringConcatenationInsideStringBufferAppend" })
  private void debugPrintBucket(final OBonsaiBucketPointer bucketPointer, final PrintStream writer,
      final ArrayList<OBonsaiBucketPointer> path) throws IOException {

    final OCacheEntry bucketEntry = loadPageForRead(fileId, bucketPointer.getPageIndex(), false);
    OSBTreeBonsaiBucket.SBTreeEntry<K, V> entry;
    try {
      final OSBTreeBonsaiBucket<K, V> keyBucket = new OSBTreeBonsaiBucket<>(bucketEntry, bucketPointer.getPageOffset());
      if (keyBucket.isLeaf()) {
        for (int i = 0; i < path.size(); i++) {
          writer.append("\t");
        }
        writer.append(" Leaf bucket:" + bucketPointer.getPageIndex() + "|" + bucketPointer.getPageOffset());
        writer
            .append(" left bucket:" + keyBucket.getLeftSibling().getPageIndex() + "|" + keyBucket.getLeftSibling().getPageOffset());
        writer.append(
            " right bucket:" + keyBucket.getRightSibling().getPageIndex() + "|" + keyBucket.getRightSibling().getPageOffset());
        writer.append(" size:" + keyBucket.size());
        writer.append(" content: [");
        for (int index = 0; index < keyBucket.size(); index++) {
          entry = keyBucket.getEntry(index, keySerializer, valueSerializer);
          writer.append(entry.getKey() + ",");
        }
        writer.append("\n");
      } else {
        for (int i = 0; i < path.size(); i++) {
          writer.append("\t");
        }
        writer.append(" node bucket:" + bucketPointer.getPageIndex() + "|" + bucketPointer.getPageOffset());
        writer
            .append(" left bucket:" + keyBucket.getLeftSibling().getPageIndex() + "|" + keyBucket.getLeftSibling().getPageOffset());
        writer.append(
            " right bucket:" + keyBucket.getRightSibling().getPageIndex() + "|" + keyBucket.getRightSibling().getPageOffset());
        writer.append("\n");
        for (int index = 0; index < keyBucket.size(); index++) {
          entry = keyBucket.getEntry(index, keySerializer, valueSerializer);
          for (int i = 0; i < path.size(); i++) {
            writer.append("\t");
          }
          writer.append(" entry:" + index + " key: " + entry.getKey() + " left \n");
          OBonsaiBucketPointer next = entry.leftChild;
          path.add(next);
          debugPrintBucket(next, writer, path);
          path.remove(next);
          for (int i = 0; i < path.size(); i++) {
            writer.append("\t");
          }
          writer.append(" entry:" + index + " key: " + entry.getKey() + " right \n");
          next = entry.rightChild;
          path.add(next);
          debugPrintBucket(next, writer, path);
          path.remove(next);

        }
      }
    } finally {
      releasePageFromRead(bucketEntry);
    }

  }
}
