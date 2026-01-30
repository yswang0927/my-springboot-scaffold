package logfile;

public class LongRange {
    private final long start;
    private final long endInclusive;

    public LongRange(long start, long endInclusive) {
        this.start = start;
        this.endInclusive = endInclusive;
    }

    public long getStart() {
        return start;
    }

    public long getLast() {
        return endInclusive;
    }

    public long getEndInclusive() {
        return endInclusive;
    }

    public long getEndExclusive() {
        return endInclusive + 1;
    }

    public boolean contains(long value) {
        return value >= start && value <= endInclusive;
    }

    @Override
    public String toString() {
        return start + ".." + endInclusive;
    }
}
