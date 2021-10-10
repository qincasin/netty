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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        // 第一步 list中添加 16 32 48 64 ... 496
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        // 第二步 通过左移一位   512 1024  2048  ...  一直到int 溢出  称为负数
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        //  往数据中放置之前初始化好的list 中的数据
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        //参数1： 64 在table_size 中的下标
        //参数2：65536 在table_size 中的下标
        //参数3：1024
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            // 计算 1024 在size_table中的下标
            index = getSizeTableIndex(initial);
            //nextReceiveBufferSize  表示 下一次 分配出来的 byteBuf 容量大小   默认第一次情况下 分配的是容量是 1024
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 条件成立 说明  读取的数据量与评估的数据量 一致，说明ch内还有数据量没有访问完，需要继续读取
            if (bytes == attemptedBytesRead()) {
                // 这个方法 会更新 nextReceiveBufferSize 数据量大小   因为 前面 你已经把预估分配的容量给读满了 可能意味着 ch内有很多数据量还需要读 ,咱们需要更大的容器
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        // 参数  真实读取的数据量   本次从ch内读取的数据量
        // 这个是一个动态伸缩性的 获取容量大小
        private void record(int actualReadBytes) {
            //缩减逻辑
            // 假设 size_table[idx] = 512  =====>  可以推出来    size_table[idx-1] = 496
            // 如果本次读取的数据量 <= 496  ,那么 ch内并没有那么多的数据量， 也就可能不需要这么大的ByteBuf
            // 如果第二次在进行读取的数据量 <= 496 ,那么 ch内并没有那么多的数据量， 也就不需要这么大的ByteBuf
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    //初始阶段定义过  最小不能小于  size_table[minIndex]
                    index = max(index - INDEX_DECREMENT, minIndex);
                    //获取相对 较小的 BuffSize 值 赋值给 nextReceiveBufferSize
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    //第一次的时候 会设置为true
                    decreaseNow = true;
                }
                //增大逻辑
                //条件成立 说明本次 ch 已经将预估的nextReceiveBufferSize容量大小给塞满了，说明ch内的数据量还很多很多，需要右移一位
                // 获取一个更大的 nextReceiveBufferSize ,下一次 会构建出更大的ByteBuf 对象
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        //   参数1 64
        //   参数2 2048
        //   参数3 65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    //   参数1 64
    //   参数2 2048
    //   参数3 65536
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        //start  二分查找算法 查找 minimum 在size数组中>=minimum下标的位置值的第一个元素  (ps:  查找<=minimum 的一个下标 )  -- 下面的逻辑都是
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            //如果小于的话 ，则取size_table[minIndex+1] 下标对应的 的值
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }
        //end
        //  查找大于等于 maximum 值的第一个元素
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        //参数1： 64 在table_size 中的下标
        //参数2：65536 在table_size 中的下标
        //参数3：1024
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
