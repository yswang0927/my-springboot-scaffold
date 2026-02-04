package logfile;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 超大文本文件读取器。
 * 特性：
 * <pre>
 * 1. 分块缓存 (Block Cache)
 * 2. UTF-8 边界自动修复
 * 3. 线程安全的缓存加载
 * 4. 高性能换行符查找
 * </pre>
 */
public class HugeTextFileReader implements AutoCloseable {

    private static final int BLOCK_CURRENT = 1;
    // 缓存槽位定义：0=Prev, 1=Current, 2=Next1, 3=Next2
    private static final int CACHE_SIZE = 4;

    private final RandomAccessFile file;
    private final long fileLength;
    private final int blockSize;

    private final ReentrantReadWriteLock blockCacheLock = new ReentrantReadWriteLock();
    private final FileBlock[] blockCache = new FileBlock[CACHE_SIZE];
    private LongRange bytePositions = new LongRange(-1L, -2L);

    public HugeTextFileReader(String filePath) throws IOException {
        this(filePath, 1 * 1024 * 1024, -1);
    }

    public HugeTextFileReader(String filePath, int blockSize) throws IOException {
        this(filePath, blockSize, -1);
    }

    public HugeTextFileReader(String filePath, int blockSize, long initialFileLength) throws IOException {
        this.file = new RandomAccessFile(filePath, "r");
        this.blockSize = blockSize;
        this.fileLength = (initialFileLength > 0) ? initialFileLength : file.length();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    public long lengthInBytes() {
        return fileLength;
    }

    private Pair<byte[], LongRange> readBlock(FileBlockPosition block, long fileSize) throws IOException {
        if (block.position < 0 || block.position > fileSize / blockSize) {
            throw new IndexOutOfBoundsException("Attempt to read block " + block.position + " from " + block.anchor +
                    " but there are only " + (fileSize / blockSize) + " blocks. File size is " + fileSize + ".");
        }

        if (block.anchor == FileAnchor.Start) {
            long readStart = block.position * blockSize;
            int readLength = (int) Math.min((long) blockSize, fileSize - readStart);
            byte[] bytes = new byte[readLength];

            if (readLength > 0) {
                /*
                file.seek(readStart);
                file.read(bytes);
                */
                // 加锁 file 对象，防止多线程同时 seek/read 导致指针混乱
                synchronized (file) {
                    file.seek(readStart);
                    int totalRead = 0;
                    while (totalRead < readLength) {
                        int count = file.read(bytes, totalRead, readLength - totalRead);
                        if (count < 0) {
                            break; // EOF
                        }
                        totalRead += count;
                    }
                }
            }

            return new Pair<>(bytes, new LongRange(readStart, readStart + readLength - 1));
        } else {
            throw new UnsupportedOperationException("NotImplementedError");
        }
    }

    private FileBlock readBlockIfNotRead(FileBlockPosition block) throws IOException {
        long fileSize = fileLength;
        // 1. 尝试从缓存获取 (无锁快速检查，外部通常已加锁，这里为了逻辑独立再次遍历)
        for (FileBlock it : blockCache) {
            if (it != null
                    && it.pos.equals(block)
                    && (!isLastBlock(it.pos, fileSize) || it.bytes.length == (int)(fileSize % blockSize))) {
                return it;
            }
        }

        // 2. 缓存未命中，执行物理读取
        Pair<byte[], LongRange> result = readBlock(block, fileSize);
        return new FileBlock(block, result.first, result.second);
    }

    private boolean isLastBlock(FileBlockPosition pos, long fileSize) {
        if (pos.anchor == FileAnchor.Start) {
            return pos.position >= fileSize / blockSize;
        } else {
            return pos.position == 0L;
        }
    }

    /**
     * 加载指定的 Block 到 Current 槽位，并预加载前后 Block
     * 包含 Double-Check Locking 以解决并发问题
     */
    private void loadBlockPosition(FileBlockPosition targetBlockPos) throws IOException {
        long fileSize = fileLength;
        if (targetBlockPos.position < 0 || targetBlockPos.position > fileSize / blockSize) {
            throw new IndexOutOfBoundsException("Attempt to read block " + targetBlockPos.position + " from " + targetBlockPos.anchor +
                    " but there are only " + (fileSize / blockSize) + " blocks. File size is " + fileSize + ".");
        }

        // 预先读取数据 (在锁外进行 IO，减少锁持有时间)
        // 注意：这里可能会产生重复 IO，但为了减小锁粒度是值得的。
        // 如果想极致优化，可以在这里也加个读锁检查，但逻辑会变复杂。
        FileBlock currentBlock = readBlockIfNotRead(targetBlockPos);

        FileBlock prevBlock = null;
        if (targetBlockPos.position > 0) {
            FileBlockPosition pos = new FileBlockPosition(targetBlockPos.anchor, targetBlockPos.position - 1);
            prevBlock = readBlockIfNotRead(pos);
        }

        FileBlock nextBlock1 = null;
        if (targetBlockPos.position < fileSize / blockSize) {
            FileBlockPosition pos = new FileBlockPosition(targetBlockPos.anchor, targetBlockPos.position + 1);
            nextBlock1 = readBlockIfNotRead(pos);
        }

        FileBlock nextBlock2 = null;
        if (targetBlockPos.position + 1 < fileSize / blockSize) {
            FileBlockPosition pos = new FileBlockPosition(targetBlockPos.anchor, targetBlockPos.position + 2);
            nextBlock2 = readBlockIfNotRead(pos);
        }

        blockCacheLock.writeLock().lock();
        try {
            // [Fix] Double-Check: 获取写锁后，再次检查 Current 是否已经是我们想要的
            // 防止线程 A 和 B 同时 load 同一个位置，A 刚做完，B 又覆盖一遍
            FileBlock existingCurrent = blockCache[BLOCK_CURRENT];
            if (existingCurrent != null && existingCurrent.pos.equals(targetBlockPos)) {
                return;
            }

            blockCache[0] = prevBlock;
            blockCache[1] = currentBlock;
            blockCache[2] = nextBlock1;
            blockCache[3] = nextBlock2;

            long min = -1L;
            long max = -2L;

            // Calculate min start
            Long minVal = null;
            for (FileBlock fb : blockCache) {
                if (fb != null) {
                    if (minVal == null || fb.bytePositions.getStart() < minVal) {
                        minVal = fb.bytePositions.getStart();
                    }
                }
            }
            if (minVal != null) {
                min = minVal;
            }

            // Calculate max end
            Long maxVal = null;
            for (FileBlock fb : blockCache) {
                if (fb != null) {
                    if (maxVal == null || fb.bytePositions.getLast() > maxVal) {
                        maxVal = fb.bytePositions.getLast();
                    }
                }
            }
            if (maxVal != null) {
                max = maxVal;
            }

            bytePositions = new LongRange(min, max);
        } finally {
            blockCacheLock.writeLock().unlock();
        }
    }

    private void ensurePositionLoaded(long position) throws IOException {
        blockCacheLock.readLock().lock();
        try {
            FileBlock current = blockCache[BLOCK_CURRENT];
            if (current != null && current.bytePositions.contains(position)) {
                return;
            }
        } finally {
            blockCacheLock.readLock().unlock();
        }

        // 2. 缓存未命中，加载 (loadBlockPosition 内部有写锁)
        loadBlockPosition(new FileBlockPosition(FileAnchor.Start, position / blockSize));
    }

    /**
     * This should be called within a lock scope
     */
    private Byte read(PositionInBlock positionInBlock) {
        if (positionInBlock == null) {
            return null;
        }
        FileBlock block = blockCache[positionInBlock.blockIndex];
        if (block == null) {
            return null;
        }
        return block.bytes[positionInBlock.bytePosition];
    }

    Pair<ByteArrayOutputStream2, LongRange> readAsByteArrayOutputStream(long startBytePosition, int length) throws IOException {
        if (length > blockSize) {
            throw new IllegalArgumentException("Length " + length + " exceeds block size " + blockSize);
        }

        ByteArrayOutputStream2 bb = new ByteArrayOutputStream2(length + 16); // 预留一点空间给 UTF-8 修复
        long outputStart = startBytePosition;
        long outputEnd = -2L;

        // 确保数据在缓存中
        ensurePositionLoaded(startBytePosition);

        blockCacheLock.readLock().lock();
        try {
            FileBlock block = blockCache[BLOCK_CURRENT];
            if (block == null) {
                // 可能是文件为空或位置越界
                return new Pair<>(bb, new LongRange(startBytePosition, -2L));
            }

            if (startBytePosition > block.bytePositions.getEndInclusive()) {
                return new Pair<>(bb, new LongRange(startBytePosition, -2L));
            }

            int localStart = (int) (startBytePosition - block.bytePositions.getStart());

            // -------------------------------------------------------
            // 1. 前向修复 (Look Behind): 如果起始字节是 Continuation Byte
            // -------------------------------------------------------
            long currentReadPos = startBytePosition;

            if (Utf8Decoder.isContinuationByte(block.bytes[localStart])) {
                // 这是一个多字节序列的中间部分，我们需要回溯找到 Header
                byte[] lookBehindBuf = new byte[4];
                int foundCount = 0;
                boolean foundHeader = false;

                // 最多回溯 3 字节 (UTF-8 最大 4 字节)
                for (int i = 1; i <= 3; i++) {
                    long checkPos = startBytePosition - i;
                    if (checkPos < 0) {
                        break;
                    }

                    Byte b = readByteUnsafe(checkPos);
                    if (b == null) {
                        break; // 超出缓存范围，理论上 loadBlockPosition 会加载 prevBlock，除非在文件头
                    }

                    lookBehindBuf[foundCount++] = b;
                    outputStart--; // 修正返回范围的起始点

                    if (Utf8Decoder.isHeaderOfByteSequence(b)) {
                        foundHeader = true;
                        break;
                    }
                }

                if (foundHeader) {
                    // 找到了 Header，将回溯到的字节 (Header + 中间部分) 逆序写入
                    for (int i = foundCount - 1; i >= 0; i--) {
                        bb.write(lookBehindBuf[i]);
                    }
                } else {
                    // 没找到 Header (可能是文件损坏或切分错误)，回滚 outputStart
                    outputStart = startBytePosition;
                    // 并保留不做处理，从当前位置开始读，尽管可能会乱码
                }
            }

            // -------------------------------------------------------
            // 2. 读取主体部分
            // -------------------------------------------------------
            // 此时 bb 可能已经包含了一些前向修复的字节

            // 计算当前块能读多少
            int availableInCurrent = (int) (block.bytePositions.getEndExclusive() - startBytePosition);
            int toRead = Math.min(length, availableInCurrent);

            bb.write(block.bytes, localStart, toRead);
            currentReadPos = startBytePosition + toRead;
            int remainLength = length - toRead;

            // 如果跨块
            if (remainLength > 0) {
                FileBlock next = blockCache[BLOCK_CURRENT + 1];
                if (next != null) {
                    int nextRead = Math.min(remainLength, next.bytes.length);
                    bb.write(next.bytes, 0, nextRead);
                    currentReadPos += nextRead;
                }
            }

            outputEnd = currentReadPos - 1;

            // -------------------------------------------------------
            // 3. 后向修复 (Look Ahead): 检查结尾是否截断了 UTF-8 序列
            // -------------------------------------------------------
            int headerOffset = -1;
            // 在 buffer 末尾倒序找 Header
            while (headerOffset >= -3) {
                Byte b = bb.readFromLastOrNull(headerOffset);
                if (b == null) {
                    break;
                }

                if (Utf8Decoder.isContinuationByte(b)) {
                    headerOffset--;
                } else if (Utf8Decoder.isHeaderOfByteSequence(b)) {
                    // 找到了 Header，计算它应该有多长
                    int expectedLen = Utf8Decoder.sequenceLengthRepresentedByThisHeaderByte(b);
                    // 现有长度 = -headerOffset (例如 offset -1 代表有1个字节)
                    int presentLen = -headerOffset;
                    int missingLen = expectedLen - presentLen;

                    if (missingLen > 0) {
                        // 补齐缺失的字节
                        for (int k = 0; k < missingLen; k++) {
                            Byte missing = readByteUnsafe(currentReadPos);
                            if (missing != null) {
                                bb.write(missing);
                                currentReadPos++;
                                outputEnd++;
                            } else {
                                break; // 读不到（EOF 或 缓存缺失），放弃
                            }
                        }
                    }
                    break; // 只要处理完最后一个可能的序列就退出
                } else {
                    // 是 ASCII 字符，直接结束检查
                    break;
                }
            }

        } finally {
            blockCacheLock.readLock().unlock();
        }

        return new Pair<>(bb, new LongRange(outputStart, outputEnd));
    }

    /**
     * 读取指定范围的数据，并处理 UTF-8 边界
     * @deprecated
     */
    /*Pair<ByteArrayOutputStream2, LongRange> readAsByteArrayOutputStreamOld(long startBytePosition, int length) throws IOException {
        if (length > blockSize) {
            throw new IllegalArgumentException("`length` " + length + " should be less than or equal a block size");
        }

        ByteArrayOutputStream2 bb = new ByteArrayOutputStream2(length);
        long start = startBytePosition;
        long end = -2L;

        // 确保数据在缓存中
        ensurePositionLoaded(startBytePosition);

        blockCacheLock.readLock().lock();
        try {
            FileBlock block = blockCache[BLOCK_CURRENT];
            if (block == null) {
                throw new IllegalStateException("Current block shouldn't be null after load");
            }

            if (startBytePosition > block.bytePositions.getEndInclusive()) {
                return new Pair<>(bb, new LongRange(startBytePosition, -2L));
            }

            int currentBlockReadStart = (int) (startBytePosition - block.bytePositions.getStart());

            // -------------------------------------------------------
            // 前向修复 (Look Behind): 如果起始字节是 Continuation Byte
            // -------------------------------------------------------
            if (Utf8Decoder.isContinuationByte(block.bytes[currentBlockReadStart])) {
                LinkedList<Byte> lookBehindBytes = new LinkedList<>();
                PositionInBlock prevBytePos = new PositionInBlock(BLOCK_CURRENT, currentBlockReadStart).minus(1);

                while (prevBytePos != null) {
                    Byte bObj = read(prevBytePos);
                    if (bObj == null) {
                        break; // Should not happen if logic is correct
                    }
                    byte b = bObj;
                    lookBehindBytes.addFirst(b);
                    --start;

                    if (lookBehindBytes.size() < 3 && Utf8Decoder.isContinuationByte(b)) {
                        prevBytePos = prevBytePos.minus(1);
                    } else {
                        break;
                    }
                }

                byte[] lookBehindArr = new byte[lookBehindBytes.size()];
                int i = 0;
                for (Byte b : lookBehindBytes) {
                    lookBehindArr[i++] = b;
                }
                bb.write(lookBehindArr);
            }

            int currentBlockReadLength = Math.min(length, (int)(block.bytePositions.getEndExclusive() - startBytePosition));
            int remainLength = length;

            bb.write(block.bytes, currentBlockReadStart, currentBlockReadLength);
            end = block.bytePositions.getStart() + currentBlockReadStart + currentBlockReadLength;

            PositionInBlock lastBlockPos = new PositionInBlock(BLOCK_CURRENT, currentBlockReadStart + currentBlockReadLength - 1);
            remainLength -= currentBlockReadLength;

            if (remainLength > 0 && blockCache[BLOCK_CURRENT + 1] != null) {
                FileBlock nextBlock = blockCache[BLOCK_CURRENT + 1];
                int nextReadLength = Math.min(remainLength, nextBlock.bytes.length);
                bb.write(nextBlock.bytes, 0, nextReadLength);
                end += nextReadLength;

                if (lastBlockPos != null) {
                    lastBlockPos = lastBlockPos.plus(nextReadLength);
                }
            }

            // -------------------------------------------------------
            // 后向修复 (Look Ahead): 检查结尾是否截断了 UTF-8 序列
            // -------------------------------------------------------
            int headerByteOffset = -1;
            while (headerByteOffset > -3) {
                Byte b = bb.readFromLastOrNull(headerByteOffset);
                if (b != null && Utf8Decoder.isContinuationByte(b)) {
                    --headerByteOffset;
                } else {
                    break;
                }
            }

            Byte potentialHeader = bb.readFromLastOrNull(headerByteOffset);
            if (potentialHeader != null && Utf8Decoder.isHeaderOfByteSequence(potentialHeader)) {
                int byteSequenceLength = Utf8Decoder.sequenceLengthRepresentedByThisHeaderByte(potentialHeader);
                // logic: remainLength = byteSequenceLength - (- headerByteOffset)
                // headerByteOffset is negative, e.g., -1. -(-1) = 1.
                remainLength = byteSequenceLength - (-headerByteOffset);

                for (int k = 0; k < remainLength; k++) {
                    if (lastBlockPos != null) {
                        lastBlockPos = lastBlockPos.plus(1);
                        Byte b = read(lastBlockPos);
                        if (b != null) {
                            bb.write(b.intValue());
                            ++end;
                        }
                    }
                }
            }

        } finally {
            blockCacheLock.readLock().unlock();
        }

        return new Pair<>(bb, new LongRange(start, end - 1));
    }*/

    /**
     * [Fix] 不安全读取：假设调用者已经持有锁。
     * 直接遍历数组，避免创建对象和嵌套锁。
     */
    private Byte readByteUnsafe(long globalBytePos) {
        // 快速检查 Current
        FileBlock current = blockCache[BLOCK_CURRENT];
        if (current != null && current.bytePositions.contains(globalBytePos)) {
            return current.bytes[(int) (globalBytePos - current.bytePositions.getStart())];
        }

        // 检查其他
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (i == BLOCK_CURRENT) {
                continue;
            }
            FileBlock fb = blockCache[i];
            if (fb != null && fb.bytePositions.contains(globalBytePos)) {
                return fb.bytes[(int) (globalBytePos - fb.bytePositions.getStart())];
            }
        }

        return null;
    }

