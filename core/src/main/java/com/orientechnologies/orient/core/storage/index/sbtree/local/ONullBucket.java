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

import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurablePage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.pageoperations.btree.btreenullbucket.OSBTreeNullBucketRemoveValuePageOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.pageoperations.btree.btreenullbucket.OSBTreeNullBucketSetValuePageOperation;

/**
 * Bucket which is intended to save values stored in sbtree under <code>null</code> key. Bucket has following layout:
 * <ol>
 * <li>First byte is flag which indicates presence of value in bucket</li>
 * <li>Second byte indicates whether value is presented by link to the "bucket list" where actual value is stored or real value
 * passed be user.</li>
 * <li>The rest is serialized value whether link or passed in value.</li>
 * </ol>
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 4/15/14
 */
public final class ONullBucket<V> extends ODurablePage {
  public ONullBucket(final OCacheEntry cacheEntry) {
    super(cacheEntry);
  }

  void init() {
    setByteValue(NEXT_FREE_POSITION, (byte) 0);
  }

  public final void setValue(final OSBTreeValue<V> value, final OBinarySerializer<V> valueSerializer, final int prevValueSize) {
    final int valueSize = valueSerializer.getObjectSize(value.getValue());

    final byte[] serializedValue = new byte[valueSize];
    valueSerializer.serializeNativeObject(value.getValue(), serializedValue, 0);

    setValue(serializedValue, prevValueSize);
  }

  public final void setValue(final byte[] value, final int prevValueSize) {
    final byte[] prevValue;
    if (getByteValue(NEXT_FREE_POSITION) == 0) {
      prevValue = null;
    } else {
      prevValue = getBinaryValue(NEXT_FREE_POSITION + 2, prevValueSize);
    }

    setByteValue(NEXT_FREE_POSITION, (byte) 1);
    setByteValue(NEXT_FREE_POSITION + 1, (byte) 1);
    setBinaryValue(NEXT_FREE_POSITION + 2, value);

    addPageOperation(new OSBTreeNullBucketSetValuePageOperation(prevValue, value.length));
  }

  public final byte[] getRawValue(final OBinarySerializer<V> valueSerializer) {
    if (getByteValue(NEXT_FREE_POSITION) == 0) {
      return null;
    }

    return getBinaryValue(NEXT_FREE_POSITION + 2, getObjectSizeInDirectMemory(valueSerializer, NEXT_FREE_POSITION + 2));
  }

  public final OSBTreeValue<V> getValue(final OBinarySerializer<V> valueSerializer) {
    if (getByteValue(NEXT_FREE_POSITION) == 0)
      return null;

    final boolean isLink = getByteValue(NEXT_FREE_POSITION + 1) == 0;
    if (isLink)
      return new OSBTreeValue<>(true, getLongValue(NEXT_FREE_POSITION + 2), null);

    return new OSBTreeValue<>(false, -1, deserializeFromDirectMemory(valueSerializer, NEXT_FREE_POSITION + 2));
  }

  public final void removeValue(final int prevValueSize) {
    if (getByteValue(NEXT_FREE_POSITION) > 0) {
      final byte[] prevValue = getBinaryValue(NEXT_FREE_POSITION + 2, prevValueSize);
      setByteValue(NEXT_FREE_POSITION, (byte) 0);

      addPageOperation(new OSBTreeNullBucketRemoveValuePageOperation(prevValue));
    }
  }
}
