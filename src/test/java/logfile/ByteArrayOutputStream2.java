package logfile;

import java.io.ByteArrayOutputStream;

/**
 * 继承自 ByteArrayOutputStream 以支持从末尾读取字节。
 * Java 中的 buf 和 count 字段是 protected，可以直接在子类中使用。
 */
public class ByteArrayOutputStream2 extends ByteArrayOutputStream {

    public ByteArrayOutputStream2(int size) {
        super(size);
    }

    /**
     * 读取指定索引处的字节
     */
    public synchronized byte readAt(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index should be within the range [0, " + count + ")");
        }
        return buf[index];
    }

    /**
     * 从末尾开始读取（使用负索引，例如 -1 表示最后一个字节）
     */
    public synchronized byte readFromLast(int negativeIndex) {
        if (negativeIndex >= 0 || negativeIndex < -count) {
            throw new IndexOutOfBoundsException("NegativeIndex should be negative and within the range [-" + count + ", 0)");
        }
        return buf[count + negativeIndex];
    }

    /**
     * 从末尾读取，如果索引越界则返回 null
     * 注意：Java 的 byte 是基本类型，不能直接返回 null，因此返回 Byte 对象。
     */
    public synchronized Byte readFromLastOrNull(int negativeIndex) {
        if (negativeIndex >= 0 || negativeIndex < -count) {
            return null;
        }
        return buf[count + negativeIndex];
    }
}
