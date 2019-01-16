/*
 * Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.common.serialization.types;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 04.04.12
 */
public class DecimalSerializerTest {
  private final static int        FIELD_SIZE = 9;
  private static final byte[]     stream     = new byte[FIELD_SIZE];
  private static final BigDecimal OBJECT     = new BigDecimal(new BigInteger("20"), 2);
  private ODecimalSerializer decimalSerializer;

  @Before
  public void beforeClass() {
    decimalSerializer = new ODecimalSerializer();
  }

  @Test
  public void testFieldSize() {
    Assert.assertEquals(decimalSerializer.getObjectSize(OBJECT), FIELD_SIZE);
  }

  @Test
  public void testSerialize() {
    decimalSerializer.serialize(OBJECT, stream, 0);
    Assert.assertEquals(decimalSerializer.deserialize(stream, 0), OBJECT);
  }

  @Test
  public void testSerializeNative() {
    decimalSerializer.serializeNativeObject(OBJECT, stream, 0);
    Assert.assertEquals(decimalSerializer.deserializeNativeObject(stream, 0), OBJECT);
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    decimalSerializer.serializeNativeObject(OBJECT, stream, 0);

    final ByteBuffer buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.put(stream);
    buffer.position(0);

    Assert.assertEquals(decimalSerializer.deserializeFromByteBufferObject(buffer), OBJECT);
  }

  @Test
  public void testSerializeInByteBuffer() {
    final int serializationOffset = 5;

    final ByteBuffer buffer = ByteBuffer.allocate(FIELD_SIZE + serializationOffset);

    buffer.position(serializationOffset);
    decimalSerializer.serializeInByteBufferObject(OBJECT, buffer);

    final int binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, FIELD_SIZE);

    buffer.position(serializationOffset);
    Assert.assertEquals(decimalSerializer.getObjectSizeInByteBuffer(buffer), FIELD_SIZE);

    buffer.position(serializationOffset);
    Assert.assertEquals(decimalSerializer.deserializeFromByteBufferObject(buffer), OBJECT);

    Assert.assertEquals(buffer.position() - serializationOffset, FIELD_SIZE);
  }
}
