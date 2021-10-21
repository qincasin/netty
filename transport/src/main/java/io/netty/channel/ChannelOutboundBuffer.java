/*
 * Copyright 2013 The Netty Project
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
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * </p>
 */
public final class ChannelOutboundBuffer {
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header
    //  - 6 reference fields
    //  - 2 long fields
    //  - 2 int fields
    //  - 1 boolean field
    //  - padding JVM要求对象占用的内存为8字节的整数倍
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };


    private final Channel channel;

    /**
     * 1. unflushedEntry != null && flushedEntry == null  此时 出站缓冲区 处于 数据入站阶段
     * 2. unflushedEntry == null && flushedEntry != null 此时 出站缓冲区 处于 数据出站阶段 ，
     * 调用了addFlush 方法后， 会将flushedEntry 指向 原 unflushedEntry 的值，并且计算出 一个 待刷新的节点数量  flushed 值
     * ==================================================================================================================================================
     * 3. unflushedEntry != null && flushedEntry != null 这种情况比较极端 ...
     * 假设业务层面 不停的 使用 ctx.write(msg) ,msg 最终都会 调用unsafe.write(msg.... )  ==> channelOutboundBuffer.addMessage(msg)
     * e1 -> e2 -> e3 -> e4 -> e5 -> ... -> eN
     * flushedEntry -> null
     * unflushedEntry -> e1
     * taiEntry -> eN
     * 业务handler 接下来 调用ctx.flush() 最终会触发 unsafe.flush()
     * unsafe.flush() {
     *     1.channelOutboundBuffer.addFlush() 这个方法 会将flushedEntry 指向 unflushedEntry 的元素  flushedEntry -> e1
     *     2.channelOutboundBuffer.nioBuffers(...) 这个方法会返回 byteBuffer[] 数组，供 下面逻辑 使用
     *     3. 遍历byteBuffer 数组， 调用jdk channel.write(buffer) 该方法 会返回真正写入 socket 写缓冲区的 字节数量  结果为 res
     *     4. 根据res 移除 出站缓冲区 内对应的entry .
     * }
     *
     * socket 写缓冲区 有可能被写满   假设写到 byteBuffer[3] 的时候， socket 写缓冲区满了  .... 那么 此时 nioEventLoop 再重试 去写 ，也没啥用，
     * 需要怎么办？
     *  需要设置多路复用器 当前ch 关注OP_WRITE 事件   当底层 socket 写缓冲区 有空闲时， 多路复用器 会再次唤醒NioEventLoop 线程去处理 ...
     *
     *
     *  这种情况 flushedEntry -> e4
     *  业务handler 再次使用 ctx.write(msg) 那么 unflushedEntry 就指向 当前 msg 对象的 Entry 了
     *
     *  e4 -> e5 -> ... -> eN -> eN+1
     * flushedEntry -> e4
     * unflushedEntry -> eN + 1
     * tailEntry -> eN+1
     */
    // Entry(flushedEntry) --> ... Entry(unflushedEntry) --> ... Entry(tailEntry)
    //
    // The Entry that is the first in the linked-list structure that was flushed
    private Entry flushedEntry;
    // The Entry which is the first unflushed in the linked-list structure
    private Entry unflushedEntry;
    // The Entry which represents the tail of the buffer
    private Entry tailEntry;
    // The number of flushed entries that are not written yet
    // 计算出 剩余多少的entry 待刷新到ch，addFlush方法会计算这个值，计算方式：从flushEntry 一直遍历到 tail，计算出有多少元素
    private int flushed;

    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    //内部 采用 cas方式 更新管理的 totalPendingSize 字段
    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    @SuppressWarnings("UnusedDeclaration")
    // 出站 缓冲区 总共有多少字节量    注意 包含entry 自身字段 占用的空间   entry --->(指向) msg + entry.field
    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    @SuppressWarnings("UnusedDeclaration")
    //表示出站 缓冲区 是否可写  0 表示可写， 1 表示不可写
    //如果业务层面不检查 unwritable 状态  就不限制
    private volatile int unwritable;

    private volatile Runnable fireChannelWritabilityChangedTask;

    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written.
     */
    //将ByteBuf数据 加入到 出站缓冲区内
    // 参数1 msg： 一般都是ByteBuf对象，ByteBuf对象根据内存归属 分为 heap ，direct类型，  当前只讨论 ByteBuf 对象 ，其他 暂时先不讨论
    // 参数2 size： 数据量大小
    // 参数3 promise： 业务如果关注 本次操作成功 或者失败可以手动提交一个跟msg 相关的 promise，promise中可以注册一些和监听者，用户处理结果
    public void addMessage(Object msg, int size, ChannelPromise promise) {
        //参数1 msg
        //参数2 size 数据量大小
        //参数3 total(msg) == size
        //参数4 promise
        //返回一个 包装了 当前 msg 数据的 entry 对象
        Entry entry = Entry.newInstance(msg, size, total(msg), promise);
        //将包装当前 msg 数据的entry 对象 加入到entry 链表中，表示数据 入站 到 出站缓冲区
        if (tailEntry == null) {
            flushedEntry = null;
        } else {
            Entry tail = tailEntry;
            tail.next = entry;
        }
        tailEntry = entry;
        if (unflushedEntry == null) {
            unflushedEntry = entry;
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        //累加 出站 缓冲区 的总大小
        //参数1 entry pendingSize
        //参数2 false
        incrementPendingOutboundBytes(entry.pendingSize, false);
    }

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     */
    //代码不难 很容易懂 ~ 哈哈哈哈
    // 只是将节点标记为flushed，并没有真正发送数据，会跳过已经被取消的节点。
    public void addFlush() {
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        Entry entry = unflushedEntry;
        if (entry != null) {
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                flushedEntry = entry;
            }
            do {
                flushed ++;
                //将entry的promise设为 不可取消 状态
                if (!entry.promise.setUncancellable()) {
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    //设置失败，说明promise已经取消，需要释放消息，并递减挂起的字节数
                    int pending = entry.cancel();
                    // 递减缓冲区的消息字节总数，如果达到低水位线，则将Channel重新设为「可写」状态，并触发回调
                    decrementPendingOutboundBytes(pending, false, true);
                }
                entry = entry.next;
            } while (entry != null); // 不断往后找 待flush的节点

            // All flushed so reset unflushedEntry
            //所有的节点都flush了，置为空
            unflushedEntry = null;
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(long size) {
        incrementPendingOutboundBytes(size, true);
    }

    //累加 出站 缓冲区 的总大小
    //参数1 entry pendingSize
    //参数2 false
    private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
        if (size == 0) {
            return;
        }

        //使用cas 更新 totalPendingSize 字段 ，将size 累加进去
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
        //如果 累加完之后的 值，大于 出站 缓冲区的高水位  则 设置 unwritable 字段 不可写，并且想ch pipeline 发起 unwritable 不可更改
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {
            setUnwritable(invokeLater);
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) {
        decrementPendingOutboundBytes(size, true, true);
    }

    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) {
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
        //  小于最低水位
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater);
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     */
    public Object current() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Return the current message flush progress.
     * @return {@code 0} if nothing was flushed before for the current message or there is no current message
     */
    public long currentProgress() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return 0;
        }
        return entry.progress;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     */
    public void progress(long amount) {
        Entry e = flushedEntry;
        assert e != null;
        ChannelPromise p = e.promise;
        long progress = e.progress + amount;
        e.progress = progress;
        if (p instanceof ChannelProgressivePromise) {
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     */
    public boolean remove() {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        // 一般是  移动 flushedEntry 指向 当前e 的下一个节点 ，并且更新 flushed字段
        removeEntry(e);

        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            // byteBuf 实现了 引用计数  这里safeRelease 更新 引用计数，  最终可能会触发 byteBuf 归还内存逻辑
            ReferenceCountUtil.safeRelease(msg);
            safeSuccess(promise);
            //原子减少 出站 缓冲区的总容量   减去 移除的 entry.pendingSize
            decrementPendingOutboundBytes(size, false, true);
        }

        // recycle the entry
        // 归还 当前 entry 对象 到对象池
        e.recycle();

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }

    private boolean remove0(Throwable cause, boolean notifyWritability) {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e);

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);

            safeFail(promise, cause);
            decrementPendingOutboundBytes(size, false, notifyWritability);
        }

        // recycle the entry
        e.recycle();

        return true;
    }

    private void removeEntry(Entry e) {
        if (-- flushed == 0) {
            // processed everything
            flushedEntry = null;
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else {
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     * 参数 writtenBytes   可能是 一条buffer 的大小，也可能 表示多条 buffer 的大小 ...  或者部分 大小
     */
    public void removeBytes(long writtenBytes) {
        for (;;) {
            //获取flushedEntry 节点 指向的 entry.msg 数量
            Object msg = current();
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            final ByteBuf buf = (ByteBuf) msg;
            final int readerIndex = buf.readerIndex();
            //计算出来 msg 可读 数据量大小
            final int readableBytes = buf.writerIndex() - readerIndex;

            // 条件如果成立 说明 unsafe 写入到 socket底层 缓冲区的 数据量 > flushedEntry.msg 可读数据量大小，
            // if 内的逻辑 就是移处 flushedEntry 代表的 entry
            if (readableBytes <= writtenBytes) {

                if (writtenBytes != 0) {
                    progress(readableBytes);
                    writtenBytes -= readableBytes;
                }

                // 移除当前entry
                remove();
            }
            // 执行到else 说明 unsafe 真正写入到 socket 的数据量 < 当前flushedEntry.msg 可读数据量的
            else { // readableBytes > writtenBytes
                if (writtenBytes != 0) {
                    buf.readerIndex(readerIndex + (int) writtenBytes);
                    progress(writtenBytes);
                }
                break;
            }
        }
        clearNioBuffers();
    }

    // Clear all ByteBuffer from the array so these can be GC'ed.
    // See https://github.com/netty/netty/issues/3837
    private void clearNioBuffers() {
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0;
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null);
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     */
    //将出站缓冲区内的 部分entry.msg 转换成 JDK Channel 依赖的 标准对象  ByteBuffer ，注意 这里返回的 是 ByteBuffer[]
    //参数1 1024  最多转换出来  1024个 ByteBuffer对象
    //参数2 nioBuffers 方法 最多 转换 maxBytes  个字节的 ByteBuf 对象
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) {
        assert maxCount > 0;
        assert maxBytes > 0;
        long nioBufferSize = 0;
        int nioBufferCount = 0;
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 提升了程序性能
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap);
        //循环处理开始节点 flushEntry
        Entry entry = flushedEntry;

        // 循环条件   当前节点 不是 null && 当前节点 不是 unflushedEntry 指向节点 。 ==> 1.循环到末尾 或者 循环到  unflushedEntry
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) {

            //条件成立   说明当前 entry 节点 非取消状态， 所以需要提取它的数据
            if (!entry.cancelled) {
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();
                //有效数据量
                final int readableBytes = buf.writerIndex() - readerIndex;

                // 条件成立 说明 msg 包含待发送数据
                if (readableBytes > 0) {

                    // 条件一： maxBytes - readableBytes < nioBufferSize ==>   maxBytes  < nioBufferSize + readableBytes
                    // ==>   nioBufferSize + readableBytes > maxBytes   ==> 已转换buffer容量大小  +  本次 可转换 大小 > 最大限制 则跳出while 循环
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) {
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - https://linux.die.net//man/2/writev
                        break;
                    }

                    //正常逻辑


                    // 更新总转换量大小  原值 + 本条msg 可读大小
                    nioBufferSize += readableBytes;

                    //默认值count 是 -1
                    int count = entry.count;

                    //大概率会成立  ...
                    if (count == -1) {
                        //noinspection ConstantValueVariableUse
                        // 获取出 byteBuf 底层是由多少 byteBuffer 构成的 ， 在这里  msg 都是direct byteBuf

                        //正常情况下 计算出来的值都是 1  特殊 情况是 CompositeByteBuf
                        entry.count = count = buf.nioBufferCount();
                    }

                    // 计算出 需要多大的 byteBuffer数组
                    int neededSpace = min(maxCount, nioBufferCount + count);
                    //如果需要的数组大小 > 默认值1024 的话，则执行扩容逻辑 ..
                    if (neededSpace > nioBuffers.length) {
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);
                    }
                    //正常情况 ： 条件成立
                    if (count == 1) {

                        ByteBuffer nioBuf = entry.buf;
                        // 一般都会成立  ....
                        if (nioBuf == null) {


                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer

                            // 获取 byteBuf 底层真正的 内存对象 ByteBuffer
                            // 参数1 读索引
                            // 参数2 可读数量容量
                            // chunk
                            // PooledByteBuf#internalNioBuffer
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }

                        //将刚刚转换出来的 byteBuffer 对象 加入到 数组 ...
                        nioBuffers[nioBufferCount++] = nioBuf;
                    } else {
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }

                    if (nioBufferCount >= maxCount) {
                        break;
                    }
                }
            }
            entry = entry.next;
        }

        // 出站缓冲区 记录 有多少 byteBuffer 待出站
        // 出站缓冲区 记录 有多少字节 byteBuffer 待出站
        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) {
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            nioBuffers[nioBufferCount++] = nioBuf;
        }
        return nioBufferCount;
    }

    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public int nioBufferCount() {
        return nioBufferCount;
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public long nioBufferSize() {
        return nioBufferSize;
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     */
    public boolean isWritable() {
        return unwritable == 0;
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     */
    public boolean getUserDefinedWritability(int index) {
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    private void setWritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void setUnwritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | 1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void fireChannelWritabilityChanged(boolean invokeLater) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (invokeLater) {
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {
            pipeline.fireChannelWritabilityChanged();
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     */
    public int size() {
        return flushed;
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     */
    public boolean isEmpty() {
        return flushed == 0;
    }

    void failFlushed(Throwable cause, boolean notify) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    void close(final Throwable cause, final boolean allowChannelOpen) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen);
                }
            });
            return;
        }

        inFail = true;

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        try {
            Entry e = unflushedEntry;
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);

                if (!e.cancelled) {
                    ReferenceCountUtil.safeRelease(e.msg);
                    safeFail(e.promise, cause);
                }
                e = e.recycleAndGetNext();
            }
        } finally {
            inFail = false;
        }
        clearNioBuffers();
    }

    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    public long bytesBeforeUnwritable() {
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0;
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    public long bytesBeforeWritable() {
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes;
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        ObjectUtil.checkNotNull(processor, "processor");

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    static final class Entry {
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });

        //归还 entry 到ObjectPool 使用的句柄
        private final Handle<Entry> handle;
        //组装成链表使用的 字段 ，执行下一个 entry
        Entry next;
        //业务层面的数据，一般msg 都是 ByteBuf 对象
        Object msg;
        //当unsafe调用出站缓冲区.nioBuffers 方法时，被涉及到的entry 都会将它的 msg 转换成 ByteBuffer ，这里是缓存结果使用的
        ByteBuffer[] bufs;
        ByteBuffer buf;
        //业务层面关注 msg 写结果时 提交的 promise
        ChannelPromise promise;
        // 进度
        long progress;
        // msg ByteBuf 有效数据量大小
        long total;
        //ByteBuf 有效数据量大小 + 96 (16 + 6 * 8 + 2 * 8 + 2 * 4 + 1 ==89  )  JVM要求对象占用的内存为8字节的整数倍 所以 >=89 最近接的 8 的整数倍 就是 96
        // Assuming a 64-bit JVM:
        //  - 16 bytes object header
        //  - 6 reference fields
        //  - 2 long fields
        //  - 2 int fields
        //  - 1 boolean field
        //  - padding

        int pendingSize;
        // 当前ByteBuf 底层由多少 ByteBuffer 组成 ，一般都是 1 ，特殊情况 是 CompositeByteBuf  底层可以由 多个 ByteBuf组成
        int count = -1;
        //当前entry 是否取消 刷新 到 socket 默认是false
        boolean cancelled;

        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }

        //参数1 msg
        //参数2 size 数据量大小
        //参数3 total(msg) == size
        //参数4 promise
        //返回一个entry 对象， 并且entry 对象包装 msg 等数据
        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) {
            //从对象池中 获取一个空闲的 entry 对象 如果对象池内没有空闲的 entry 对象，则new Entry(),否则 使用空闲的 entry 对象
            Entry entry = RECYCLER.get();
            //赋值操作
            entry.msg = msg;
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }

        int cancel() {
            if (!cancelled) {
                cancelled = true;
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                ReferenceCountUtil.safeRelease(msg);
                msg = Unpooled.EMPTY_BUFFER;

                pendingSize = 0;
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize;
            }
            return 0;
        }

        void recycle() {
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            handle.recycle(this);
        }

        Entry recycleAndGetNext() {
            Entry next = this.next;
            recycle();
            return next;
        }
    }
}
