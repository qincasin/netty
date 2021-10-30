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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Integer.MAX_VALUE;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        //将小碗的面条 合并到 大碗里面(将小碗面条的头 合并到 大碗面条的尾部)
        //参数1: 内存池
        //参数2: 堆积区ByteBuf (大碗)
        //参数3: 小碗
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            if (!cumulation.isReadable() && in.isContiguous()) {
                // If cumulation is empty and input buffer is contiguous, use it directly
                cumulation.release();
                return in;
            }
            try {
                //获取从socket加载的byteBuf可读数据量
                final int required = in.readableBytes();
                //条件一: 成立 说明堆积区的ByteBuf容量 不足以支撑 本次 从socket 加载的数据量 ... 需要扩容
                if (required > cumulation.maxWritableBytes() ||
                        (required > cumulation.maxFastWritableBytes() && cumulation.refCnt() > 1) ||
                        cumulation.isReadOnly()) {
                    // Expand cumulation (by replacing it) under the following conditions:
                    // - cumulation cannot be resized to accommodate the additional data
                    // - cumulation can be expanded with a reallocation operation to accommodate but the buffer is
                    //   assumed to be shared (e.g. refCnt() > 1) and the reallocation may not be safe.
                    // 扩容逻辑
                    //参数1 内存池
                    //参数2 原堆积区
                    //参数3 小碗byteBuf 对象
                    // 返回新的堆积区，新的堆积区 包含原堆积区所有未处理数据和 in(小碗) 内部的所有数据
                    return expandCumulation(alloc, cumulation, in);
                }
                //执行到这里 说明 当前 decoder 的堆积区(大碗) 还有空间 可以盛装 本次 in 内部的全部数据

                cumulation.writeBytes(in, in.readerIndex(), required);

                //更新 in 的 读索引 和 它的 写索引一致 ，表示in 的 数据全部处理了
                in.readerIndex(in.writerIndex());

                // 返回原堆积区
                return cumulation;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                //将in 占用的 内存归还到 它申请内存的地方
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            if (!cumulation.isReadable()) {
                cumulation.release();
                return in;
            }
            CompositeByteBuf composite = null;
            try {
                if (cumulation instanceof CompositeByteBuf && cumulation.refCnt() == 1) {
                    composite = (CompositeByteBuf) cumulation;
                    // Writer index must equal capacity if we are going to "write"
                    // new components to the end
                    if (composite.writerIndex() != composite.capacity()) {
                        composite.capacity(composite.writerIndex());
                    }
                } else {
                    composite = alloc.compositeBuffer(Integer.MAX_VALUE).addFlattenedComponents(true, cumulation);
                }
                composite.addFlattenedComponents(true, in);
                in = null;
                return composite;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak
                    in.release();
                    // Also release any new buffer allocated if we're not returning it
                    if (composite != null && composite != cumulation) {
                        composite.release();
                    }
                }
            }
        }
    };

    //初始化状态
    private static final byte STATE_INIT = 0;
    //调用子类实现的decode方法时期状态
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    //当decode从pipeline 移除出去后，状态会修改为 remove_pending  状态
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    //堆积区 ByteBuf (大碗)
    ByteBuf cumulation;
    //小碗面条来了之后，cumulator 负责将其合并到 大碗(cumulation)内
    private Cumulator cumulator = MERGE_CUMULATOR;
    //默认是false，如果是true的话，每次ChannelRead 只解码一个frame
    private boolean singleDecode;
    //false 代表cumulation 值为 null，否则代表cumulation 值不为null
    // 当cumulation 内部数据完全处理完毕后，Decode 会会把cumulation占用的对外内存释放，并且将cumulation字段置为null
    private boolean first;

    /**
     * This flag is used to determine if we need to call {@link ChannelHandlerContext#read()} to consume more data
     * when {@link ChannelConfig#isAutoRead()} is {@code false}.
     */
    private boolean firedChannelRead;

    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     */
    private byte decodeState = STATE_INIT;
    //整理cumulation 已读空间的阈值，当cumulation readIndex 比较大时，cumulation 可写的空间就会变小，
    //通过整理的方法，可以调整 cumulation的readIndex 、 writeIndex 让其复用 空闲的 内存
    private int discardAfterReads = 16;
    //表示当前cumulation 已经累积进去多少小碗 数据 ....
    private int numReads;

    protected ByteToMessageDecoder() {
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        this.cumulator = ObjectUtil.checkNotNull(cumulator, "cumulator");
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;
            numReads = 0;
            int readable = buf.readableBytes();
            if (readable > 0) {
                ctx.fireChannelRead(buf);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //msg 是ch.unsafe 到ch底层关联的socket读取数据时 使用的byteBuf(小碗)
        if (msg instanceof ByteBuf) {
            //可以理解为就是一个list
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                //false 代表cumulation 值为 null，否则代表cumulation 值不为null
                first = cumulation == null;
                //将小碗的面条 合并到 大碗里面(将小碗面条的头 合并到 大碗面条的尾部)
                //参数1: 内存池
                //参数2: 堆积区ByteBuf (大碗)
                //参数3: 小碗
                cumulation = cumulator.cumulate(ctx.alloc(),
                        first ? Unpooled.EMPTY_BUFFER : cumulation, (ByteBuf) msg);
                //参数1: ctx 包装当前handler 的 context
                //参数2: cumulation 堆积区
                //参数3: out 已解码的数据集合List
                callDecode(ctx, cumulation, out);

                //总结下：
                //1.cumulation 堆积区内的数据 全部解析成 frame了，并且发送到decoder 后面的handler 去处理了 (out 内可能剩余有 未发送的 frame)
                //2.cumulation 堆积区内的数据 是半包数据....
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                try {
                    //条件成立 说明 cumulation 堆积区内的数据 全部处理完成了 ...
                    if (cumulation != null && !cumulation.isReadable()) {
                        //将堆积区占用的内存释放
                        numReads = 0;
                        cumulation.release();
                        //堆积区字段置为 null
                        cumulation = null;
                    } else if (++numReads >= discardAfterReads) { //什么时候执行到else？  堆积区内的数据 是半包数据...
                        // ++numReads 记录当前堆积区 累加过的 数据次数

                        // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                        // See https://github.com/netty/netty/issues/4275
                        numReads = 0;
                        //调整堆积区 ByteBuf 的 ReadIndex 和 writeIndex ，重复利用空闲 内存空间
                        discardSomeReadBytes();
                    }

                    int size = out.size();
                    //将out 内 未发送的数据，也向后handler 通知过去
                    firedChannelRead |= out.insertSinceRecycled();
                    //将out 这个list 归还给对象池
                    fireChannelRead(ctx, out, size);
                } finally {
                    out.recycle();
                }
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (!firedChannelRead && !ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        firedChannelRead = false;
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                fireChannelRead(ctx, out, size);
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            // If callDecode(...) removed the handle from the pipeline we should not call decodeLast(...) as this would
            // be unexpected.
            if (!ctx.isRemoved()) {
                // Use Unpooled.EMPTY_BUFFER if cumulation become null after calling callDecode(...).
                // See https://github.com/netty/netty/issues/10802.
                ByteBuf buffer = cumulation == null ? Unpooled.EMPTY_BUFFER : cumulation;
                decodeLast(ctx, buffer, out);
            }
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     */
    //参数1: ctx 包装当前handler 的 context
    //参数2: cumulation 堆积区
    //参数3: out 已解码的数据集合List
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            //大条件：堆积区内已经没有待处理的 数据了
            while (in.isReadable()) {
                //刚调用 传过来的out 这个list size 是 0
                int outSize = out.size();

                if (outSize > 0) {
                    fireChannelRead(ctx, out, outSize);
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    if (ctx.isRemoved()) {
                        break;
                    }
                }

                // 获取堆积区 待处理数据量 大小
                int oldInputLength = in.readableBytes();
                //内部调用decode 方法，该方法由子类实现，子类通过将in 的数据 解析成 业务协议 的 frame ，并且放入到 out 这个list中
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                if (ctx.isRemoved()) {
                    break;
                }

                //条件成立 outSize == out.size() ==0  一般是这种情况 ....
                if (out.isEmpty()) {
                    //说明 in 堆积区内的数据 属于半包数据 ...
                    if (oldInputLength == in.readableBytes()) {
                        break;
                    } else {
                        //魔幻 丢弃数据 ....
                        continue;
                    }
                }

                //前提条件 outSize != out.size()
                //说明 你在 decode 阶段 肯定已经解析出来数据了，且已经放入到out list 内中了。
                //都解析出 frame 了，堆积区大小 没变。。。 肯定是子类实现的 逻辑有问题了....
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                //默认是false，如果是true的话，每次ChannelRead 只解码一个frame
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            decode(ctx, in, out);
        } finally {
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            decodeState = STATE_INIT;
            if (removePending) {
                fireChannelRead(ctx, out, out.size());
                out.clear();
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    // 扩容逻辑
    //参数1 内存池
    //参数2 原堆积区
    //参数3 小碗byteBuf 对象
    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf oldCumulation, ByteBuf in) {
        //获取原来堆积区 未处理的数据量大小
        int oldBytes = oldCumulation.readableBytes();
        //获取本次待处理的数据量
        int newBytes = in.readableBytes();
        //根据上面两个容量 计算出一个总容量
        int totalBytes = oldBytes + newBytes;
        //alloc.calculateNewCapacity(totalBytes, MAX_VALUE) 返回一个合适的 size，总之 这个size>=totalBytes
        //到内存池 申请一块内存为 size 大小的 buffer
        ByteBuf newCumulation = alloc.buffer(alloc.calculateNewCapacity(totalBytes, MAX_VALUE));
        //待释放的内存
        ByteBuf toRelease = newCumulation;
        try {
            // This avoids redundant checks and stack depth compared to calling writeBytes(...)
            //将老的堆积区数据全部导入到新的堆积区数据中
            newCumulation.setBytes(0, oldCumulation, oldCumulation.readerIndex(), oldBytes)
                    //将小碗内的数据也写入到新的堆积区内
                .setBytes(oldBytes, in, in.readerIndex(), newBytes)
                    //更新写索引为totalByte
                .writerIndex(totalBytes);
            //更新小碗byteBuf的读索引 和  写索引一致，表示 这块byteBuf 都处理完了
            in.readerIndex(in.writerIndex());
            // toRelease 指向老的堆积区，后面finally会释放老的堆积区
            toRelease = oldCumulation;
            return newCumulation;
        } finally {
            //如果上面报错的话，需要将待释放的内存释放掉
            toRelease.release();
        }
    }

    /**
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