    public Pair<String, LongRange> readString(long startBytePosition, int length) throws IOException {
        Pair<ByteArrayOutputStream2, LongRange> result = readAsByteArrayOutputStream(startBytePosition, length);
        return new Pair<>(result.first.toString(StandardCharsets.UTF_8.name()), result.second);
    }

    public Pair<byte[], LongRange> readStringBytes(long startBytePosition, int length) throws IOException {
        Pair<ByteArrayOutputStream2, LongRange> result = readAsByteArrayOutputStream(startBytePosition, length);
        return new Pair<>(result.first.toByteArray(), result.second);
    }

    /**
     * 读取指定范围并自动扩展为整数行
     * @param rawStart 原始起始字节位置
     * @param rawLength 期望读取的长度
     * @return 包含完整行的结果对象
     */
    public Pair<String, LongRange> readFullLines(long rawStart, int rawLength) throws IOException {
        long fileLength = this.lengthInBytes();
        // 1. 修正起始位置 (向前找 \n)
        long newStart = findStartOfLine(rawStart);

        // 2. 修正结束位置 (向后找 \n)
        // 注意：搜索起点应该是 newStart + rawLength，我们要确保至少读这么多
        long searchEndFrom = Math.min(newStart + rawLength, fileLength);
        long newEnd = findEndOfLine(searchEndFrom);

        // 3. 计算实际需要读取的长度
        int newLength = (int) (newEnd - newStart);
        // 边界检查：如果算出来长度为0或负数（比如文件为空），直接返回空
        if (newLength <= 0) {
            return new Pair<>("", new LongRange(0L, -1L));
        }

        // 4. 执行实际读取
        return this.readString(newStart, newLength);
    }

