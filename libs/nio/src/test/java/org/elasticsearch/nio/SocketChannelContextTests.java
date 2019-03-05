/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.nio;

import org.elasticsearch.test.ESTestCase;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SocketChannelContextTests extends ESTestCase {

    private SocketChannel rawChannel;
    private TestSocketChannelContext context;
    private Consumer<Exception> exceptionHandler;
    private NioSocketChannel channel;
    private BiConsumer<Void, Exception> listener;
    private NioSelector selector;
    private ReadWriteHandler readWriteHandler;
    private ByteBuffer ioBuffer = ByteBuffer.allocate(1024);

    @SuppressWarnings("unchecked")
    @Before
    public void setup() throws Exception {
        super.setUp();

        rawChannel = mock(SocketChannel.class);
        channel = mock(NioSocketChannel.class);
        listener = mock(BiConsumer.class);
        when(channel.getRawChannel()).thenReturn(rawChannel);
        exceptionHandler = mock(Consumer.class);
        selector = mock(NioSelector.class);
        readWriteHandler = mock(ReadWriteHandler.class);
        InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
        context = new TestSocketChannelContext(channel, selector, exceptionHandler, readWriteHandler, channelBuffer);

        when(selector.isOnCurrentThread()).thenReturn(true);
        when(selector.getIoBuffer()).thenAnswer(invocationOnMock -> {
            ioBuffer.clear();
            return ioBuffer;
        });
    }

    public void testIOExceptionSetIfEncountered() throws IOException {
        when(rawChannel.write(any(ByteBuffer[].class), anyInt(), anyInt())).thenThrow(new IOException());
        when(rawChannel.write(any(ByteBuffer.class))).thenThrow(new IOException());
        when(rawChannel.read(any(ByteBuffer[].class), anyInt(), anyInt())).thenThrow(new IOException());
        when(rawChannel.read(any(ByteBuffer.class))).thenThrow(new IOException());
        assertFalse(context.closeNow());
        expectThrows(IOException.class, () -> {
            if (randomBoolean()) {
                context.read();
            } else {
                context.flushChannel();
            }
        });
        assertTrue(context.closeNow());
    }

    public void testSignalWhenPeerClosed() throws IOException {
        when(rawChannel.read(any(ByteBuffer.class))).thenReturn(-1);
        assertFalse(context.closeNow());
        context.read();
        assertTrue(context.closeNow());
    }

    public void testValidateInRegisterCanSucceed() throws IOException {
        InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
        context = new TestSocketChannelContext(channel, selector, exceptionHandler, readWriteHandler, channelBuffer, (c) -> true);
        assertFalse(context.closeNow());
        context.register();
        assertFalse(context.closeNow());
    }

    public void testValidateInRegisterCanFail() throws IOException {
        InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
        context = new TestSocketChannelContext(channel, selector, exceptionHandler, readWriteHandler, channelBuffer, (c) -> false);
        assertFalse(context.closeNow());
        context.register();
        assertTrue(context.closeNow());
    }

    public void testConnectSucceeds() throws IOException {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        when(rawChannel.finishConnect()).thenReturn(false, true);

        context.addConnectListener((v, t) -> {
            if (t == null) {
                listenerCalled.compareAndSet(false, true);
            } else {
                throw new AssertionError("Connection should not fail");
            }
        });

        assertFalse(context.connect());
        assertFalse(context.isConnectComplete());
        assertFalse(listenerCalled.get());
        assertTrue(context.connect());
        assertTrue(context.isConnectComplete());
        assertTrue(listenerCalled.get());
    }

    public void testConnectFails() throws IOException {
        AtomicReference<Exception> exception = new AtomicReference<>();
        IOException ioException = new IOException("boom");
        when(rawChannel.finishConnect()).thenReturn(false).thenThrow(ioException);

        context.addConnectListener((v, t) -> {
            if (t == null) {
                throw new AssertionError("Connection should not succeed");
            } else {
                exception.set(t);
            }
        });

        assertFalse(context.connect());
        assertFalse(context.isConnectComplete());
        assertNull(exception.get());
        expectThrows(IOException.class, context::connect);
        assertFalse(context.isConnectComplete());
        assertSame(ioException, exception.get());
    }

    public void testWriteFailsIfClosing() {
        context.closeChannel();

        ByteBuffer[] buffers = {ByteBuffer.wrap(createMessage(10))};
        context.sendMessage(buffers, listener);

        verify(listener).accept(isNull(Void.class), any(ClosedChannelException.class));
    }

    public void testSendMessageFromDifferentThreadIsQueuedWithSelector() throws Exception {
        ArgumentCaptor<WriteOperation> writeOpCaptor = ArgumentCaptor.forClass(WriteOperation.class);

        when(selector.isOnCurrentThread()).thenReturn(false);

        ByteBuffer[] buffers = {ByteBuffer.wrap(createMessage(10))};
        WriteOperation writeOperation = mock(WriteOperation.class);
        when(readWriteHandler.createWriteOperation(context, buffers, listener)).thenReturn(writeOperation);
        context.sendMessage(buffers, listener);

        verify(selector).queueWrite(writeOpCaptor.capture());
        WriteOperation writeOp = writeOpCaptor.getValue();

        assertSame(writeOperation, writeOp);
    }

    public void testSendMessageFromSameThreadIsQueuedInChannel() {
        ArgumentCaptor<WriteOperation> writeOpCaptor = ArgumentCaptor.forClass(WriteOperation.class);

        ByteBuffer[] buffers = {ByteBuffer.wrap(createMessage(10))};
        WriteOperation writeOperation = mock(WriteOperation.class);
        when(readWriteHandler.createWriteOperation(context, buffers, listener)).thenReturn(writeOperation);
        context.sendMessage(buffers, listener);

        verify(selector).writeToChannel(writeOpCaptor.capture());
        WriteOperation writeOp = writeOpCaptor.getValue();

        assertSame(writeOperation, writeOp);
    }

    public void testWriteIsQueuedInChannel() {
        assertFalse(context.readyForFlush());

        ByteBuffer[] buffer = {ByteBuffer.allocate(10)};
        FlushReadyWrite writeOperation = new FlushReadyWrite(context, buffer, listener);
        when(readWriteHandler.writeToBytes(writeOperation)).thenReturn(Collections.singletonList(writeOperation));
        context.queueWriteOperation(writeOperation);

        verify(readWriteHandler).writeToBytes(writeOperation);
        assertTrue(context.readyForFlush());
    }

    public void testHandleReadBytesWillCheckForNewFlushOperations() throws IOException {
        assertFalse(context.readyForFlush());
        when(readWriteHandler.pollFlushOperations()).thenReturn(Collections.singletonList(mock(FlushOperation.class)));
        context.handleReadBytes();
        assertTrue(context.readyForFlush());
    }

    @SuppressWarnings({"unchecked", "varargs"})
    public void testFlushOpsClearedOnClose() throws Exception {
        try (SocketChannel realChannel = SocketChannel.open()) {
            when(channel.getRawChannel()).thenReturn(realChannel);
            InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
            context = new TestSocketChannelContext(channel, selector, exceptionHandler, readWriteHandler, channelBuffer);

            assertFalse(context.readyForFlush());

            ByteBuffer[] buffer = {ByteBuffer.allocate(10)};
            WriteOperation writeOperation = mock(WriteOperation.class);
            BiConsumer<Void, Exception> listener2 = mock(BiConsumer.class);
            when(readWriteHandler.writeToBytes(writeOperation)).thenReturn(Arrays.asList(new FlushOperation(buffer, listener),
                new FlushOperation(buffer, listener2)));
            context.queueWriteOperation(writeOperation);

            assertTrue(context.readyForFlush());

            when(channel.isOpen()).thenReturn(true);
            context.closeFromSelector();

            verify(selector, times(1)).executeFailedListener(same(listener), any(ClosedChannelException.class));
            verify(selector, times(1)).executeFailedListener(same(listener2), any(ClosedChannelException.class));

            assertFalse(context.readyForFlush());
        }
    }

    @SuppressWarnings({"unchecked", "varargs"})
    public void testWillPollForFlushOpsToClose() throws Exception {
        try (SocketChannel realChannel = SocketChannel.open()) {
            when(channel.getRawChannel()).thenReturn(realChannel);
            InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
            context = new TestSocketChannelContext(channel, selector, exceptionHandler, readWriteHandler, channelBuffer);


            ByteBuffer[] buffer = {ByteBuffer.allocate(10)};
            BiConsumer<Void, Exception> listener2 = mock(BiConsumer.class);

            assertFalse(context.readyForFlush());
            when(channel.isOpen()).thenReturn(true);
            when(readWriteHandler.pollFlushOperations()).thenReturn(Arrays.asList(new FlushOperation(buffer, listener),
                new FlushOperation(buffer, listener2)));
            context.closeFromSelector();

            verify(selector, times(1)).executeFailedListener(same(listener), any(ClosedChannelException.class));
            verify(selector, times(1)).executeFailedListener(same(listener2), any(ClosedChannelException.class));

            assertFalse(context.readyForFlush());
        }
    }

    public void testCloseClosesWriteProducer() throws IOException {
        try (SocketChannel realChannel = SocketChannel.open()) {
            when(channel.getRawChannel()).thenReturn(realChannel);
            when(channel.isOpen()).thenReturn(true);
            InboundChannelBuffer buffer = InboundChannelBuffer.allocatingInstance();
            BytesChannelContext context = new BytesChannelContext(channel, selector, exceptionHandler, readWriteHandler, buffer);
            context.closeFromSelector();
            verify(readWriteHandler).close();
        }
    }

    public void testCloseClosesChannelBuffer() throws IOException {
        try (SocketChannel realChannel = SocketChannel.open()) {
            when(channel.getRawChannel()).thenReturn(realChannel);
            when(channel.isOpen()).thenReturn(true);
            Runnable closer = mock(Runnable.class);
            Supplier<InboundChannelBuffer.Page> pageSupplier = () -> new InboundChannelBuffer.Page(ByteBuffer.allocate(1 << 14), closer);
            InboundChannelBuffer buffer = new InboundChannelBuffer(pageSupplier);
            buffer.ensureCapacity(1);
            TestSocketChannelContext context = new TestSocketChannelContext(channel, selector, exceptionHandler, readWriteHandler, buffer);
            context.closeFromSelector();
            verify(closer).run();
        }
    }

    public void testReadToBufferLimitsToPassedBuffer() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(10);
        when(rawChannel.read(any(ByteBuffer.class))).thenAnswer(completelyFillBufferAnswer());

        int bytesRead = context.readFromChannel(buffer);
        assertEquals(bytesRead, 10);
        assertEquals(0, buffer.remaining());
    }

    public void testReadToBufferHandlesIOException() throws IOException {
        when(rawChannel.read(any(ByteBuffer.class))).thenThrow(new IOException());

        expectThrows(IOException.class, () -> context.readFromChannel(ByteBuffer.allocate(10)));
        assertTrue(context.closeNow());
    }

    public void testReadToBufferHandlesEOF() throws IOException {
        when(rawChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        context.readFromChannel(ByteBuffer.allocate(10));
        assertTrue(context.closeNow());
    }

    public void testReadToChannelBufferWillReadAsMuchAsIOBufferAllows() throws IOException {
        when(rawChannel.read(any(ByteBuffer.class))).thenAnswer(completelyFillBufferAnswer());

        InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
        int bytesRead = context.readFromChannel(channelBuffer);
        assertEquals(ioBuffer.capacity(), bytesRead);
        assertEquals(ioBuffer.capacity(), channelBuffer.getIndex());
    }

    public void testReadToChannelBufferHandlesIOException() throws IOException  {
        when(rawChannel.read(any(ByteBuffer.class))).thenThrow(new IOException());

        InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
        expectThrows(IOException.class, () -> context.readFromChannel(channelBuffer));
        assertTrue(context.closeNow());
        assertEquals(0, channelBuffer.getIndex());
    }

    public void testReadToChannelBufferHandlesEOF() throws IOException {
        when(rawChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
        context.readFromChannel(channelBuffer);
        assertTrue(context.closeNow());
        assertEquals(0, channelBuffer.getIndex());
    }

    public void testFlushBufferHandlesPartialFlush() throws IOException {
        int bytesToConsume = 3;
        when(rawChannel.write(any(ByteBuffer.class))).thenAnswer(consumeBufferAnswer(bytesToConsume));

        ByteBuffer buffer = ByteBuffer.allocate(10);
        context.flushToChannel(buffer);
        assertEquals(10 - bytesToConsume, buffer.remaining());
    }

    public void testFlushBufferHandlesFullFlush() throws IOException {
        int bytesToConsume = 10;
        when(rawChannel.write(any(ByteBuffer.class))).thenAnswer(consumeBufferAnswer(bytesToConsume));

        ByteBuffer buffer = ByteBuffer.allocate(10);
        context.flushToChannel(buffer);
        assertEquals(0, buffer.remaining());
    }

    public void testFlushBufferHandlesIOException() throws IOException {
        when(rawChannel.write(any(ByteBuffer.class))).thenThrow(new IOException());

        ByteBuffer buffer = ByteBuffer.allocate(10);
        expectThrows(IOException.class, () -> context.flushToChannel(buffer));
        assertTrue(context.closeNow());
        assertEquals(10, buffer.remaining());
    }

    public void testFlushBuffersHandlesZeroFlush() throws IOException {
        when(rawChannel.write(any(ByteBuffer.class))).thenAnswer(consumeBufferAnswer(0));

        ByteBuffer[] buffers = {ByteBuffer.allocate(1023), ByteBuffer.allocate(1023)};
        FlushOperation flushOperation = new FlushOperation(buffers, listener);
        context.flushToChannel(flushOperation);
        assertEquals(2, flushOperation.getBuffersToWrite().length);
        assertEquals(0, flushOperation.getBuffersToWrite()[0].position());
    }

    public void testFlushBuffersHandlesPartialFlush() throws IOException {
        AtomicBoolean first = new AtomicBoolean(true);
        when(rawChannel.write(any(ByteBuffer.class))).thenAnswer(invocationOnMock -> {
            if (first.compareAndSet(true, false)) {
                return consumeBufferAnswer(1024).answer(invocationOnMock);
            } else {
                return consumeBufferAnswer(3).answer(invocationOnMock);
            }
        });

        ByteBuffer[] buffers = {ByteBuffer.allocate(1023), ByteBuffer.allocate(1023)};
        FlushOperation flushOperation = new FlushOperation(buffers, listener);
        context.flushToChannel(flushOperation);
        assertEquals(1, flushOperation.getBuffersToWrite().length);
        assertEquals(4, flushOperation.getBuffersToWrite()[0].position());
    }

    public void testFlushBuffersHandlesFullFlush() throws IOException {
        AtomicBoolean first = new AtomicBoolean(true);
        when(rawChannel.write(any(ByteBuffer.class))).thenAnswer(invocationOnMock -> {
            if (first.compareAndSet(true, false)) {
                return consumeBufferAnswer(1024).answer(invocationOnMock);
            } else {
                return consumeBufferAnswer(1022).answer(invocationOnMock);
            }
        });

        ByteBuffer[] buffers = {ByteBuffer.allocate(1023), ByteBuffer.allocate(1023)};
        FlushOperation flushOperation = new FlushOperation(buffers, listener);
        context.flushToChannel(flushOperation);
        assertTrue(flushOperation.isFullyFlushed());
    }

    public void testFlushBuffersHandlesIOException() throws IOException {
        when(rawChannel.write(any(ByteBuffer.class))).thenThrow(new IOException());

        ByteBuffer[] buffers = {ByteBuffer.allocate(10), ByteBuffer.allocate(10)};
        FlushOperation flushOperation = new FlushOperation(buffers, listener);
        expectThrows(IOException.class, () -> context.flushToChannel(flushOperation));
        assertTrue(context.closeNow());
    }

    public void testFlushBuffersHandlesIOExceptionSecondTimeThroughLoop() throws IOException {
        AtomicBoolean first = new AtomicBoolean(true);
        when(rawChannel.write(any(ByteBuffer.class))).thenAnswer(invocationOnMock -> {
            if (first.compareAndSet(true, false)) {
                return consumeBufferAnswer(1024).answer(invocationOnMock);
            } else {
                throw new IOException();
            }
        });

        ByteBuffer[] buffers = {ByteBuffer.allocate(1023), ByteBuffer.allocate(1023)};
        FlushOperation flushOperation = new FlushOperation(buffers, listener);
        expectThrows(IOException.class, () -> context.flushToChannel(flushOperation));
        assertTrue(context.closeNow());
        assertEquals(1, flushOperation.getBuffersToWrite().length);
        assertEquals(1, flushOperation.getBuffersToWrite()[0].position());
    }

    private static class TestSocketChannelContext extends SocketChannelContext {

        private TestSocketChannelContext(NioSocketChannel channel, NioSelector selector, Consumer<Exception> exceptionHandler,
                                         ReadWriteHandler readWriteHandler, InboundChannelBuffer channelBuffer) {
            this(channel, selector, exceptionHandler, readWriteHandler, channelBuffer, ALWAYS_ALLOW_CHANNEL);
        }

        private TestSocketChannelContext(NioSocketChannel channel, NioSelector selector, Consumer<Exception> exceptionHandler,
                                         ReadWriteHandler readWriteHandler, InboundChannelBuffer channelBuffer,
                                         Predicate<NioSocketChannel> allowChannelPredicate) {
            super(channel, selector, exceptionHandler, readWriteHandler, channelBuffer, allowChannelPredicate);
        }

        @Override
        public int read() throws IOException {
            if (randomBoolean()) {
                InboundChannelBuffer channelBuffer = InboundChannelBuffer.allocatingInstance();
                return readFromChannel(channelBuffer);
            } else {
                return readFromChannel(ByteBuffer.allocate(10));
            }
        }

        @Override
        public void flushChannel() throws IOException {
            if (randomBoolean()) {
                ByteBuffer[] byteBuffers = {ByteBuffer.allocate(10)};
                flushToChannel(new FlushOperation(byteBuffers, (v, e) -> {}));
            } else {
                flushToChannel(ByteBuffer.allocate(10));
            }
        }

        @Override
        public boolean selectorShouldClose() {
            return isClosing.get();
        }

        @Override
        public void closeChannel() {
            isClosing.set(true);
        }

        @Override
        void doSelectorRegister() {
            // We do not want to call the actual register with selector method as it will throw a NPE
        }
    }

    private static byte[] createMessage(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; ++i) {
            bytes[i] = randomByte();
        }
        return bytes;
    }

    private Answer<Integer> completelyFillBufferAnswer() {
        return invocationOnMock -> {
            ByteBuffer b = (ByteBuffer) invocationOnMock.getArguments()[0];
            int bytesRead = b.remaining();
            while (b.hasRemaining()) {
                b.put((byte) 1);
            }
            return bytesRead;
        };
    }

    private Answer<Object> consumeBufferAnswer(int bytesToConsume) {
        return invocationOnMock -> {
            ByteBuffer b = (ByteBuffer) invocationOnMock.getArguments()[0];
            b.position(b.position() + bytesToConsume);
            return bytesToConsume;
        };
    }
}
