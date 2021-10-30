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
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    //业务层指定的协议 frame 最大长度，如果 客户端发来的业务层 数据包  超过此长度， 解码器需要抛异常
    private final int maxLength;
    //是否快速失败  默认是false
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast;
    // 是否跳过 "分隔符" 字节
    private final boolean stripDelimiter;

    /** True if we're discarding input because we're already over maxLength.  */
    // 是否为丢弃模式  true 表示当前是 丢弃模式 false 正常模式
    private boolean discarding;
    //记录丢弃模式 的时候，当前已丢弃的数据量
    private int discardedBytes;

    /** Last scan position. */
    // 上一次 从 堆积区 的扫描位点， 只有堆积区是半包的时候 才有值
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        // 从堆积区 ByteBuf 查找换行符 的位置，可能返回 -1 ，表示从当前堆积区
        final int eol = findEndOfLine(buffer);
        //条件成立  !discarding 说明 是正常模式 ，非丢弃模式；
        if (!discarding) {
            // 条件成立  说明 在堆积区 ByteBuf 内查找到 换行 符了
            if (eol >= 0) {
                //最终指向业务层的 数据帧
                final ByteBuf frame;
                // 计算出 当前 业务层数据包 长度
                final int length = eol - buffer.readerIndex();
                //delimLength 表示 换行符 长度 ，如果换行符是 '\r\n' 则 2，否则 '\n' 则1
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                //条件成立 说明 本次的数据包长度 大于 业务层指定的长度 ... 需要 跳过 并且 抛异常
                if (length > maxLength) {
                    //将整个数据包 跳过
                    buffer.readerIndex(eol + delimLength);
                    //报错之类的 发送 事件
                    fail(ctx, length);
                    //返回null，表示未能解析出满足 业务层 协议的 数据包
                    return null;
                }

                //正常逻辑了

                //处理换行符 是否跳过逻辑
                if (stripDelimiter) {
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength);
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }
                //返回正常 切片出来的数据包
                return frame;
            } else { // 执行到这里 说明堆积区 内未查找到  换行符

                //条件成立 说明 堆积区 内的数据量 已经超过 最大帧 长度了，需要开启丢弃模式
                final int length = buffer.readableBytes();
                if (length > maxLength) {
                    //设置丢弃的长度
                    discardedBytes = length;
                    buffer.readerIndex(buffer.writerIndex());
                    //开启丢弃模式
                    discarding = true;
                    offset = 0;
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                //返回null 未能从堆积区中解析出数据
                return null;
            }
        } else {
            //执行到这里  说明 当前decoder 处于丢弃模式
            if (eol >= 0) {
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                discarding = false;
                if (!failFast) {
                    fail(ctx, length);
                }
            } else { //执行到这里，说明仍然未找到换行符 ， 需要继续 丢弃数据 ，并保持 丢弃模式
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        //堆积区 总大小
        int totalLength = buffer.readableBytes();
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        //条件成立  说明 现在 堆积区 内查找到换行符了
        if (i >= 0) {
            offset = 0;
            // 条件成立  说明在堆积区 内查找到的 换行符 是"\r\n" ;
            // 虽然我们找到的是\n位置点，但是我们这个时候需要判断下它的前一个位置点是不是\r的位置点，如果成立，说明我们应该返回前一个\r位置点，而不是当前的\n位置点
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            //执行到else 说明在 堆积区 内 未查找到到 换行符    需要设置 offset 为totalLength
            //因为当前堆积区内 是一个半包 业务数据，下次堆积区 再次积累新数据之后，在调用当前decoder 需要从offset 开始检查 换行符 ，提高工作效率
            offset = totalLength;
        }
        return i;
    }
}
