package com.orientechnologies.orient.core.storage.impl.local.paginated.wal;

import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.storage.cache.OReadCache;
import com.orientechnologies.orient.core.storage.cache.OWriteCache;

import java.nio.ByteBuffer;

public final class OFileDeletedWALRecord extends OOperationUnitBodyRecord {
  private long fileId;

  OFileDeletedWALRecord() {
  }


  @Override
  public void undo(final OReadCache readCache, final OWriteCache writeCache, final OWriteAheadLog writeAheadLog,
      final OOperationUnitId operationUnitId) {
    throw new OStorageException("File deletion can not be rolled back");
  }

  public OFileDeletedWALRecord(final long fileId) {
    this.fileId = fileId;
  }

  public long getFileId() {
    return fileId;
  }

  @Override
  public int toStream(final byte[] content, int offset) {
    offset = super.toStream(content, offset);

    OLongSerializer.serializeNative(fileId, content, offset);
    offset += OLongSerializer.LONG_SIZE;

    return offset;
  }

  @Override
  public void toStream(final ByteBuffer buffer) {
    super.toStream(buffer);
    buffer.putLong(fileId);
  }

  @Override
  public int fromStream(final byte[] content, int offset) {
    offset = super.fromStream(content, offset);

    fileId = OLongSerializer.deserializeNative(content, offset);
    offset += OLongSerializer.LONG_SIZE;

    return offset;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + OLongSerializer.LONG_SIZE;
  }

  @Override
  public boolean isUpdateMasterRecord() {
    return false;
  }

  @Override
  public byte getId() {
    return WALRecordTypes.FILE_DELETED_WAL_RECORD;
  }
}