    /**
     * 【新功能】从 endPosition 往前读 length 字节，并自动修正为整数行
     * 用于：向上滚动加载历史日志
     */
    public Pair<String, LongRange> readFullLinesBefore(long endPosition, int length) throws IOException {
        // 1. 粗略计算起始点
        long rawStart = Math.max(0, endPosition - length);
        // 2. 如果算出来是0，说明已经到头了，直接用普通读即可
        if (rawStart == 0) {
            return readFullLines(0, (int) endPosition);
        }

        // 3. 修正起始点：从 rawStart 往前找第一个换行符，确保不切断第一行
        long newStart = findStartOfLine(rawStart);
        // 4. 计算实际长度
        int realLength = (int) (endPosition - newStart);

        // 5. 读取 (readFullLines 会负责修正结尾，但因为我们的终点 endPosition
        //    本身通常就是上一次读取的起点（即行首），所以通常不需要修正结尾，
        //    但为了安全，我们还是调用 readFullLines)
        return readFullLines(newStart, realLength);
    }

    /**
     * [Optimization] 使用 Raw IO 查找行首，避开复杂的 readStringBytes 解码。
     * 寻找换行符时，其实不需要做 UTF-8 解码检查，
     * ASCII 的 \n (0x0A) 在 UTF-8 的多字节序列中是不可能出现的（UTF-8 多字节的字节范围是 0x80-0xFF）。
     */
    private long findStartOfLine(long startPos) throws IOException {
        if (startPos <= 0) {
            return 0;
        }

        // 如果当前位置的前一个字节就是 \n，那当前位置就是行首
        long currentPos = startPos;
        int bufSize = 256;
        byte[] buffer = new byte[bufSize];

        synchronized (file) {
            while (currentPos > 0) {
                long readStart = Math.max(0, currentPos - bufSize);
                int len = (int) (currentPos - readStart);

                file.seek(readStart);
                file.readFully(buffer, 0, len);

                // 倒序查找
                for (int i = len - 1; i >= 0; i--) {
                    if (buffer[i] == '\n') {
                        // 找到了换行符，行首是换行符的下一位
                        return readStart + i + 1;
                    }
                }
                currentPos = readStart;
            }
        }
        return 0;
    }

