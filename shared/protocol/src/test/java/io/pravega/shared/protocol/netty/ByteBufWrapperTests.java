/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.shared.protocol.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.pravega.common.ObjectClosedException;
import io.pravega.test.common.AssertExtensions;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import lombok.Cleanup;
import lombok.val;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the {@link ByteBufWrapper} class.
 */
public class ByteBufWrapperTests {
    private static final int BUFFER_SIZE = 1024;
    private static final int SKIP_COUNT = 10;
    private final Random rnd = new Random(0);

    /**
     * Tests the {@link ByteBufWrapper#ByteBufWrapper} and  {@link ByteBufWrapper#close} methods' ability to manipulate
     * the underlying ByteBuf's reference count.
     */
    @Test
    public void testReferences() {
        val data = newData();
        val buf = wrap(data);
        Assert.assertEquals(1, buf.refCnt());
        val wrap = new ByteBufWrapper(buf);
        Assert.assertEquals(1, buf.refCnt());
        wrap.retain();
        Assert.assertEquals(2, buf.refCnt());
        buf.release();
        Assert.assertEquals(1, buf.refCnt());
        wrap.release();
        Assert.assertEquals(0, buf.refCnt());
        wrap.release(); // Check idempotency.
        Assert.assertEquals(0, buf.refCnt());
        Assert.assertEquals("Buffer length should be preserved after freeing.", data.length, wrap.getLength());

        // Check the other methods throw.
        AssertExtensions.assertThrows("getCopy() worked when ByteBufWrapper was closed.",
                () -> wrap.getCopy(),
                ex -> ex instanceof ObjectClosedException);
        AssertExtensions.assertThrows("getReader() worked when ByteBufWrapper was closed.",
                () -> wrap.getReader(),
                ex -> ex instanceof ObjectClosedException);
        AssertExtensions.assertThrows("getReader() worked when ByteBufWrapper was closed.",
                () -> wrap.copyTo(new ByteArrayOutputStream()),
                ex -> ex instanceof ObjectClosedException);
    }

    /**
     * Tests the ability of {@link ByteBufWrapper} to separate itself from the state of the underlying byte buffer.
     */
    @Test
    public void testSeparation() {
        val data = newData();
        val expectedData = new byte[data.length - SKIP_COUNT];
        System.arraycopy(data, SKIP_COUNT, expectedData, 0, expectedData.length);

        @Cleanup("release")
        val buf = wrap(data);

        // Increase the reader index. Our constructor should pick up from the reader index, and not from the beginning.
        buf.readerIndex(buf.readerIndex() + SKIP_COUNT);
        val wrap = new ByteBufWrapper(buf);

        // Increase the reader index again. This should have no effect on our wrapper.
        buf.readerIndex(buf.readerIndex() + SKIP_COUNT);
        val copy = wrap.getCopy();
        Assert.assertArrayEquals("ByteBufWrapper was modified when the underlying ByteBuf was modified.", expectedData, copy);
    }

    /**
     * Tests all functionality.
     */
    @Test
    public void testFunctionality() throws Exception {
        val data = newData();
        val expectedData = new byte[data.length - SKIP_COUNT];
        System.arraycopy(data, SKIP_COUNT, expectedData, 0, expectedData.length);

        @Cleanup("release")
        val buf = wrap(data);
        buf.readerIndex(buf.readerIndex() + SKIP_COUNT);
        val wrap = new ByteBufWrapper(buf);

        // Length.
        Assert.assertEquals("Unexpected length.", buf.readableBytes(), wrap.getLength());

        // Get Copy.
        val copy = wrap.getCopy();
        Assert.assertArrayEquals("Unexpected result from getCopy.", expectedData, copy);

        // Get Reader.
        @Cleanup
        val reader = wrap.getReader();
        val readerData = IOUtils.readFully(reader, wrap.getLength());
        Assert.assertArrayEquals("Unexpected result from getReader.", expectedData, copy);

        // Copy To.
        @Cleanup
        val outputStream1 = new ByteArrayOutputStream();
        wrap.copyTo(outputStream1);
        Assert.assertArrayEquals("Unexpected result from copyTo.", expectedData, outputStream1.toByteArray());
    }

    private byte[] newData() {
        byte[] data = new byte[BUFFER_SIZE];
        rnd.nextBytes(data);
        return data;
    }

    private ByteBuf wrap(byte[] data) {
        return Unpooled.wrappedBuffer(data);
    }
}
