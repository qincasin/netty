/*
 * Copyright 2015 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    //每次读循环操作  最大能读取的消息数量 每到ch拉一次消息，称为一个消息
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        // channel#config
        private ChannelConfig config;
        //每次读循环操作  最大能读取的消息数量 每到ch拉一次消息，称为一个消息
        private int maxMessagePerRead;
        //已经读的消息数量
        private int totalMessages;
        //已经读的消息size总大小
        private int totalBytesRead;
        //预测读的字节大小
        private int attemptedBytesRead;
        //最后一次读的字节数量
        private int lastBytesRead;
        //true
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                // 预估读取量 == 最后一次读取量
                //true : 最后一次读取数据量 和 评估的数据量 一致，，，， 说明ch内可能还剩余数据  未读取完 还需要继续
                //false : 1.评估的数据量产生一个ByteBuf  > 剩余数据量   2.channel close lastBytesRead =-1 都代表不在继续循环了
                return attemptedBytesRead == lastBytesRead;
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         * 重置handler
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            // 重新设置 读循环操作，最大可读消息量   默认情况下是16  服务端和客户端都是 16
//            /**
//             * Set the {@link RecvByteBufAllocator} which is used for the channel to allocate receive buffers.
//             * @param allocator the allocator to set.
//             * @param metadata Used to set the {@link ChannelMetadata#defaultMaxMessagesPerRead()} if {@code allocator}
//             * is of type {@link MaxMessagesRecvByteBufAllocator}.
//             */
//            private void setRecvByteBufAllocator(RecvByteBufAllocator allocator, ChannelMetadata metadata) {
//                checkNotNull(allocator, "allocator");
//                checkNotNull(metadata, "metadata");
//                if (allocator instanceof MaxMessagesRecvByteBufAllocator) {
//                    ((MaxMessagesRecvByteBufAllocator) allocator).maxMessagesPerRead(metadata.defaultMaxMessagesPerRead());  这个地方的值就是 16
            // 具体的meta配置在 AbstractNioByteChannel #    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);  (客户端)
            //NioServerSocketChannel  #                   private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);   (服务端)
//                }
//                setRecvByteBufAllocator(allocator);
//            }
            maxMessagePerRead = maxMessagesPerRead();
            //统计字段归零
            totalMessages = totalBytesRead = 0;
        }

        @Override
        //alloc  真正的 分配内存的 缓冲区分配器
        public ByteBuf allocate(ByteBufAllocator alloc) {
            // guess()  根据读循环过程中的上线文  去评估一个 适合本次读循环大小的值
            // alloc.ioBuffer 真正分配内存  缓冲区 对象
            return alloc.ioBuffer(guess());
        }

        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            // continueReading 控制着读循环 是否继续 循环  非常重要 ！！！
            // 什么情况下继续循环 ？  4中情况都要成立 才会继续循环
            //1. config.isAutoRead() 默认都是 true
            //2. !respectMaybeMoreData 一般都是false
            // maybeMoreDataSupplier.get() true  最后一次读取数据量 和 评估的数据量 一致，，，， 说明ch内可能还剩余数据  未读取完 还需要继续
            //3. totalMessages < maxMessagePerRead 一次unsafe.read 最多能从ch中读取16次数据， 超过16次就不行了
            // totalBytesRead > 0;
                // 4.1 客户端    正常情况下都是 true   什么情况下会小于等于0  读取的数据量太多了，超出了int的最大值 会导致 totalBytesRead <0
                // 4.2 服务端    这里的值 会是 0>0 会是false ， 服务端 每次unsafe.read()     只进行一次 读循环
            return config.isAutoRead() &&
                   (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                   totalMessages < maxMessagePerRead &&
                   totalBytesRead > 0;
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