    /**
     * 向前寻找行首（即上一个 \n 的下一个位置，或者是文件开头）
     * @deprecated
     */
    /*private long findStartOfLineOld(long startPos) throws IOException {
        if (startPos <= 0) {
            return 0;
        }

        // 优化：先检查前一个字节是不是 \n，如果是，那当前就是行首
        // (注意：这里简化了逻辑，严谨的话要用 readStringBytes 防止切断 UTF-8，但找 \n (ASCII 10) 是安全的)
        // 不过为了复用缓存，我们直接读一小块
        long currentPos = startPos;
        int bufferSize = 256; // 每次回溯 256 字节查找

        while (currentPos > 0) {
            long readStart = Math.max(0, currentPos - bufferSize);
            int len = (int) (currentPos - readStart);

            Pair<byte[], LongRange> res = this.readStringBytes(readStart, len);
            byte[] bytes = res.first;
            // 倒序查找换行符
            for (int i = bytes.length - 1; i >= 0; i--) {
                if (bytes[i] == '\n') {
                    // 找到了！行首是换行符的下一个字节
                    // 这里的计算要小心：readStart + i 是 \n 的位置
                    return res.second.getStart() + i + 1;
                }
            }
            currentPos = readStart;
            // 如果已经到了文件头，退出循环
            if (currentPos == 0) {
                return 0;
            }
        }
        return 0;
    }*/

