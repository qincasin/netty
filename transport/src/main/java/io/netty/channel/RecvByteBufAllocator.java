/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import io.netty.util.internal.UnstableApi;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Allocates a new receive buffer whose capacity is probably large enough to read all inbound data and small enough
 * not to waste its space.
 */
public interface RecvByteBufAllocator {
    /**
     * Creates a new handle.  The handle provides the actual operations and keeps the internal information which is
     * required for predicting an optimal buffer capacity.
     *
     * 创建一个Handler对象，作用就是预测下一次分配的一个可选的buffer对象大小，这个大小不能太大，也不能太小...
     */
    Handle newHandle();

    /**
     * @deprecated Use {@link ExtendedHandle}.
     */
    @Deprecated
    interface Handle {
        /**
         * Creates a new receive buffer whose capacity is probably large enough to read all inbound data and small
         * enough not to waste its space.
         * 分配ByteBuffer 缓冲区对象的接口  参数很重要！！！   handler 这一层 提供预测分配的 size
         * 参数 ： alloc  真正分配内存的大佬
         */
        ByteBuf allocate(ByteBufAllocator alloc);

        /**
         * Similar to {@link #allocate(ByteBufAllocator)} except that it does not allocate anything but just tells the
         * capacity.
         * 获取预测容量值
         */
        int guess();

        /**
         * Reset any counters that have accumulated and recommend how many messages/bytes should be read for the next
         * read loop.
         * <p>
         * This may be used by {@link #continueReading()} to determine if the read operation should complete.
         * </p>
         * This is only ever a hint and may be ignored by the implementation.
         * @param config The channel configuration which may impact this object's behavior.
         */
        void reset(ChannelConfig config);

        /**
         * Increment the number of messages that have been read for the current read loop.
         * @param numMessages The amount to increment by.
         *  增加已读消息数量  不是读取的byteSize ，而是读取的次数
         */
        void incMessagesRead(int numMessages);

        /**
         * Set the bytes that have been read for the last read operation.
         * This may be used to increment the number of bytes that have been read.
         * @param bytes The number of bytes from the previous read operation. This may be negative if an read error
         * occurs. If a negative value is seen it is expected to be return on the next call to
         * {@link #lastBytesRead()}. A negative value will signal a termination condition enforced externally
         * to this class and is not required to be enforced in {@link #continueReading()}.
         * 最后一次从ch读取的数据量 大小，这里指的就是 byteSize
         */
        void lastBytesRead(int bytes);

        /**
         * Get the amount of bytes for the previous read operation.
         * @return The amount of bytes for the previous read operation.
         *
         * 获取最后一次从ch读取的数据量大小
         */
        int lastBytesRead();

        /**
         * Set how many bytes the read operation will (or did) attempt to read.
         * @param bytes How many bytes the read operation will (or did) attempt to read.
         *  设置即将想要 读取的数据量 或者已经读取的数据量
         */
        void attemptedBytesRead(int bytes);

        /**
         * Get how many bytes the read operation will (or did) attempt to read.
         * 获取 即将想要 读取的数据量 或者已经读取的数据量
         * @return How many bytes the read operation will (or did) attempt to read.
         */
        int attemptedBytesRead();

        /**
         * Determine if the current read loop should continue.
         * 判断是否继续循环   也就是 之前的那个 do ..... while    NioMessageUnsafe.read   NioByteUnsafe.read
         * @return {@code true} if the read loop should continue reading. {@code false} if the read loop is complete.
         */
        boolean continueReading();

        /**
         * The read has completed.
         * 本次读循环 完毕
         */
        void readComplete();
    }

    @SuppressWarnings("deprecation")
    @UnstableApi
    interface ExtendedHandle extends Handle {
        /**
         * Same as {@link Handle#continueReading()} except "more data" is determined by the supplier parameter.
         * @param maybeMoreDataSupplier A supplier that determines if there maybe more data to read.
         */
        boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier);
    }

    /**
     * A {@link Handle} which delegates all call to some other {@link Handle}.
     */
    class DelegatingHandle implements Handle {
        private final Handle delegate;

        public DelegatingHandle(Handle delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
        }

        /**
         * Get the {@link Handle} which all methods will be delegated to.
         * @return the {@link Handle} which all methods will be delegated to.
         */
        protected final Handle delegate() {
            return delegate;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return delegate.allocate(alloc);
        }

        @Override
        public int guess() {
            return delegate.guess();
        }

        @Override
        public void reset(ChannelConfig config) {
            delegate.reset(config);
        }

        @Override
        public void incMessagesRead(int numMessages) {
            delegate.incMessagesRead(numMessages);
        }

        @Override
        public void lastBytesRead(int bytes) {
            delegate.lastBytesRead(bytes);
        }

        @Override
        public int lastBytesRead() {
            return delegate.lastBytesRead();
        }

        @Override
        public boolean continueReading() {
            return delegate.continueReading();
        }

        @Override
        public int attemptedBytesRead() {
            return delegate.attemptedBytesRead();
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            delegate.attemptedBytesRead(bytes);
        }

        @Override
        public void readComplete() {
            delegate.readComplete();
        }
    }
}
