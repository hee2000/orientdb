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

package com.orientechnologies.orient.core.storage.index.sbtree.local;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.util.ORawPair;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.index.OIndexEngine;
import com.orientechnologies.orient.core.index.OIndexKeyUpdater;
import com.orientechnologies.orient.core.index.OIndexUpdateAction;
import com.orientechnologies.orient.core.iterator.OEmptyIterator;
import com.orientechnologies.orient.core.iterator.OEmptyMapEntryIterator;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This is implementation which is based on B+-tree implementation threaded tree.
 * The main differences are:
 * <ol>
 * <li>Buckets are not compacted/removed if they are empty after deletion of item. They reused later when new items are added.</li>
 * <li>All non-leaf buckets have links to neighbor buckets which contain keys which are less/more than keys contained in current
 * bucket</li>
 * <ol/>
 * There is support of null values for keys, but values itself cannot be null. Null keys support is switched off by default if null
 * keys are supported value which is related to null key will be stored in separate file which has only one page.
 * Buckets/pages for usual (non-null) key-value entries can be considered as sorted array. The first bytes of page contains such
 * auxiliary information as size of entries contained in bucket, links to neighbors which contain entries with keys less/more than
 * keys in current bucket.
 * The next bytes contain sorted array of entries. Array itself is split on two parts. First part is growing from start to end, and
 * second part is growing from end to start.
 * First part is array of offsets to real key-value entries which are stored in second part of array which grows from end to start.
 * This array of offsets is sorted by accessing order according to key value. So we can use binary search to find requested key.
 * When new key-value pair is added we append binary presentation of this pair to the second part of array which grows from end of
 * page to start, remember value of offset for this pair, and find proper position of this offset inside of first part of array.
 * Such approach allows to minimize amount of memory involved in performing of operations and as result speed up data processing.
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 8/7/13
 */
public final class OPrefixBTree<V> extends ODurableComponent {
  private static final int MAX_EMBEDDED_VALUE_SIZE = OGlobalConfiguration.SBTREE_MAX_EMBEDDED_VALUE_SIZE.getValueAsInteger();

  private static final int MAX_PATH_LENGTH = OGlobalConfiguration.SBTREE_MAX_DEPTH.getValueAsInteger();

  private final static long                      ROOT_INDEX       = 0;
  private final        Comparator<Object>        comparator       = ODefaultComparator.INSTANCE;
  private final        String                    nullFileExtension;
  private              long                      fileId;
  private              long                      nullBucketFileId = -1;
  private              OBinarySerializer<String> keySerializer;
  private              OBinarySerializer<V>      valueSerializer;
  private              boolean                   nullPointerSupport;
  private final        AtomicLong                bonsaiFileId     = new AtomicLong(0);
  private              OEncryption               encryption;