    /**
     * [Optimization] 使用 Raw IO 查找行尾
     */
    private long findEndOfLine(long startPos) throws IOException {
        long fileLength = this.lengthInBytes();
        if (startPos >= fileLength) {
            return fileLength;
        }

        long currentPos = startPos;
        int bufSize = 256;
        byte[] buffer = new byte[bufSize];

        synchronized (file) {
            while (currentPos < fileLength) {
                int len = (int) Math.min(bufSize, fileLength - currentPos);

                file.seek(currentPos);
                file.readFully(buffer, 0, len);

                for (int i = 0; i < len; i++) {
                    if (buffer[i] == '\n') {
                        // 包含换行符本身
                        return currentPos + i + 1;
                    }
                }
                currentPos += len;
            }
        }
        return fileLength;
    }

    /**
     * 向后寻找行尾（即下一个 \n 的位置 + 1，或者是 EOF）
     */
    /*private long findEndOfLineOld(long startPos) throws IOException {
        long fileLength = this.lengthInBytes();
        if (startPos >= fileLength) {
            return fileLength;
        }

        long currentPos = startPos;
        int bufferSize = 256; // 每次向后读 256 字节

        while (currentPos < fileLength) {
            Pair<byte[], LongRange> res = this.readStringBytes(currentPos, bufferSize);
            byte[] bytes = res.first;
            if (bytes.length == 0) {
                break;
            }

            // 正序查找换行符
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    // 找到了！行尾包含换行符，所以返回 \n 后面一个位置
                    // 这样 substring(start, end) 就会包含 \n
                    return res.second.getStart() + i + 1;
                }
            }

            currentPos = res.second.getEndExclusive();
        }
        return fileLength;
    }*/

