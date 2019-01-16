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

package com.orientechnologies.orient.core.storage.impl.local.paginated.wal;

import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.serialization.types.OStringSerializer;
import com.orientechnologies.orient.core.storage.cache.OReadCache;
import com.orientechnologies.orient.core.storage.cache.OWriteCache;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 5/21/14
 */
public final class OFileCreatedWALRecord extends OOperationUnitBodyRecord {
  private String fileName;
  private long   fileId;

  public OFileCreatedWALRecord() {
  }

  public OFileCreatedWALRecord(final String fileName, final long fileId) {
    this.fileName = fileName;
    this.fileId = fileId;
  }

  public String getFileName() {
    return fileName;
  }

  public long getFileId() {
    return fileId;
  }


  @Override
  public void undo(final OReadCache readCache, final OWriteCache writeCache, final OWriteAheadLog writeAheadLog,
      final OOperationUnitId operationUnitId)
      throws IOException {
    final OFileDeletedWALRecord walRecord = new OFileDeletedWALRecord(fileId);
    walRecord.setOperationUnitId(operationUnitId);
    writeAheadLog.log(walRecord);

    readCache.deleteFile(fileId, writeCache);
  }

  @Override
  public final int toStream(final byte[] content, int offset) {
    offset = super.toStream(content, offset);

    OStringSerializer.INSTANCE.serializeNativeObject(fileName, content, offset);
    offset += OStringSerializer.INSTANCE.getObjectSize(fileName);

    OLongSerializer.serializeNative(fileId, content, offset);
    offset += OLongSerializer.LONG_SIZE;

    return offset;
  }

  @Override
  public final void toStream(final ByteBuffer buffer) {
    super.toStream(buffer);

    OStringSerializer.INSTANCE.serializeInByteBufferObject(fileName, buffer);
    buffer.putLong(fileId);
  }

  @Override
  public final int fromStream(final byte[] content, int offset) {
    offset = super.fromStream(content, offset);

    fileName = OStringSerializer.INSTANCE.deserializeNativeObject(content, offset);
    offset += OStringSerializer.INSTANCE.getObjectSize(fileName);

    fileId = OLongSerializer.deserializeNative(content, offset);
    offset += OLongSerializer.LONG_SIZE;

    return offset;
  }

  @Override
  public final int serializedSize() {
    return super.serializedSize() + OStringSerializer.INSTANCE.getObjectSize(fileName) + OLongSerializer.LONG_SIZE;
  }

  @Override
  public final boolean isUpdateMasterRecord() {
    return false;
  }

  @Override
  public final byte getId() {
    return WALRecordTypes.FILE_CREATED_WAL_RECORD;
  }
}
