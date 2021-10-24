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

package io.netty.buffer;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PooledByteBufAllocator.class);
    private static final int DEFAULT_NUM_HEAP_ARENA;
    private static final int DEFAULT_NUM_DIRECT_ARENA;

    private static final int DEFAULT_PAGE_SIZE;
    private static final int DEFAULT_MAX_ORDER; // 8192 << 11 = 16 MiB per chunk
    private static final int DEFAULT_SMALL_CACHE_SIZE;
    private static final int DEFAULT_NORMAL_CACHE_SIZE;
    static final int DEFAULT_MAX_CACHED_BUFFER_CAPACITY;
    private static final int DEFAULT_CACHE_TRIM_INTERVAL;
    private static final long DEFAULT_CACHE_TRIM_INTERVAL_MILLIS;
    private static final boolean DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    private static final int DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT;
    static final int DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK;

    private static final int MIN_PAGE_SIZE = 4096;
    private static final int MAX_CHUNK_SIZE = (int) (((long) Integer.MAX_VALUE + 1) / 2);

    private final Runnable trimTask = new Runnable() {
        @Override
        public void run() {
            PooledByteBufAllocator.this.trimCurrentThreadCache();
        }
    };

    static {
        int defaultAlignment = SystemPropertyUtil.getInt(
                "io.netty.allocator.directMemoryCacheAlignment", 0);
        //  默认一页 内存大小 8K
        int defaultPageSize = SystemPropertyUtil.getInt("io.netty.allocator.pageSize", 8192);
        Throwable pageSizeFallbackCause = null;
        try {
            validateAndCalculatePageShifts(defaultPageSize, defaultAlignment);
        } catch (Throwable t) {
            pageSizeFallbackCause = t;
            defaultPageSize = 8192;
            defaultAlignment = 0;
        }
        DEFAULT_PAGE_SIZE = defaultPageSize;
        //  默认是 0
        DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT = defaultAlignment;

        // PoolChunk 内部使用 一棵满二叉树 表示内存 占用情况   这棵树最深 是 11
        int defaultMaxOrder = SystemPropertyUtil.getInt("io.netty.allocator.maxOrder", 11);
        Throwable maxOrderFallbackCause = null;
        try {
            validateAndCalculateChunkSize(DEFAULT_PAGE_SIZE, defaultMaxOrder);
        } catch (Throwable t) {
            maxOrderFallbackCause = t;
            defaultMaxOrder = 11;
        }
        // 设置常量 树深度
        DEFAULT_MAX_ORDER = defaultMaxOrder;

        // Determine reasonable default for nHeapArena and nDirectArena.
        // Assuming each arena has 3 chunks, the pool should not consume more than 50% of max memory.
        final Runtime runtime = Runtime.getRuntime();

        /*
         * We use 2 * available processors by default to reduce contention as we use 2 * available processors for the
         * number of EventLoops in NIO and EPOLL as well. If we choose a smaller number we will run into hot spots as
         * allocation and de-allocation needs to be synchronized on the PoolArena.
         *
         * See https://github.com/netty/netty/issues/3888.
         */

        // 计算出 默认 最少的 arena 个数  默认 是 cpu * 2
        final int defaultMinNumArena = NettyRuntime.availableProcessors() * 2;

        // 8192 << 11 = 16 MiB per chunk      8192  转换为 2进制  然后 左移 11位 ，换算成 十进制后，在转换为 MB 就是 16mb
        // 默认一个 chunk 管理 16mb 的真实 内存
        final int defaultChunkSize = DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER;

        // 可以暂时理解为 cpu * 2
        DEFAULT_NUM_HEAP_ARENA = Math.max(0,
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numHeapArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                runtime.maxMemory() / defaultChunkSize / 2 / 3)));


        // 可以暂时理解为 cpu * 2
        DEFAULT_NUM_DIRECT_ARENA = Math.max(0,
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numDirectArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                PlatformDependent.maxDirectMemory() / defaultChunkSize / 2 / 3)));




        // cache sizes
        // SmallMemoryRegionCache 内部 可以 缓存{256}个内存位置信息
        DEFAULT_SMALL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.smallCacheSize", 256);
        // NarmalMemoryRegionCache 内部 可以 缓存{64}个内存位置信息
        DEFAULT_NORMAL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.normalCacheSize", 64);

        // 32 kb is the default maximum capacity of the cached buffer. Similar to what is explained in
        // 'Scalable memory allocation using jemalloc'
        // 32k 表示memoryRegionCache 最大可以缓存的内存规格 是 32k
        DEFAULT_MAX_CACHED_BUFFER_CAPACITY = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedBufferCapacity", 32 * 1024);

        // the number of threshold of allocations when cached entries will be freed up if not frequently used
        DEFAULT_CACHE_TRIM_INTERVAL = SystemPropertyUtil.getInt(
                "io.netty.allocator.cacheTrimInterval", 8192);

        if (SystemPropertyUtil.contains("io.netty.allocation.cacheTrimIntervalMillis")) {
            logger.warn("-Dio.netty.allocation.cacheTrimIntervalMillis is deprecated," +
                    " use -Dio.netty.allocator.cacheTrimIntervalMillis");

            if (SystemPropertyUtil.contains("io.netty.allocator.cacheTrimIntervalMillis")) {
                // Both system properties are specified. Use the non-deprecated one.
                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                        "io.netty.allocator.cacheTrimIntervalMillis", 0);
            } else {
                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                        "io.netty.allocation.cacheTrimIntervalMillis", 0);
            }
        } else {
            DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                    "io.netty.allocator.cacheTrimIntervalMillis", 0);
        }

        // 是否 全部的 线程 都使用 PoolThreadCache 技术，默认 是true， 表示都使用
        DEFAULT_USE_CACHE_FOR_ALL_THREADS = SystemPropertyUtil.getBoolean(
                "io.netty.allocator.useCacheForAllThreads", true);

        // Use 1023 by default as we use an ArrayDeque as backing storage which will then allocate an internal array
        // of 1024 elements. Otherwise we would allocate 2048 and only use 1024 which is wasteful.
        DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedByteBuffersPerChunk", 1023);

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.allocator.numHeapArenas: {}", DEFAULT_NUM_HEAP_ARENA);
            logger.debug("-Dio.netty.allocator.numDirectArenas: {}", DEFAULT_NUM_DIRECT_ARENA);
            if (pageSizeFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE);
            } else {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE, pageSizeFallbackCause);
            }
            if (maxOrderFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER);
            } else {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER, maxOrderFallbackCause);
            }
            logger.debug("-Dio.netty.allocator.chunkSize: {}", DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER);
            logger.debug("-Dio.netty.allocator.smallCacheSize: {}", DEFAULT_SMALL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.normalCacheSize: {}", DEFAULT_NORMAL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.maxCachedBufferCapacity: {}", DEFAULT_MAX_CACHED_BUFFER_CAPACITY);
            logger.debug("-Dio.netty.allocator.cacheTrimInterval: {}", DEFAULT_CACHE_TRIM_INTERVAL);
            logger.debug("-Dio.netty.allocator.cacheTrimIntervalMillis: {}", DEFAULT_CACHE_TRIM_INTERVAL_MILLIS);
            logger.debug("-Dio.netty.allocator.useCacheForAllThreads: {}", DEFAULT_USE_CACHE_FOR_ALL_THREADS);
            logger.debug("-Dio.netty.allocator.maxCachedByteBuffersPerChunk: {}",
                    DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK);
        }
    }

    public static final PooledByteBufAllocator DEFAULT =
            new PooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    private final PoolArena<byte[]>[] heapArenas;
    private final PoolArena<ByteBuffer>[] directArenas;
    private final int smallCacheSize;
    private final int normalCacheSize;
    private final List<PoolArenaMetric> heapArenaMetrics;
    private final List<PoolArenaMetric> directArenaMetrics;
    private final PoolThreadLocalCache threadCache;
    private final int chunkSize;
    private final PooledByteBufAllocatorMetric metric;

    public PooledByteBufAllocator() {
        this(false);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(boolean preferDirect) {
        // 参数1 preferDirect 一般是 true
        // 参数2 DEFAULT_NUM_HEAP_ARENA    堆arena 个数  cpu * 2
        // 参数3 DEFAULT_NUM_DIRECT_ARENA    非堆arena 个数  cpu * 2
        // 参数4 DEFAULT_PAGE_SIZE 默认页大小 8K
        // 参数5 DEFAULT_MAX_ORDER 11  深度 11
        this(preferDirect, DEFAULT_NUM_HEAP_ARENA, DEFAULT_NUM_DIRECT_ARENA, DEFAULT_PAGE_SIZE, DEFAULT_MAX_ORDER);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(false, nHeapArena, nDirectArena, pageSize, maxOrder);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        // 参数1 preferDirect 一般是 true
        // 参数2 DEFAULT_NUM_HEAP_ARENA    堆arena 个数  cpu * 2
        // 参数3 DEFAULT_NUM_DIRECT_ARENA    非堆arena 个数  cpu * 2
        // 参数4 DEFAULT_PAGE_SIZE 默认页大小 8K
        // 参数5 DEFAULT_MAX_ORDER 11  深度 11
        // 又增加了 几个 cache的 参数
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             0, DEFAULT_SMALL_CACHE_SIZE, DEFAULT_NORMAL_CACHE_SIZE);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder, smallCacheSize,
                // 参数 DEFAULT_USE_CACHE_FOR_ALL_THREADS 是否 全部的 线程 都使用 PoolThreadCache 技术，默认 是true， 表示都使用
                // DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT 对其填充 是个 0 值
             normalCacheSize, DEFAULT_USE_CACHE_FOR_ALL_THREADS, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder, int tinyCacheSize,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads);
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean, int)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads, directMemoryCacheAlignment);
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        // 将申请偏向 堆  还是 非堆    信息交给 父类  父类 根据该信息 会进行 路由
        super(preferDirect);

        //threadcache   和 threadLocal 非常类似  每个线程 到threadcache 内可以获取到一个 当前线程自己的 PoolThreadCache 对象
        threadCache = new PoolThreadLocalCache(useCacheForAllThreads);

        // 赋值  256
        this.smallCacheSize = smallCacheSize;
        // 64
        this.normalCacheSize = normalCacheSize;

        if (directMemoryCacheAlignment != 0) {
            if (!PlatformDependent.hasAlignDirectByteBuffer()) {
                throw new UnsupportedOperationException("Buffer alignment is not supported. " +
                        "Either Unsafe or ByteBuffer.alignSlice() must be available.");
            }

            // Ensure page size is a whole multiple of the alignment, or bump it to the next whole multiple.
            pageSize = (int) PlatformDependent.align(pageSize, directMemoryCacheAlignment);
        }

        // 根据 pageSize 和 满二叉树叶子节点深度值， 计算出 chunkSize ，默认情况是 pageSize 8k，maxOrder 11， 计算出来后 是16mb
        chunkSize = validateAndCalculateChunkSize(pageSize, maxOrder);

        checkPositiveOrZero(nHeapArena, "nHeapArena");
        checkPositiveOrZero(nDirectArena, "nDirectArena");

        checkPositiveOrZero(directMemoryCacheAlignment, "directMemoryCacheAlignment");
        if (directMemoryCacheAlignment > 0 && !isDirectMemoryCacheAlignmentSupported()) {
            throw new IllegalArgumentException("directMemoryCacheAlignment is not supported");
        }

        if ((directMemoryCacheAlignment & -directMemoryCacheAlignment) != directMemoryCacheAlignment) {
            throw new IllegalArgumentException("directMemoryCacheAlignment: "
                    + directMemoryCacheAlignment + " (expected: power of two)");
        }





        // pageSize is 8k, 得出 pageShifts = 13 ，  1<<13 ==> pageSize
        int pageShifts = validateAndCalculatePageShifts(pageSize, directMemoryCacheAlignment);

        if (nHeapArena > 0) {
            // 假设平台 cpu 8 ，则这里会创建一个 长度16 的 heapArena 数组
            heapArenas = newArenaArray(nHeapArena);
            // 监控报表相关的 ....
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(heapArenas.length);

            //循环 16次 创建 16个heapArena 对象，并且将这些对象放入到 数组内
            for (int i = 0; i < heapArenas.length; i ++) {
                PoolArena.HeapArena arena = new PoolArena.HeapArena(this,
                        pageSize, pageShifts, chunkSize,
                        directMemoryCacheAlignment);
                heapArenas[i] = arena;
                metrics.add(arena);
            }
            heapArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            heapArenas = null;
            heapArenaMetrics = Collections.emptyList();
        }

        if (nDirectArena > 0) {
            // 假设平台 cpu 8 ，则这里会创建一个 长度16 的 directArena 数组
            directArenas = newArenaArray(nDirectArena);

            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(directArenas.length);
            for (int i = 0; i < directArenas.length; i ++) {

                // 参数1 this  allocator 对象
                // 参数2 pageSize 8K
                // 参数3 pageShifts  13 1<<<13 ==> pageSize
                // 参数4 chunkSize 16mb
                // 参数5 directMemoryCacheAlignment 0
                PoolArena.DirectArena arena = new PoolArena.DirectArena(
                        this, pageSize, pageShifts, chunkSize, directMemoryCacheAlignment);
                directArenas[i] = arena;
                metrics.add(arena);
            }
            directArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            directArenas = null;
            directArenaMetrics = Collections.emptyList();
        }
        metric = new PooledByteBufAllocatorMetric(this);
    }

    @SuppressWarnings("unchecked")
    private static <T> PoolArena<T>[] newArenaArray(int size) {
        return new PoolArena[size];
    }

    private static int validateAndCalculatePageShifts(int pageSize, int alignment) {
        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: " + MIN_PAGE_SIZE + ')');
        }

        // 8K & 8k -1  二进制 运算后 == 0
        //0b 10000000000000
        //0b 01111111111111
        //0b 00000000000000
        if ((pageSize & pageSize - 1) != 0) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: power of 2)");
        }

        if (pageSize < alignment) {
            throw new IllegalArgumentException("Alignment cannot be greater than page size. " +
                    "Alignment: " + alignment + ", page size: " + pageSize + '.');
        }
        //0b 10000000000000 ==> 0b 0000 0000 0000 0000 0010 0000 0000 0000
        //numberOfLeadingZeros 1 前面有多少个0
        //32 - 1 - 18 ==> 13   return 13 .  这个时候 可以推出来  1  左移 13位 就是 pageSize 的值  1<< 13 ==> 8192
        // Logarithm base 2. At this point we know that pageSize is a power of two.
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize);
    }

    private static int validateAndCalculateChunkSize(int pageSize, int maxOrder) {
        if (maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder: " + maxOrder + " (expected: 0-14)");
        }

        // Ensure the resulting chunkSize does not overflow.
        int chunkSize = pageSize;
        for (int i = maxOrder; i > 0; i --) {
            if (chunkSize > MAX_CHUNK_SIZE / 2) {
                throw new IllegalArgumentException(String.format(
                        "pageSize (%d) << maxOrder (%d) must not exceed %d", pageSize, maxOrder, MAX_CHUNK_SIZE));
            }
            chunkSize <<= 1;
        }

        // 8192  最多 左移 11次，结果是 16777216   ==> 16M
        return chunkSize;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        PoolThreadCache cache = threadCache.get();
        PoolArena<byte[]> heapArena = cache.heapArena;

        final ByteBuf buf;
        if (heapArena != null) {
            buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    new UnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        return toLeakAwareBuffer(buf);
    }

    @Override
    // initialCapacity 业务需求内存大小
    // maxCapacity  最大内存大小
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {

        // 每个线程都会去获取一个 当前线程 的 PoolThreadCache对象

        // 获取 当前线程相关的PoolThreadCache 对象，注意 每个线程都有一个自己的 PoolThreadCache 对象，并且
        // 每个 PoolThreadCache 对象 内部都有两个区域 ，一个是HeapArena  另一个是 DirectArena
        PoolThreadCache cache = threadCache.get();
        //获取分配给当前线程使用的 directArena区域，后面要在 这块区域内 申请内存
        PoolArena<ByteBuffer> directArena = cache.directArena;

        final ByteBuf buf;
        //正常都会成立   ！！ 核心入口！！！
        if (directArena != null) {
            // 参数1 cache 当前线程相关的 PoolThreadCache 对象
            // 参数2 initialCapacity 业务层需要的内存容量大小
            // 参数3 maxCapacity 最大内存大小
            buf = directArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    UnsafeByteBufUtil.newUnsafeDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        return toLeakAwareBuffer(buf);
    }

    /**
     * Default number of heap arenas - System Property: io.netty.allocator.numHeapArenas - default 2 * cores
     */
    public static int defaultNumHeapArena() {
        return DEFAULT_NUM_HEAP_ARENA;
    }

    /**
     * Default number of direct arenas - System Property: io.netty.allocator.numDirectArenas - default 2 * cores
     */
    public static int defaultNumDirectArena() {
        return DEFAULT_NUM_DIRECT_ARENA;
    }

    /**
     * Default buffer page size - System Property: io.netty.allocator.pageSize - default 8192
     */
    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * Default maximum order - System Property: io.netty.allocator.maxOrder - default 11
     */
    public static int defaultMaxOrder() {
        return DEFAULT_MAX_ORDER;
    }

    /**
     * Default thread caching behavior - System Property: io.netty.allocator.useCacheForAllThreads - default true
     */
    public static boolean defaultUseCacheForAllThreads() {
        return DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    }

    /**
     * Default prefer direct - System Property: io.netty.noPreferDirect - default false
     */
    public static boolean defaultPreferDirect() {
        return PlatformDependent.directBufferPreferred();
    }

    /**
     * Default tiny cache size - default 0
     *
     * @deprecated Tiny caches have been merged into small caches.
     */
    @Deprecated
    public static int defaultTinyCacheSize() {
        return 0;
    }

    /**
     * Default small cache size - System Property: io.netty.allocator.smallCacheSize - default 256
     */
    public static int defaultSmallCacheSize() {
        return DEFAULT_SMALL_CACHE_SIZE;
    }

    /**
     * Default normal cache size - System Property: io.netty.allocator.normalCacheSize - default 64
     */
    public static int defaultNormalCacheSize() {
        return DEFAULT_NORMAL_CACHE_SIZE;
    }

    /**
     * Return {@code true} if direct memory cache alignment is supported, {@code false} otherwise.
     */
    public static boolean isDirectMemoryCacheAlignmentSupported() {
        return PlatformDependent.hasUnsafe();
    }

    @Override
    public boolean isDirectBufferPooled() {
        return directArenas != null;
    }

    /**
     * Returns {@code true} if the calling {@link Thread} has a {@link ThreadLocal} cache for the allocated
     * buffers.
     */
    @Deprecated
    public boolean hasThreadLocalCache() {
        return threadCache.isSet();
    }

    /**
     * Free all cached buffers for the calling {@link Thread}.
     */
    @Deprecated
    public void freeThreadLocalCache() {
        threadCache.remove();
    }

    final class PoolThreadLocalCache extends FastThreadLocal<PoolThreadCache> {
        //true
        private final boolean useCacheForAllThreads;

        PoolThreadLocalCache(boolean useCacheForAllThreads) {
            this.useCacheForAllThreads = useCacheForAllThreads;
        }

        @Override
        protected synchronized PoolThreadCache initialValue() {
            // 返回一个 arenas 数组 内，线程共享数 最低的 arena 对象
            final PoolArena<byte[]> heapArena = leastUsedArena(heapArenas);
            final PoolArena<ByteBuffer> directArena = leastUsedArena(directArenas);

            final Thread current = Thread.currentThread();
            // 条件一一般都成立， 条件二 netty 一般都是 NioEvnetLoop 线程，NioEventLoop 线程 由DefaultThreadFactory 创建，它创建的类型 都是FastThreadLocalThread线程
            if (useCacheForAllThreads || current instanceof FastThreadLocalThread) {
                // 创建 归属当前线程 的 cache对象
                // 参数1 heapArena
                // 参数2 directArena
                // 参数3 smallCacheSize 256
                // 参数4 narmalCacheSize 64
                // 参数5 DEFAULT_MAX_CACHED_BUFFER_CAPACITY 32K
                // 参数6 DEFAULT_CACHE_TRIM_INTERVAL 8192  是一个阈值
                final PoolThreadCache cache = new PoolThreadCache(
                        heapArena, directArena, smallCacheSize, normalCacheSize,
                        DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL);

                if (DEFAULT_CACHE_TRIM_INTERVAL_MILLIS > 0) {
                    final EventExecutor executor = ThreadExecutorMap.currentExecutor();
                    if (executor != null) {
                        executor.scheduleAtFixedRate(trimTask, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS,
                                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
                return cache;
            }
            // No caching so just use 0 as sizes.
            return new PoolThreadCache(heapArena, directArena, 0, 0, 0, 0);
        }

        @Override
        protected void onRemoval(PoolThreadCache threadCache) {
            threadCache.free(false);
        }

        // 返回一个 arenas 数组 内，线程共享数 最低的 arena 对象
        private <T> PoolArena<T> leastUsedArena(PoolArena<T>[] arenas) {
            if (arenas == null || arenas.length == 0) {
                return null;
            }

            PoolArena<T> minArena = arenas[0];
            for (int i = 1; i < arenas.length; i++) {
                PoolArena<T> arena = arenas[i];
                if (arena.numThreadCaches.get() < minArena.numThreadCaches.get()) {
                    minArena = arena;
                }
            }

            return minArena;
        }
    }

    @Override
    public PooledByteBufAllocatorMetric metric() {
        return metric;
    }

    /**
     * Return the number of heap arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numHeapArenas()}.
     */
    @Deprecated
    public int numHeapArenas() {
        return heapArenaMetrics.size();
    }

    /**
     * Return the number of direct arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numDirectArenas()}.
     */
    @Deprecated
    public int numDirectArenas() {
        return directArenaMetrics.size();
    }

    /**
     * Return a {@link List} of all heap {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#heapArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> heapArenas() {
        return heapArenaMetrics;
    }

    /**
     * Return a {@link List} of all direct {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#directArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> directArenas() {
        return directArenaMetrics;
    }

    /**
     * Return the number of thread local caches used by this {@link PooledByteBufAllocator}.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numThreadLocalCaches()}.
     */
    @Deprecated
    public int numThreadLocalCaches() {
        PoolArena<?>[] arenas = heapArenas != null ? heapArenas : directArenas;
        if (arenas == null) {
            return 0;
        }

        int total = 0;
        for (PoolArena<?> arena : arenas) {
            total += arena.numThreadCaches.get();
        }

        return total;
    }

    /**
     * Return the size of the tiny cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#tinyCacheSize()}.
     */
    @Deprecated
    public int tinyCacheSize() {
        return 0;
    }

    /**
     * Return the size of the small cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#smallCacheSize()}.
     */
    @Deprecated
    public int smallCacheSize() {
        return smallCacheSize;
    }

    /**
     * Return the size of the normal cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#normalCacheSize()}.
     */
    @Deprecated
    public int normalCacheSize() {
        return normalCacheSize;
    }

    /**
     * Return the chunk size for an arena.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#chunkSize()}.
     */
    @Deprecated
    public final int chunkSize() {
        return chunkSize;
    }

    final long usedHeapMemory() {
        return usedMemory(heapArenas);
    }

    final long usedDirectMemory() {
        return usedMemory(directArenas);
    }

    private static long usedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numActiveBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    /**
     * Returns the number of bytes of heap memory that is currently pinned to heap buffers allocated by a
     * {@link ByteBufAllocator}, or {@code -1} if unknown.
     * A buffer can pin more memory than its {@linkplain ByteBuf#capacity() capacity} might indicate,
     * due to implementation details of the allocator.
     */
    public final long pinnedHeapMemory() {
        return pinnedMemory(heapArenas);
    }

    /**
     * Returns the number of bytes of direct memory that is currently pinned to direct buffers allocated by a
     * {@link ByteBufAllocator}, or {@code -1} if unknown.
     * A buffer can pin more memory than its {@linkplain ByteBuf#capacity() capacity} might indicate,
     * due to implementation details of the allocator.
     */
    public final long pinnedDirectMemory() {
        return pinnedMemory(directArenas);
    }

    private static long pinnedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numPinnedBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    final PoolThreadCache threadCache() {
        PoolThreadCache cache =  threadCache.get();
        assert cache != null;
        return cache;
    }

    /**
     * Trim thread local cache for the current {@link Thread}, which will give back any cached memory that was not
     * allocated frequently since the last trim operation.
     *
     * Returns {@code true} if a cache for the current {@link Thread} exists and so was trimmed, false otherwise.
     */
    public boolean trimCurrentThreadCache() {
        PoolThreadCache cache = threadCache.getIfExists();
        if (cache != null) {
            cache.trim();
            return true;
        }
        return false;
    }

    /**
     * Returns the status of the allocator (which contains all metrics) as string. Be aware this may be expensive
     * and so should not called too frequently.
     */
    public String dumpStats() {
        int heapArenasLen = heapArenas == null ? 0 : heapArenas.length;
        StringBuilder buf = new StringBuilder(512)
                .append(heapArenasLen)
                .append(" heap arena(s):")
                .append(StringUtil.NEWLINE);
        if (heapArenasLen > 0) {
            for (PoolArena<byte[]> a: heapArenas) {
                buf.append(a);
            }
        }

        int directArenasLen = directArenas == null ? 0 : directArenas.length;

        buf.append(directArenasLen)
           .append(" direct arena(s):")
           .append(StringUtil.NEWLINE);
        if (directArenasLen > 0) {
            for (PoolArena<ByteBuffer> a: directArenas) {
                buf.append(a);
            }
        }

        return buf.toString();
    }
}