  public OPrefixBTree(final String name, final String dataFileExtension, final String nullFileExtension,
      final OAbstractPaginatedStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension);
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void create(final OBinarySerializer<String> keySerializer, final OBinarySerializer<V> valueSerializer,
      final boolean nullPointerSupport, final OEncryption encryption) throws IOException {
    assert keySerializer != null;

    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      acquireExclusiveLock();
      try {
        this.encryption = encryption;
        this.keySerializer = keySerializer;

        this.valueSerializer = valueSerializer;
        this.nullPointerSupport = nullPointerSupport;

        fileId = addFile(getFullName(), atomicOperation);

        if (nullPointerSupport) {
          nullBucketFileId = addFile(getName() + nullFileExtension, atomicOperation);
        }

        OPrefixBTreeBucket<V> rootBucket = null;
        final OCacheEntry rootCacheEntry = addPage(fileId, false);
        try {
          rootBucket = new OPrefixBTreeBucket<>(rootCacheEntry, true, keySerializer, valueSerializer, encryption, "");
          rootBucket.setTreeSize(0);
        } finally {
          releasePageFromWrite(rootBucket, atomicOperation);
        }

      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  @SuppressWarnings("unused")
  public boolean isNullPointerSupport() {
    acquireSharedLock();
    try {
      return nullPointerSupport;
    } finally {
      releaseSharedLock();
    }
  }

  public V get(String key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        checkNullSupport(key);

        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        if (key != null) {
          key = keySerializer.preprocess(key, OType.STRING);

          final BucketSearchResult bucketSearchResult = findBucket(key);
          if (bucketSearchResult.itemIndex < 0) {
            return null;
          }

          final long pageIndex = bucketSearchResult.getLastPathItem();
          final OCacheEntry keyBucketCacheEntry = loadPageForRead(fileId, pageIndex, false);
          try {
            final OPrefixBTreeBucket<V> keyBucket = new OPrefixBTreeBucket<>(keyBucketCacheEntry, keySerializer, valueSerializer,
                encryption);

            return readValue(keyBucket.getValue(bucketSearchResult.itemIndex));
          } finally {
            releasePageFromRead(keyBucketCacheEntry);
          }
        } else {
          if (getFilledUpTo(nullBucketFileId) == 0) {
            return null;
          }

          final OCacheEntry nullBucketCacheEntry = loadPageForRead(nullBucketFileId, 0, false);
          try {
            final ONullBucket<V> nullBucket = new ONullBucket<>(nullBucketCacheEntry);
            final OSBTreeValue<V> treeValue = nullBucket.getValue(valueSerializer);
            if (treeValue == null) {
              return null;
            }

            return readValue(treeValue);
          } finally {
            releasePageFromRead(nullBucketCacheEntry);
          }
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OPrefixBTreeException("Error during retrieving  of sbtree with name " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void put(final String key, final V value) throws IOException {
    put(key, value, null);
  }

  public boolean validatedPut(final String key, final V value, final OIndexEngine.Validator<String, V> validator)
      throws IOException {
    return put(key, value, validator);
  }

  private boolean put(final String key, final V value, final OIndexEngine.Validator<String, V> validator) throws IOException {
    return update(key, (x, bonsaiFileId) -> OIndexUpdateAction.changed(value), validator);
  }

  @SuppressWarnings("unchecked")
  public final boolean update(String key, final OIndexKeyUpdater<V> updater, final OIndexEngine.Validator<String, V> validator)
      throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);

    try {
      assert atomicOperation != null;
      acquireExclusiveLock();
      try {
        checkNullSupport(key);

        if (key != null) {
          key = keySerializer.preprocess(key, OType.STRING);

          BucketUpdateSearchResult bucketSearchResult = findBucketForUpdate(key);

          OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, bucketSearchResult.getLastPathItem(), false);
          OPrefixBTreeBucket<V> keyBucket = new OPrefixBTreeBucket<>(keyBucketCacheEntry, keySerializer, valueSerializer,
              encryption);

          final byte[] oldRawValue = bucketSearchResult.itemIndex > -1 ? keyBucket.getRawValue(bucketSearchResult.itemIndex) : null;
          final V oldValue;
          if (oldRawValue == null) {
            oldValue = null;
          } else {
            oldValue = valueSerializer.deserializeNativeObject(oldRawValue, 0);
          }

          final OIndexUpdateAction<V> updatedValue = updater.update(oldValue, bonsaiFileId);
          if (updatedValue.isChange()) {
            V value = updatedValue.getValue();

            if (validator != null) {
              boolean failure = true; // assuming validation throws by default
              boolean ignored = false;

              try {

                final Object result = validator.validate(key, oldValue, value);
                if (result == OIndexEngine.Validator.IGNORE) {
                  ignored = true;
                  failure = false;
                  return false;
                }

                value = (V) result;
                failure = false;
              } finally {
                if (failure || ignored) {
                  releasePageFromWrite(keyBucket, atomicOperation);
                }
              }
            }

            final byte[] serializeValue = valueSerializer.serializeNativeAsWhole(value);
            final boolean createLinkToTheValue = serializeValue.length > MAX_EMBEDDED_VALUE_SIZE;
            assert !createLinkToTheValue;

            int insertionIndex;
            final int sizeDiff;
            if (bucketSearchResult.itemIndex >= 0) {
              assert oldRawValue != null;

              if (oldRawValue.length == serializeValue.length) {
                keyBucket.updateValue(bucketSearchResult.itemIndex, serializeValue);
                releasePageFromWrite(keyBucket, atomicOperation);
                return true;
              } else {
                keyBucket.remove(bucketSearchResult.itemIndex);
                insertionIndex = bucketSearchResult.itemIndex;
                sizeDiff = 0;
              }
            } else {
              insertionIndex = -bucketSearchResult.itemIndex - 1;
              sizeDiff = 1;
            }

            while (!keyBucket
                .addEntry(insertionIndex, new OPrefixBTreeBucket.SBTreeEntry<>(-1, -1, key, new OSBTreeValue<>(false, -1, value)),
                    true)) {
              bucketSearchResult = splitBucket(keyBucket, keyBucketCacheEntry, bucketSearchResult.path,
                  bucketSearchResult.leftBoundaries, bucketSearchResult.rightBoundaries, insertionIndex, key, atomicOperation);

              insertionIndex = bucketSearchResult.itemIndex;
              final long parentIndex = bucketSearchResult.getLastPathItem();

              if (parentIndex != keyBucketCacheEntry.getPageIndex()) {
                releasePageFromWrite(keyBucket, atomicOperation);

                keyBucketCacheEntry = loadPageForWrite(fileId, parentIndex, false);
              }

              keyBucket = new OPrefixBTreeBucket<>(keyBucketCacheEntry, keySerializer, valueSerializer, encryption);
            }

            releasePageFromWrite(keyBucket, atomicOperation);

            if (sizeDiff != 0) {
              updateSize(sizeDiff, atomicOperation);
            }
          } else if (updatedValue.isRemove()) {
            removeKey(atomicOperation, bucketSearchResult.getLastPathItem(), bucketSearchResult.itemIndex);
            releasePageFromWrite(keyBucket, atomicOperation);
          } else if (updatedValue.isNothing()) {
            releasePageFromWrite(keyBucket, atomicOperation);
          }
        } else {
          final OCacheEntry cacheEntry;
          boolean isNew = false;

          if (getFilledUpTo(nullBucketFileId) == 0) {
            cacheEntry = addPage(nullBucketFileId, false);
            isNew = true;
          } else {
            cacheEntry = loadPageForWrite(nullBucketFileId, 0, false);
          }

          int sizeDiff = 0;

          ONullBucket<V> nullBucket = null;
          try {
            nullBucket = new ONullBucket<>(cacheEntry);
            if (isNew) {
              nullBucket.init();
            }

            final byte[] oldRawValue = nullBucket.getRawValue(valueSerializer);
            final V oldValue = oldRawValue != null ? valueSerializer.deserializeNativeObject(oldRawValue, 0) : null;
            final OIndexUpdateAction<V> updatedValue = updater.update(oldValue, bonsaiFileId);
            if (updatedValue.isChange()) {
              final V value = updatedValue.getValue();
              final OSBTreeValue<V> treeValue = new OSBTreeValue<>(false, -1, value);

              if (validator != null) {
                final Object result = validator.validate(null, oldValue, value);
                if (result == OIndexEngine.Validator.IGNORE) {
                  return false;
                }
              }

              if (oldValue != null) {
                sizeDiff = -1;
              }

              nullBucket.setValue(treeValue, valueSerializer, oldRawValue != null ? oldRawValue.length : -1);
            } else if (updatedValue.isRemove()) {
              removeNullBucket(atomicOperation);
            } else //noinspection StatementWithEmptyBody
              if (updatedValue.isNothing()) {
              //Do Nothing
            }
          } finally {
            releasePageFromWrite(nullBucket, atomicOperation);
          }

          sizeDiff++;
          updateSize(sizeDiff, atomicOperation);
        }

        return true;
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  public void close() {
    acquireExclusiveLock();
    try {
      readCache.closeFile(fileId, true, writeCache);

      if (nullPointerSupport) {
        readCache.closeFile(nullBucketFileId, true, writeCache);
      }

    } finally {
      releaseExclusiveLock();
    }
  }

  public void clear() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        truncateFile(fileId);

        if (nullPointerSupport) {
          truncateFile(nullBucketFileId);
        }

        OCacheEntry cacheEntry = loadPageForWrite(fileId, ROOT_INDEX, false);
        if (cacheEntry == null) {
          cacheEntry = addPage(fileId, false);
        }

        OPrefixBTreeBucket<V> rootBucket = null;
        try {
          rootBucket = new OPrefixBTreeBucket<>(cacheEntry, true, keySerializer, valueSerializer, encryption, "");

          rootBucket.setTreeSize(0);

        } finally {
          releasePageFromWrite(rootBucket, atomicOperation);
        }
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  public void delete() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);

    try {
      acquireExclusiveLock();
      try {
        deleteFile(fileId, atomicOperation);

        if (nullPointerSupport) {
          deleteFile(nullBucketFileId, atomicOperation);
        }
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }

  }

  public void deleteWithoutLoad() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      acquireExclusiveLock();
      try {
        if (isFileExists(getFullName())) {
          final long fileId = openFile(getFullName());
          deleteFile(fileId, atomicOperation);
        }

        if (isFileExists(getName() + nullFileExtension)) {
          final long nullFileId = openFile(getName() + nullFileExtension);
          deleteFile(nullFileId, atomicOperation);
        }
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }

  }

  public void load(final String name, final OBinarySerializer<String> keySerializer, final OBinarySerializer<V> valueSerializer,
      final boolean nullPointerSupport, final OEncryption encryption) {
    acquireExclusiveLock();
    try {
      this.encryption = encryption;
      this.nullPointerSupport = nullPointerSupport;

      fileId = openFile(getFullName());
      if (nullPointerSupport) {
        nullBucketFileId = openFile(name + nullFileExtension);
      }

      this.keySerializer = keySerializer;
      this.valueSerializer = valueSerializer;
    } catch (final IOException e) {
      throw OException.wrapException(new OPrefixBTreeException("Exception during loading of sbtree " + name, this), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final OCacheEntry rootCacheEntry = loadPageForRead(fileId, ROOT_INDEX, false);
        try {
          final OPrefixBTreeBucket<V> rootBucket = new OPrefixBTreeBucket<>(rootCacheEntry, keySerializer, valueSerializer,
              encryption);
          return rootBucket.getTreeSize();
        } finally {
          releasePageFromRead(rootCacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException.wrapException(new OPrefixBTreeException("Error during retrieving of size of index " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public V remove(String key) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        final V removedValue;

        if (key != null) {
          key = keySerializer.preprocess(key, OType.STRING);

          final BucketSearchResult bucketSearchResult = findBucket(key);
          if (bucketSearchResult.itemIndex < 0) {
            return null;
          }

          removedValue = valueSerializer.deserializeNativeObject(
              removeKey(atomicOperation, bucketSearchResult.getLastPathItem(), bucketSearchResult.itemIndex), 0);
        } else {
          if (getFilledUpTo(nullBucketFileId) == 0) {
            return null;
          }

          removedValue = removeNullBucket(atomicOperation);
        }
        return removedValue;
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  private V removeNullBucket(final OAtomicOperation atomicOperation) throws IOException {
    V removedValue;
    ONullBucket<V> nullBucket = null;
    final OCacheEntry nullCacheEntry = loadPageForWrite(nullBucketFileId, 0, false);
    try {
      nullBucket = new ONullBucket<>(nullCacheEntry);
      final byte[] treeValue = nullBucket.getRawValue(valueSerializer);

      if (treeValue != null) {
        removedValue = valueSerializer.deserializeNativeObject(treeValue, 0);
        nullBucket.removeValue(treeValue.length);
      } else {
        removedValue = null;
      }
    } finally {
      releasePageFromWrite(nullBucket, atomicOperation);
    }

    if (removedValue != null) {
      updateSize(-1, atomicOperation);
    }
    return removedValue;
  }

  private byte[] removeKey(final OAtomicOperation atomicOperation, final long pageIndex, final int itemIndex) throws IOException {
    byte[] removedValue;
    OPrefixBTreeBucket<V> keyBucket = null;
    final OCacheEntry keyBucketCacheEntry = loadPageForWrite(fileId, pageIndex, false);
    try {
      keyBucket = new OPrefixBTreeBucket<>(keyBucketCacheEntry, keySerializer, valueSerializer, encryption);

      removedValue = keyBucket.getRawValue(itemIndex);
      keyBucket.remove(itemIndex);
      updateSize(-1, atomicOperation);
    } finally {
      releasePageFromWrite(keyBucket, atomicOperation);
    }
    return removedValue;
  }

  public OSBTreeCursor<String, V> iterateEntriesMinor(final String key, final boolean inclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (!ascSortOrder) {
          return iterateEntriesMinorDesc(key, inclusive);
        }

        return iterateEntriesMinorAsc(key, inclusive);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OSBTreeCursor<String, V> iterateEntriesMajor(final String key, final boolean inclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return iterateEntriesMajorAsc(key, inclusive);
        }

        return iterateEntriesMajorDesc(key, inclusive);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public String firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final BucketSearchResult searchResult = firstItem();
        if (searchResult == null) {
          return null;
        }

        final OCacheEntry cacheEntry = loadPageForRead(fileId, searchResult.getLastPathItem(), false);
        try {
          final OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
          return bucket.getBucketPrefix() + bucket.getKeyWithoutPrefix(searchResult.itemIndex);
        } finally {
          releasePageFromRead(cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OPrefixBTreeException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public String lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final BucketSearchResult searchResult = lastItem();
        if (searchResult == null) {
          return null;
        }

        final OCacheEntry cacheEntry = loadPageForRead(fileId, searchResult.getLastPathItem(), false);
        try {
          final OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
          return bucket.getBucketPrefix() + bucket.getKeyWithoutPrefix(searchResult.itemIndex);
        } finally {
          releasePageFromRead(cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new OPrefixBTreeException("Error during finding last key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OSBTreeKeyCursor<String> keyCursor() {
    return new OSBTreeFullKeyCursor();
  }

  public OSBTreeCursor<String, V> iterateEntriesBetween(final String keyFrom, final boolean fromInclusive, final String keyTo,
      final boolean toInclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive);
        } else {
          return iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void flush() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        writeCache.flush();
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this SB-tree.
   */
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  private void checkNullSupport(final String key) {
    if (key == null && !nullPointerSupport) {
      throw new OPrefixBTreeException("Null keys are not supported.", this);
    }
  }

  private void updateSize(final long diffSize, final OAtomicOperation atomicOperation) throws IOException {
    OPrefixBTreeBucket<V> rootBucket = null;
    final OCacheEntry rootCacheEntry = loadPageForWrite(fileId, ROOT_INDEX, false);
    try {
      rootBucket = new OPrefixBTreeBucket<>(rootCacheEntry, keySerializer, valueSerializer, encryption);
      rootBucket.setTreeSize(rootBucket.getTreeSize() + diffSize);
    } finally {
      releasePageFromWrite(rootBucket, atomicOperation);
    }
  }

  private OSBTreeCursor<String, V> iterateEntriesMinorDesc(String key, final boolean inclusive) {
    key = keySerializer.preprocess(key, OType.STRING);
    return new OSBTreeCursorBackward(null, key, false, inclusive);
  }

  private OSBTreeCursor<String, V> iterateEntriesMinorAsc(String key, final boolean inclusive) {
    acquireSharedLock();
    try {
      key = keySerializer.preprocess(key, OType.STRING);
      return new OSBTreeCursorForward(null, key, false, inclusive);
    } finally {
      releaseSharedLock();
    }

  }

  private OSBTreeCursor<String, V> iterateEntriesMajorAsc(String key, final boolean inclusive) {
    key = keySerializer.preprocess(key, OType.STRING);
    return new OSBTreeCursorForward(key, null, inclusive, false);
  }

  private OSBTreeCursor<String, V> iterateEntriesMajorDesc(String key, final boolean inclusive) {
    key = keySerializer.preprocess(key, OType.STRING);
    return new OSBTreeCursorBackward(key, null, inclusive, false);
  }

  private BucketSearchResult firstItem() throws IOException {
    final LinkedList<PagePathItemUnit> path = new LinkedList<>();

    long bucketIndex = ROOT_INDEX;

    //depth first iteration which iterates till not find first item in not empty bucket
    OCacheEntry cacheEntry = loadPageForRead(fileId, bucketIndex, false);
    int itemIndex = 0;
    try {
      OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);

      while (true) {
        if (!bucket.isLeaf()) {
          if (bucket.isEmpty() || itemIndex > bucket.size()) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = Math.abs(pagePathItemUnit.itemIndex);
            } else {
              return null;
            }
          } else {
            if (itemIndex < bucket.size()) {
              path.add(new PagePathItemUnit(bucketIndex, -(itemIndex + 1)));

              bucketIndex = bucket.getLeft(itemIndex);
            } else {
              path.add(new PagePathItemUnit(bucketIndex, itemIndex));

              bucketIndex = bucket.getRight(itemIndex - 1);
            }

            //jump to next page and start from first item
            itemIndex = 0;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = Math.abs(pagePathItemUnit.itemIndex);
            } else {
              return null;
            }
          } else {
            final ArrayList<Long> resultPath = new ArrayList<>(path.size() + 1);
            final ArrayList<Integer> items = new ArrayList<>(path.size() + 1);

            for (final PagePathItemUnit pathItemUnit : path) {
              resultPath.add(pathItemUnit.pageIndex);
              items.add(pathItemUnit.itemIndex);
            }

            resultPath.add(bucketIndex);
            items.add(1);

            return new BucketSearchResult(0, resultPath, items);
          }
        }

        releasePageFromRead(cacheEntry);

        cacheEntry = loadPageForRead(fileId, bucketIndex, false);

        bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
      }
    } finally {
      releasePageFromRead(cacheEntry);
    }
  }

  private BucketSearchResult lastItem() throws IOException {
    final LinkedList<PagePathItemUnit> path = new LinkedList<>();

    long bucketIndex = ROOT_INDEX;

    OCacheEntry cacheEntry = loadPageForRead(fileId, bucketIndex, false);

    OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);

    int itemIndex = bucket.size() - 1;
    try {
      while (true) {
        if (!bucket.isLeaf()) {
          if (itemIndex < -1) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = Math.abs(pagePathItemUnit.itemIndex) - 2;
            } else {
              return null;
            }
          } else {
            if (itemIndex > -1) {
              path.add(new PagePathItemUnit(bucketIndex, itemIndex + 1));

              bucketIndex = bucket.getRight(itemIndex);

            } else {
              path.add(new PagePathItemUnit(bucketIndex, -1));

              bucketIndex = bucket.getLeft(0);
            }

            itemIndex = OPrefixBTreeBucket.MAX_PAGE_SIZE_BYTES + 1;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = Math.abs(pagePathItemUnit.itemIndex) - 2;
            } else {
              return null;
            }
          } else {
            final ArrayList<Long> resultPath = new ArrayList<>(path.size() + 1);
            final ArrayList<Integer> items = new ArrayList<>(path.size() + 1);

            for (final PagePathItemUnit pathItemUnit : path) {
              resultPath.add(pathItemUnit.pageIndex);
              items.add(pathItemUnit.itemIndex);
            }

            resultPath.add(bucketIndex);

            final int lastItem = bucket.size();
            items.add(lastItem);

            return new BucketSearchResult(lastItem - 1, resultPath, items);
          }
        }

        releasePageFromRead(cacheEntry);

        cacheEntry = loadPageForRead(fileId, bucketIndex, false);

        bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
        if (itemIndex == OPrefixBTreeBucket.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      releasePageFromRead(cacheEntry);
    }
  }

  private OSBTreeCursor<String, V> iterateEntriesBetweenAscOrder(String keyFrom, final boolean fromInclusive, String keyTo,
      final boolean toInclusive) {
    keyFrom = keySerializer.preprocess(keyFrom, OType.STRING);
    keyTo = keySerializer.preprocess(keyTo, OType.STRING);

    return new OSBTreeCursorForward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private OSBTreeCursor<String, V> iterateEntriesBetweenDescOrder(String keyFrom, final boolean fromInclusive, String keyTo,
      final boolean toInclusive) {
    keyFrom = keySerializer.preprocess(keyFrom, OType.STRING);
    keyTo = keySerializer.preprocess(keyTo, OType.STRING);

    return new OSBTreeCursorBackward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private BucketUpdateSearchResult splitBucket(final OPrefixBTreeBucket<V> bucketToSplit, final OCacheEntry bucketToSplitEntry,
      final List<Long> path, final List<String> leftBoundaries, final List<String> rightBoundaries, final int keyIndex,
      final String keyToInsert, final OAtomicOperation atomicOperation)
      throws IOException {
    final long pageIndex = path.get(path.size() - 1);
    final boolean splitLeaf = bucketToSplit.isLeaf();
    final int bucketSize = bucketToSplit.size();

    int indexToSplit = bucketSize >>> 1;

    final String separator;

    if (bucketSize < 100) {
      final String separationKey;
      final String separationKeyRight = bucketToSplit.getKeyWithoutPrefix(indexToSplit);

      if (splitLeaf) {
        if (indexToSplit > 0) {
          final String separationKeyLeft = bucketToSplit.getKeyWithoutPrefix(indexToSplit - 1);
          separationKey = bucketToSplit.getBucketPrefix() + findMinSeparationKey(separationKeyLeft, separationKeyRight);
        } else {
          separationKey = bucketToSplit.getBucketPrefix() + separationKeyRight;
        }
      } else {
        separationKey = bucketToSplit.getBucketPrefix() + separationKeyRight;
      }

      separator = separationKey;
    } else {
      final int diff = ((int) (bucketSize * 0.1)) / 2;
      final int startSeparationIndex = indexToSplit - diff;
      final int endSeparationIndex = indexToSplit + diff + 1;

      if (splitLeaf) {
        String prevSeparationKey = bucketToSplit.getKeyWithoutPrefix(startSeparationIndex - 1);
        String separationKey = bucketToSplit.getKeyWithoutPrefix(startSeparationIndex);
        String minSeparationKey = findMinSeparationKey(prevSeparationKey, separationKey);

        String absMinKey = minSeparationKey;
        int absMinIndex = startSeparationIndex;

        for (int i = startSeparationIndex + 1; i < endSeparationIndex; i++) {
          separationKey = bucketToSplit.getKeyWithoutPrefix(i);
          minSeparationKey = findMinSeparationKey(prevSeparationKey, separationKey);

          if (absMinKey.length() > minSeparationKey.length()) {
            absMinKey = separationKey;
            absMinIndex = i;
          }

          prevSeparationKey = separationKey;
        }

        separator = bucketToSplit.getBucketPrefix() + absMinKey;
        indexToSplit = absMinIndex;
      } else {
        String absMinKey = null;
        int absMinIndex = -1;

        for (int i = startSeparationIndex; i < endSeparationIndex; i++) {
          final String separationKey = bucketToSplit.getKeyWithoutPrefix(i);
          if (absMinKey == null || absMinKey.length() > separationKey.length()) {
            absMinKey = separationKey;
            absMinIndex = i;
          }
        }

        separator = bucketToSplit.getBucketPrefix() + absMinKey;
        indexToSplit = absMinIndex;
      }

    }

    final int startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

    if (pageIndex != ROOT_INDEX) {
      final List<OPrefixBTreeBucket.SBTreeEntry<V>> rightEntries = new ArrayList<>(indexToSplit);

      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getEntry(i));
      }

      return splitNonRootBucket(path, leftBoundaries, rightBoundaries, keyIndex, keyToInsert, pageIndex, bucketToSplit, splitLeaf,
          indexToSplit, separator, rightEntries, atomicOperation);
    } else {
      final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getRawEntry(i));
      }

      return splitRootBucket(path, keyIndex, keyToInsert, bucketToSplitEntry, bucketToSplit, splitLeaf, indexToSplit, separator,
          rightEntries, atomicOperation);
    }
  }

  private static String findMinSeparationKey(final String keyLeft, final String keyRight) {
    final int minLen = Math.min(keyLeft.length(), keyRight.length());
    for (int i = 0; i < minLen; i++) {
      if (keyLeft.charAt(i) != keyRight.charAt(i)) {
        return keyRight.substring(0, i + 1);
      }
    }

    if (keyRight.length() == minLen) {
      return keyRight;
    }

    return keyRight.substring(0, minLen + 1);
  }

  private BucketUpdateSearchResult splitNonRootBucket(List<Long> path, List<String> leftBoundaries, List<String> rightBoundaries,
      final int keyIndex, final String keyToInsert, final long pageIndex, final OPrefixBTreeBucket<V> bucketToSplit,
      final boolean splitLeaf, final int indexToSplit, final String separationKey,
      final List<OPrefixBTreeBucket.SBTreeEntry<V>> rightEntries, final OAtomicOperation atomicOperation)
      throws IOException {

    final OCacheEntry rightBucketEntry = addPage(fileId, false);

    final String leftBoundary = leftBoundaries.get(leftBoundaries.size() - 2);
    final String rightBoundary = rightBoundaries.get(rightBoundaries.size() - 2);

    final String rightBucketPrefix;
    final String leftBucketPrefix;

    if (leftBoundary != null) {
      leftBucketPrefix = findCommonPrefix(leftBoundary, separationKey);
    } else {
      leftBucketPrefix = "";
    }

    if (rightBoundary != null) {
      rightBucketPrefix = findCommonPrefix(separationKey, rightBoundary);
    } else {
      rightBucketPrefix = "";
    }

    OPrefixBTreeBucket<V> newRightBucket = null;
    try {
      newRightBucket = new OPrefixBTreeBucket<>(rightBucketEntry, splitLeaf, keySerializer, valueSerializer, encryption,
          rightBucketPrefix);

      newRightBucket.addAllWithPrefix(rightEntries, rightBucketPrefix);
      bucketToSplit.shrinkWithPrefix(indexToSplit, leftBucketPrefix);

      OPrefixBTreeBucket<V> parentBucket = null;
      long parentIndex = path.get(path.size() - 2);
      OCacheEntry parentCacheEntry = loadPageForWrite(fileId, parentIndex, false);
      try {
        parentBucket = new OPrefixBTreeBucket<>(parentCacheEntry, keySerializer, valueSerializer, encryption);
        final OPrefixBTreeBucket.SBTreeEntry<V> parentEntry = new OPrefixBTreeBucket.SBTreeEntry<>((int) pageIndex,
            (int) rightBucketEntry.getPageIndex(), separationKey, null);

        int insertionIndex = parentBucket.find(separationKey);
        assert insertionIndex < 0;

        insertionIndex = -insertionIndex - 1;

        while (!parentBucket.addEntry(insertionIndex, parentEntry, true)) {
          final BucketUpdateSearchResult bucketSearchResult = splitBucket(parentBucket, parentCacheEntry,
              path.subList(0, path.size() - 1), leftBoundaries.subList(0, leftBoundaries.size() - 1),
              rightBoundaries.subList(0, rightBoundaries.size() - 1), insertionIndex, separationKey, atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();

          path = bucketSearchResult.path;
          leftBoundaries = bucketSearchResult.leftBoundaries;
          rightBoundaries = bucketSearchResult.rightBoundaries;

          insertionIndex = bucketSearchResult.itemIndex;

          //add placeholder which will be removed later, just for consistency of algorithm
          path.add(null);
          leftBoundaries.add(null);
          rightBoundaries.add(null);

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            releasePageFromWrite(parentBucket, atomicOperation);

            parentCacheEntry = loadPageForWrite(fileId, parentIndex, false);
          }

          parentBucket = new OPrefixBTreeBucket<>(parentCacheEntry, keySerializer, valueSerializer, encryption);
        }

        final ArrayList<Long> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));
        final ArrayList<String> resultLeftBoundaries = new ArrayList<>(leftBoundaries.subList(0, leftBoundaries.size() - 1));
        final ArrayList<String> resultRightBoundaries = new ArrayList<>(rightBoundaries.subList(0, rightBoundaries.size() - 1));

        if (comparator.compare(keyToInsert, separationKey) < 0) {
          resultPath.add(pageIndex);

          resultRightBoundaries.add(separationKey);
          if (insertionIndex > 0) {
            resultLeftBoundaries.add(parentBucket.getBucketPrefix() + parentBucket.getKeyWithoutPrefix(insertionIndex - 1));
          } else {
            resultLeftBoundaries.add(null);
          }

          return new BucketUpdateSearchResult(keyIndex, resultPath, resultLeftBoundaries, resultRightBoundaries);
        }

        resultPath.add(rightBucketEntry.getPageIndex());
        resultLeftBoundaries.add(separationKey);

        if (insertionIndex < parentBucket.size() - 1) {
          resultRightBoundaries.add(parentBucket.getBucketPrefix() + parentBucket.getKeyWithoutPrefix(insertionIndex + 1));
        } else {
          resultRightBoundaries.add(null);
        }

        return new BucketUpdateSearchResult(splitLeaf ? keyIndex - indexToSplit : keyIndex - indexToSplit - 1, resultPath,
            resultLeftBoundaries, resultRightBoundaries);
      } finally {
        releasePageFromWrite(parentBucket, atomicOperation);
      }

    } finally {
      releasePageFromWrite(newRightBucket, atomicOperation);
    }
  }

  private static String findCommonPrefix(final String keyOne, final String keyTwo) {
    final int commonLen = Math.min(keyOne.length(), keyTwo.length());

    int commonIndex = -1;
    String suffix = "";

    for (int i = 0; i < commonLen; i++) {
      final int res = keyOne.charAt(i) - keyTwo.charAt(i);

      if (res == 0) {
        commonIndex = i;
      } else {
        if (res == -1 && i == commonLen - 1 && commonLen == keyTwo.length()) {
          suffix = String.valueOf(keyOne.charAt(i));
        }

        break;
      }

    }

    if (commonIndex == -1) {
      return "";
    }

    return keyOne.substring(0, commonIndex + 1) + suffix;
  }

  private BucketUpdateSearchResult splitRootBucket(final List<Long> path, final int keyIndex, final String keyToInsert,
      final OCacheEntry bucketEntry, OPrefixBTreeBucket<V> bucketToSplit, final boolean splitLeaf, final int indexToSplit,
      final String separationKey, final List<byte[]> rightEntries, final OAtomicOperation atomicOperation) throws IOException {
    final long treeSize = bucketToSplit.getTreeSize();

    final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

    for (int i = 0; i < indexToSplit; i++) {
      leftEntries.add(bucketToSplit.getRawEntry(i));
    }

    OPrefixBTreeBucket<V> newLeftBucket = null;
    final OCacheEntry leftBucketEntry = addPage(fileId, false);
    final OCacheEntry rightBucketEntry = addPage(fileId, false);
    try {
      newLeftBucket = new OPrefixBTreeBucket<>(leftBucketEntry, splitLeaf, keySerializer, valueSerializer, encryption, "");
      newLeftBucket.addAllNoPrefix(leftEntries);
    } finally {
      releasePageFromWrite(newLeftBucket, atomicOperation);
    }

    OPrefixBTreeBucket<V> newRightBucket = null;
    try {
      newRightBucket = new OPrefixBTreeBucket<>(rightBucketEntry, splitLeaf, keySerializer, valueSerializer, encryption, "");
      newRightBucket.addAllNoPrefix(rightEntries);
    } finally {
      releasePageFromWrite(newRightBucket, atomicOperation);
    }

    bucketToSplit = new OPrefixBTreeBucket<>(bucketEntry, false, keySerializer, valueSerializer, encryption, "");

    bucketToSplit.setTreeSize(treeSize);

    bucketToSplit.addEntry(0,
        new OPrefixBTreeBucket.SBTreeEntry<>((int) leftBucketEntry.getPageIndex(), (int) rightBucketEntry.getPageIndex(),
            separationKey, null), true);

    final ArrayList<Long> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));

    final ArrayList<String> resultLeftBoundaries = new ArrayList<>(8);
    final ArrayList<String> resultRightBoundaries = new ArrayList<>(8);

    resultLeftBoundaries.add(null);
    resultLeftBoundaries.add(null);

    resultRightBoundaries.add(null);
    resultRightBoundaries.add(null);

    if (comparator.compare(keyToInsert, separationKey) < 0) {
      resultPath.add(leftBucketEntry.getPageIndex());
      return new BucketUpdateSearchResult(keyIndex, resultPath, resultLeftBoundaries, resultRightBoundaries);
    }

    resultPath.add(rightBucketEntry.getPageIndex());

    //in left bucket we have indexToSplit + 1 buckets, so if items in next bucket
    return new BucketUpdateSearchResult(splitLeaf ? keyIndex - indexToSplit : keyIndex - indexToSplit - 1, resultPath,
        resultLeftBoundaries, resultRightBoundaries);
  }

  private BucketUpdateSearchResult findBucketForUpdate(final String key) throws IOException {
    long pageIndex = ROOT_INDEX;
    final ArrayList<Long> path = new ArrayList<>(8);
    final ArrayList<String> leftBoundaries = new ArrayList<>(8);
    final ArrayList<String> rightBoundaries = new ArrayList<>(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new OPrefixBTreeException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state."
                + " You should rebuild index related to given query. Key = " + key, this);
      }

      path.add(pageIndex);

      final OCacheEntry bucketEntry = loadPageForRead(fileId, pageIndex, false);
      final OPrefixBTreeBucket.SBTreeEntry<V> entry;

      final int entryIndex;
      try {
        final OPrefixBTreeBucket<V> keyBucket = new OPrefixBTreeBucket<>(bucketEntry, keySerializer, valueSerializer, encryption);

        final int index = keyBucket.find(key);
        if (keyBucket.isLeaf()) {
          leftBoundaries.add(null);
          rightBoundaries.add(null);

          return new BucketUpdateSearchResult(index, path, leftBoundaries, rightBoundaries);
        }

        boolean right = true;

        if (index >= 0) {
          entryIndex = index;
        } else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            entryIndex = insertionIndex - 1;
          } else {
            entryIndex = insertionIndex;
            right = false;
          }
        }

        entry = keyBucket.getEntry(entryIndex);
        if (right) {
          pageIndex = entry.rightChild;
          leftBoundaries.add(entry.key);

          if (entryIndex < keyBucket.size() - 1) {
            rightBoundaries.add(keyBucket.getBucketPrefix() + keyBucket.getKeyWithoutPrefix(entryIndex + 1));
          } else {
            rightBoundaries.add(null);
          }
        } else {
          pageIndex = entry.leftChild;
          rightBoundaries.add(entry.key);

          if (entryIndex > 0) {
            leftBoundaries.add(keyBucket.getBucketPrefix() + keyBucket.getKeyWithoutPrefix(entryIndex - 1));
          } else {
            leftBoundaries.add(null);
          }
        }
      } finally {
        releasePageFromRead(bucketEntry);
      }
    }
  }

  private BucketSearchResult findBucket(final String key) throws IOException {
    long pageIndex = ROOT_INDEX;
    final ArrayList<Long> path = new ArrayList<>(8);
    final ArrayList<Integer> items = new ArrayList<>(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new OPrefixBTreeException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state. You should rebuild index related to given query.",
            this);
      }

      path.add(pageIndex);
      final OCacheEntry bucketEntry = loadPageForRead(fileId, pageIndex, false);
      final int entryIndex;
      try {
        final OPrefixBTreeBucket<V> keyBucket = new OPrefixBTreeBucket<>(bucketEntry, keySerializer, valueSerializer, encryption);

        final int index = keyBucket.find(key);
        final int itemIndex;

        if (index >= 0) {
          entryIndex = index;

          itemIndex = entryIndex + 1;
        } else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            entryIndex = insertionIndex - 1;
            itemIndex = entryIndex + 1;
          } else {
            entryIndex = insertionIndex;
            itemIndex = -(entryIndex + 1);
          }
        }

        if (keyBucket.isLeaf()) {
          items.add(index);
          return new BucketSearchResult(index, path, items);
        } else {
          items.add(itemIndex);
        }

        if (itemIndex > 0) {
          pageIndex = keyBucket.getRight(entryIndex);
        } else {
          pageIndex = keyBucket.getLeft(entryIndex);
        }
      } finally {
        releasePageFromRead(bucketEntry);
      }
    }
  }

  private V readValue(final OSBTreeValue<V> sbTreeValue) throws IOException {
    if (!sbTreeValue.isLink()) {
      return sbTreeValue.getValue();
    }

    OCacheEntry cacheEntry = loadPageForRead(fileId, sbTreeValue.getLink(), false);

    OSBTreeValuePage valuePage = new OSBTreeValuePage(cacheEntry, false);

    final int totalSize = valuePage.getSize();
    int currentSize = 0;
    final byte[] value = new byte[totalSize];

    while (currentSize < totalSize) {
      currentSize = valuePage.readBinaryContent(value, currentSize);

      final long nextPage = valuePage.getNextPage();
      if (nextPage >= 0) {
        releasePageFromRead(cacheEntry);

        cacheEntry = loadPageForRead(fileId, nextPage, false);

        valuePage = new OSBTreeValuePage(cacheEntry, false);
      }
    }

    releasePageFromRead(cacheEntry);

    return valueSerializer.deserializeNativeObject(value, 0);
  }

  private Map.Entry<String, V> convertToMapEntry(final OPrefixBTreeBucket.SBTreeEntry<V> treeEntry) throws IOException {
    final String key = treeEntry.key;
    final V value = readValue(treeEntry.value);

    return new Map.Entry<String, V>() {
      @Override
      public String getKey() {
        return key;
      }

      @Override
      public V getValue() {
        return value;
      }

      @Override
      public V setValue(final V value) {
        throw new UnsupportedOperationException("setValue");
      }
    };
  }

  public interface OSBTreeCursor<String, V> {
    Map.Entry<String, V> next(int prefetchSize);
  }

  public interface OSBTreeKeyCursor<String> {
    String next(int prefetchSize);
  }

  private static final class BucketUpdateSearchResult {
    private final int        itemIndex;
    private final List<Long> path;

    private final List<String> leftBoundaries;
    private final List<String> rightBoundaries;

    private BucketUpdateSearchResult(final int itemIndex, final List<Long> path, final List<String> leftBoundaries,
        final List<String> rightBoundaries) {
      this.itemIndex = itemIndex;
      this.path = path;
      this.leftBoundaries = leftBoundaries;
      this.rightBoundaries = rightBoundaries;
    }

    final long getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class BucketSearchResult {
    private final int           itemIndex;
    private final List<Long>    path;
    private final List<Integer> items;

    private BucketSearchResult(final int itemIndex, final List<Long> path, final List<Integer> items) {
      this.itemIndex = itemIndex;
      this.path = path;
      this.items = items;
    }

    final long getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {
    private final long pageIndex;
    private final int  itemIndex;

    private PagePathItemUnit(final long pageIndex, final int itemIndex) {
      this.pageIndex = pageIndex;
      this.itemIndex = itemIndex;
    }
  }

  public final class OSBTreeFullKeyCursor implements OSBTreeKeyCursor<String> {
    private List<String>     keysCache    = new ArrayList<>();
    private Iterator<String> keysIterator = new OEmptyIterator<>();

    private String lastKey;

    OSBTreeFullKeyCursor() {
    }

    @Override
    public String next(int prefetchSize) {
      if (keysIterator == null) {
        return null;
      }

      if (keysIterator.hasNext()) {
        lastKey = keysIterator.next();
        return lastKey;
      }

      keysCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      if (prefetchSize == 0) {
        prefetchSize = 1;
      }

      atomicOperationsManager.acquireReadLock(OPrefixBTree.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (lastKey == null) {
            bucketSearchResult = firstItem();
          } else {
            bucketSearchResult = findBucket(lastKey);
          }

          if (bucketSearchResult == null) {
            return null;
          }

          int itemIndex;
          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = lastKey == null ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 1;
          }

          final long pageIndex = bucketSearchResult.getLastPathItem();

          final LinkedList<ORawPair<Long, Integer>> currentPath = new LinkedList<>();
          for (int i = 0; i < bucketSearchResult.path.size(); i++) {
            currentPath.add(new ORawPair<>(bucketSearchResult.path.get(i), bucketSearchResult.items.get(i)));
          }

          OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false);
          try {
            OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
            while (keysCache.size() < prefetchSize) {
              while (itemIndex >= bucket.size()) {
                releasePageFromRead(cacheEntry);
                cacheEntry = null;

                currentPath.removeLast();//remove leaf
                ORawPair<Long, Integer> pathItem = currentPath.peekLast();

                //go up into the path till will find first not visited branch
                while (pathItem != null) {
                  final long pathIndex = pathItem.getFirst();
                  final int pathItemIndex = pathItem.getSecond();

                  final OCacheEntry parentBucketCacheEntry = loadPageForRead(fileId, pathIndex, false);
                  try {
                    final OPrefixBTreeBucket<V> parentBucket = new OPrefixBTreeBucket<>(parentBucketCacheEntry, keySerializer,
                        valueSerializer, encryption);

                    int cItemIndex = Math.abs(pathItemIndex) - 1;
                    if (pathItemIndex > 0) {
                      cItemIndex++;
                    }

                    currentPath.removeLast();//remove current branch bucket
                    if (cItemIndex >= parentBucket.size()) {
                      pathItem = currentPath.peekLast();
                    } else {
                      pathItem = new ORawPair<>(pathIndex, cItemIndex + 1);
                      currentPath.add(pathItem);

                      cacheEntry = loadPageForRead(fileId, parentBucket.getRight(cItemIndex), false);

                      bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
                      break;
                    }
                  } finally {
                    releasePageFromRead(parentBucketCacheEntry);
                  }
                }

                if (pathItem == null) {
                  if (keysCache.isEmpty()) {
                    keysCache = null;
                    keysIterator = null;

                    return null;
                  }

                  keysIterator = keysCache.iterator();

                  lastKey = keysIterator.next();
                  return lastKey;
                }

                //go by the left branch till leaf page
                while (true) {
                  if (bucket.isLeaf()) {
                    currentPath.add(new ORawPair<>(cacheEntry.getPageIndex(), 0));
                    itemIndex = 0;
                    break;
                  } else {
                    currentPath.add(new ORawPair<>(cacheEntry.getPageIndex(), -1));

                    final long childIndex = bucket.getLeft(0);

                    releasePageFromRead(cacheEntry);

                    cacheEntry = loadPageForRead(fileId, childIndex, false);
                    bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
                  }
                }
              }

              final Map.Entry<String, V> entry = convertToMapEntry(bucket.getEntry(itemIndex));
              itemIndex++;

              keysCache.add(entry.getKey());
            }
          } finally {
            if (cacheEntry != null) {
              releasePageFromRead(cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw OException.wrapException(new OPrefixBTreeException("Error during element iteration", OPrefixBTree.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(OPrefixBTree.this);
      }

      if (keysCache.isEmpty()) {
        keysCache = null;
        keysIterator = null;

        return null;
      }

      keysIterator = keysCache.iterator();
      return keysIterator.next();
    }
  }

  private final class OSBTreeCursorForward implements OSBTreeCursor<String, V> {
    private       String  fromKey;
    private final String  toKey;
    private       boolean fromKeyInclusive;
    private final boolean toKeyInclusive;

    private final List<Map.Entry<String, V>>     dataCache         = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private       Iterator<Map.Entry<String, V>> dataCacheIterator = OEmptyMapEntryIterator.INSTANCE;

    private OSBTreeCursorForward(final String fromKey, final String toKey, final boolean fromKeyInclusive,
        final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (fromKey == null) {
        this.fromKeyInclusive = true;
      }
    }

    public Map.Entry<String, V> next(int prefetchSize) {
      if (dataCacheIterator == null) {
        return null;
      }

      if (dataCacheIterator.hasNext()) {
        final Map.Entry<String, V> entry = dataCacheIterator.next();

        fromKey = entry.getKey();
        fromKeyInclusive = false;

        return entry;
      }

      dataCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      if (prefetchSize == 0) {
        prefetchSize = 1;
      }

      atomicOperationsManager.acquireReadLock(OPrefixBTree.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (fromKey != null) {
            bucketSearchResult = findBucket(fromKey);
          } else {
            bucketSearchResult = firstItem();
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return null;
          }

          final long pageIndex = bucketSearchResult.getLastPathItem();
          int itemIndex;

          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = fromKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 1;
          }

          final LinkedList<ORawPair<Long, Integer>> currentPath = new LinkedList<>();
          for (int i = 0; i < bucketSearchResult.path.size(); i++) {
            currentPath.add(new ORawPair<>(bucketSearchResult.path.get(i), bucketSearchResult.items.get(i)));
          }

          OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false);
          try {
            OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
            while (dataCache.size() < prefetchSize) {
              while (itemIndex >= bucket.size()) {
                releasePageFromRead(cacheEntry);
                cacheEntry = null;

                currentPath.removeLast();//remove leaf
                ORawPair<Long, Integer> pathItem = currentPath.peekLast();

                //go up into the path till will find first not visited branch
                while (pathItem != null) {
                  final long pathIndex = pathItem.getFirst();
                  final int pathItemIndex = pathItem.getSecond();

                  final OCacheEntry parentBucketCacheEntry = loadPageForRead(fileId, pathIndex, false);
                  try {
                    final OPrefixBTreeBucket<V> parentBucket = new OPrefixBTreeBucket<>(parentBucketCacheEntry, keySerializer,
                        valueSerializer, encryption);

                    int cItemIndex = Math.abs(pathItemIndex) - 1;
                    if (pathItemIndex > 0) {
                      cItemIndex++;
                    }

                    currentPath.removeLast();//remove current branch bucket
                    if (cItemIndex >= parentBucket.size()) {
                      pathItem = currentPath.peekLast();
                    } else {
                      pathItem = new ORawPair<>(pathIndex, cItemIndex + 1);
                      currentPath.add(pathItem);

                      cacheEntry = loadPageForRead(fileId, parentBucket.getRight(cItemIndex), false);
                      bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);

                      break;
                    }
                  } finally {
                    releasePageFromRead(parentBucketCacheEntry);
                  }
                }

                if (pathItem == null) {
                  if (dataCache.isEmpty()) {
                    dataCacheIterator = null;
                    return null;
                  }

                  dataCacheIterator = dataCache.iterator();

                  final Map.Entry<String, V> entry = dataCacheIterator.next();

                  fromKey = entry.getKey();
                  fromKeyInclusive = false;

                  return entry;
                }

                //go by the left branch till leaf page
                while (true) {
                  if (bucket.isLeaf()) {
                    currentPath.add(new ORawPair<>(cacheEntry.getPageIndex(), 0));
                    itemIndex = 0;
                    break;
                  } else {
                    currentPath.add(new ORawPair<>(cacheEntry.getPageIndex(), -1));
                    final long childIndex = bucket.getLeft(0);

                    releasePageFromRead(cacheEntry);

                    cacheEntry = loadPageForRead(fileId, childIndex, false);
                    bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
                  }
                }
              }

              final Map.Entry<String, V> entry = convertToMapEntry(bucket.getEntry(itemIndex));
              itemIndex++;

              if (fromKey != null && (fromKeyInclusive ?
                  comparator.compare(entry.getKey(), fromKey) < 0 :
                  comparator.compare(entry.getKey(), fromKey) <= 0)) {
                continue;
              }

              if (toKey != null && (toKeyInclusive ?
                  comparator.compare(entry.getKey(), toKey) > 0 :
                  comparator.compare(entry.getKey(), toKey) >= 0)) {
                break;
              }

              dataCache.add(entry);
            }
          } finally {
            if (cacheEntry != null) {
              releasePageFromRead(cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw OException.wrapException(new OPrefixBTreeException("Error during element iteration", OPrefixBTree.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(OPrefixBTree.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return null;
      }

      dataCacheIterator = dataCache.iterator();

      final Map.Entry<String, V> entry = dataCacheIterator.next();

      fromKey = entry.getKey();
      fromKeyInclusive = false;

      return entry;
    }
  }

  private final class OSBTreeCursorBackward implements OSBTreeCursor<String, V> {
    private final String  fromKey;
    private       String  toKey;
    private final boolean fromKeyInclusive;
    private       boolean toKeyInclusive;

    private final List<Map.Entry<String, V>>     dataCache         = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private       Iterator<Map.Entry<String, V>> dataCacheIterator = OEmptyMapEntryIterator.INSTANCE;

    private OSBTreeCursorBackward(final String fromKey, final String toKey, final boolean fromKeyInclusive,
        final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (toKey == null) {
        this.toKeyInclusive = true;
      }

    }

    public Map.Entry<String, V> next(int prefetchSize) {
      if (dataCacheIterator == null) {
        return null;
      }

      if (dataCacheIterator.hasNext()) {
        final Map.Entry<String, V> entry = dataCacheIterator.next();
        toKey = entry.getKey();

        toKeyInclusive = false;
        return entry;
      }

      dataCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      atomicOperationsManager.acquireReadLock(OPrefixBTree.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (toKey != null) {
            bucketSearchResult = findBucket(toKey);
          } else {
            bucketSearchResult = lastItem();
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return null;
          }

          final long pageIndex = bucketSearchResult.getLastPathItem();

          int itemIndex;
          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = toKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex - 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 2;
          }

          final LinkedList<ORawPair<Long, Integer>> currentPath = new LinkedList<>();
          for (int i = 0; i < bucketSearchResult.path.size(); i++) {
            currentPath.add(new ORawPair<>(bucketSearchResult.path.get(i), bucketSearchResult.items.get(i)));
          }

          OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false);
          try {
            OPrefixBTreeBucket<V> bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
            while (dataCache.size() < prefetchSize) {
              while (itemIndex < 0) {
                releasePageFromRead(cacheEntry);
                cacheEntry = null;

                currentPath.removeLast();//remove leaf
                ORawPair<Long, Integer> pathItem = currentPath.peekLast();

                //go up into the path till will find first not visited branch
                while (pathItem != null) {
                  final long pathIndex = pathItem.getFirst();
                  final int pathItemIndex = pathItem.getSecond();

                  final OCacheEntry parentBucketCacheEntry = loadPageForRead(fileId, pathIndex, false);
                  try {
                    final OPrefixBTreeBucket<V> parentBucket = new OPrefixBTreeBucket<>(parentBucketCacheEntry, keySerializer,
                        valueSerializer, encryption);

                    int cItemIndex = Math.abs(pathItemIndex) - 1;
                    if (pathItemIndex < 0) {
                      cItemIndex--;
                    }

                    currentPath.removeLast();//remove current branch bucket
                    if (cItemIndex < 0) {
                      pathItem = currentPath.peekLast();
                    } else {
                      pathItem = new ORawPair<>(pathIndex, -(cItemIndex + 1));
                      currentPath.add(pathItem);

                      cacheEntry = loadPageForRead(fileId, parentBucket.getLeft(cItemIndex), false);
                      bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
                      break;
                    }
                  } finally {
                    releasePageFromRead(parentBucketCacheEntry);
                  }
                }

                if (pathItem == null) {
                  if (dataCache.isEmpty()) {
                    dataCacheIterator = null;
                    return null;
                  }

                  dataCacheIterator = dataCache.iterator();

                  final Map.Entry<String, V> entry = dataCacheIterator.next();

                  toKey = entry.getKey();
                  toKeyInclusive = false;

                  return entry;
                }

                //go by the left branch till leaf page
                while (true) {
                  if (bucket.isLeaf()) {
                    currentPath.add(new ORawPair<>(cacheEntry.getPageIndex(), 0));
                    itemIndex = bucket.size() - 1;
                    break;
                  } else {
                    final int lastIndex = bucket.size() - 1;
                    currentPath.add(new ORawPair<>(cacheEntry.getPageIndex(), lastIndex + 1));
                    final long childIndex = bucket.getRight(lastIndex);

                    releasePageFromRead(cacheEntry);

                    cacheEntry = loadPageForRead(fileId, childIndex, false);
                    bucket = new OPrefixBTreeBucket<>(cacheEntry, keySerializer, valueSerializer, encryption);
                  }
                }
              }

              final Map.Entry<String, V> entry = convertToMapEntry(bucket.getEntry(itemIndex));
              itemIndex--;

              if (toKey != null && (toKeyInclusive ?
                  comparator.compare(entry.getKey(), toKey) > 0 :
                  comparator.compare(entry.getKey(), toKey) >= 0)) {
                continue;
              }

              if (fromKey != null && (fromKeyInclusive ?
                  comparator.compare(entry.getKey(), fromKey) < 0 :
                  comparator.compare(entry.getKey(), fromKey) <= 0)) {
                break;
              }

              dataCache.add(entry);
            }
          } finally {
            if (cacheEntry != null) {
              releasePageFromRead(cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw OException.wrapException(new OPrefixBTreeException("Error during element iteration", OPrefixBTree.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(OPrefixBTree.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return null;
      }

      dataCacheIterator = dataCache.iterator();

      final Map.Entry<String, V> entry = dataCacheIterator.next();

      toKey = entry.getKey();
      toKeyInclusive = false;

      return entry;
    }
  }
}
