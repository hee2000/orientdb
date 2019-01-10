package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.pageoperations.extendiblehashing.directorypage;

import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OPageOperationRecord;
import com.orientechnologies.orient.core.storage.index.hashindex.local.ODirectoryPage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

abstract class ODirectoryPageOperation extends OPageOperationRecord<ODirectoryPage> {
  @Override
  protected final ODirectoryPage createPageInstance(final OCacheEntry cacheEntry) {
    return new ODirectoryPage(cacheEntry);
  }

  @Override
  public final boolean isUpdateMasterRecord() {
    return false;
  }

  @Override
  public final int toStream(final byte[] content, int offset) {
    offset = super.toStream(content, offset);

    final ByteBuffer buffer = createNativeByteBuffer(content, offset);
    serializeToByteBuffer(buffer);

    return buffer.position();
  }

  @Override
  public final void toStream(final ByteBuffer buffer) {
    super.toStream(buffer);

    serializeToByteBuffer(buffer);
  }

  @Override
  public final int fromStream(final byte[] content, int offset) {
    offset = super.fromStream(content, offset);

    final ByteBuffer buffer = createNativeByteBuffer(content, offset);

    deserializeFromByteBuffer(buffer);

    return buffer.position();
  }

  private static ByteBuffer createNativeByteBuffer(final byte[] content, final int offset) {
    return ByteBuffer.wrap(content, offset, content.length - offset).order(ByteOrder.nativeOrder());
  }

  static void serializeByteArray(final byte[] value, final ByteBuffer buffer) {
    buffer.putInt(value.length);
    buffer.put(value);
  }

  static byte[] deserializeByteArray(final ByteBuffer buffer) {
    final int len = buffer.getInt();
    final byte[] value = new byte[len];
    buffer.get(value);

    return value;
  }

  protected abstract void serializeToByteBuffer(ByteBuffer buffer);

  protected abstract void deserializeFromByteBuffer(ByteBuffer buffer);
}