    // ================= Inner Classes / Helpers =================

    public enum FileAnchor {
        Start, End
    }

    static class FileBlockPosition {
        final FileAnchor anchor;
        final long position;

        public FileBlockPosition(FileAnchor anchor, long position) {
            this.anchor = anchor;
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FileBlockPosition that)) {
                return false;
            }
            return position == that.position && anchor == that.anchor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(anchor, position);
        }
    }

    static class FileBlock {
        final FileBlockPosition pos;
        final byte[] bytes;
        final LongRange bytePositions;

        FileBlock(FileBlockPosition pos, byte[] bytes, LongRange bytePositions) {
            this.pos = pos;
            this.bytes = bytes;
            this.bytePositions = bytePositions;
        }
    }


    private class PositionInBlock {
        final int blockIndex;
        final int bytePosition;

        PositionInBlock(int blockIndex, int bytePosition) {
            this.blockIndex = blockIndex;
            this.bytePosition = bytePosition;
        }

        PositionInBlock plus(int byteOffset) {
            blockCacheLock.readLock().lock();
            try {
                if (blockIndex < 0 || blockIndex >= blockCache.length) {
                    throw new IndexOutOfBoundsException();
                }

                FileBlock block = blockCache[blockIndex];
                if (block == null) return null;

                long newBytePosition = block.bytePositions.getStart() + bytePosition + byteOffset;

                // Check neighbors: blockIndex - 1 to blockIndex + 1
                for (int i = blockIndex - 1; i <= blockIndex + 1; i++) {
                    if (i < 0 || i >= blockCache.length) continue;
                    FileBlock targetBlock = blockCache[i];
                    if (targetBlock == null) continue;

                    if (targetBlock.bytePositions.contains(newBytePosition)) {
                        return new PositionInBlock(i, (int)(newBytePosition - targetBlock.bytePositions.getStart()));
                    }
                }
                return null;
            } finally {
                blockCacheLock.readLock().unlock();
            }
        }

        PositionInBlock minus(int byteOffset) {
            return plus(-byteOffset);
        }
    }

    static class Utf8Decoder {
        // Kotlin UByte checks: 0x80u .. 0xBFu (128 to 191 in unsigned)
        static boolean isContinuationByte(byte b) {
            int ub = b & 0xFF; // convert to unsigned int
            return ub >= 0x80 && ub <= 0xBF;
        }

        // 0xC2u .. 0xF4u (194 to 244 in unsigned)
        static boolean isHeaderOfByteSequence(byte b) {
            int ub = b & 0xFF;
            return ub >= 0xC2 && ub <= 0xF4;
        }

        static int sequenceLengthRepresentedByThisHeaderByte(byte b) {
            int ub = b & 0xFF;
            if (ub >= 0xC2 && ub <= 0xDF) return 2;
            if (ub >= 0xE0 && ub <= 0xEF) return 3;
            if (ub >= 0xF0 && ub <= 0xF4) return 4;
            return 1;
        }
    }

}
