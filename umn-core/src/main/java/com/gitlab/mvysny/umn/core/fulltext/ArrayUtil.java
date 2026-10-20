package com.gitlab.mvysny.umn.core.fulltext;

/**
 * Array-growing helpers for {@link PorterStemmer}, borrowed from Lucene.
 */
final class ArrayUtil {
    private ArrayUtil() {}

    /**
     * Returns {@code array} itself if it holds at least {@code minSize} chars, otherwise a larger
     * copy, over-allocated per {@link #oversize}.
     */
    public static char[] grow(char[] array, int minSize) {
        assert minSize >= 0: "size must be positive (got " + minSize + "): likely integer overflow?";
        if (array.length < minSize) {
            char[] newArray = new char[oversize(minSize, Constants.NUM_BYTES_CHAR)];
            System.arraycopy(array, 0, newArray, 0, array.length);
            return newArray;
        } else
            return array;
    }

    /** Returns an array size >= minTargetSize, generally
     *  over-allocating exponentially to achieve amortized
     *  linear-time cost as the array grows.
     *
     *  NOTE: this was originally borrowed from Python 2.4.2
     *  listobject.c sources (attribution in LICENSE.txt), but
     *  has now been substantially changed based on
     *  discussions from java-dev thread with subject "Dynamic
     *  array reallocation algorithms", started on Jan 12
     *  2010.
     *
     * @param minTargetSize Minimum required value to be returned.
     * @param bytesPerElement Bytes used by each element of
     * the array, e.g. {@link Constants#NUM_BYTES_CHAR}.
     * @return the new array size, at least {@code minTargetSize}
     * @throws IllegalArgumentException if {@code minTargetSize} is negative
     */
    public static int oversize(int minTargetSize, int bytesPerElement) {

        if (minTargetSize < 0) {
            // catch usage that accidentally overflows int
            throw new IllegalArgumentException("invalid array size " + minTargetSize);
        }

        if (minTargetSize == 0) {
            // wait until at least one element is requested
            return 0;
        }

        // asymptotic exponential growth by 1/8th, favors
        // spending a bit more CPU to not tie up too much wasted
        // RAM:
        int extra = minTargetSize >> 3;

        if (extra < 3) {
            // for very small arrays, where constant overhead of
            // realloc is presumably relatively high, we grow
            // faster
            extra = 3;
        }

        int newSize = minTargetSize + extra;

        // add 7 to allow for worst case byte alignment addition below:
        if (newSize+7 < 0) {
            // int overflowed -- return max allowed array size
            return Integer.MAX_VALUE;
        }

        if (Constants.JRE_IS_64BIT) {
            // round up to 8 byte alignment in 64bit env
            switch(bytesPerElement) {
                case 4:
                    // round up to multiple of 2
                    return (newSize + 1) & 0x7ffffffe;
                case 2:
                    // round up to multiple of 4
                    return (newSize + 3) & 0x7ffffffc;
                case 1:
                    // round up to multiple of 8
                    return (newSize + 7) & 0x7ffffff8;
                case 8:
                    // no rounding
                default:
                    // odd (invalid?) size
                    return newSize;
            }
        } else {
            // round up to 4 byte alignment in 64bit env
            switch(bytesPerElement) {
                case 2:
                    // round up to multiple of 2
                    return (newSize + 1) & 0x7ffffffe;
                case 1:
                    // round up to multiple of 4
                    return (newSize + 3) & 0x7ffffffc;
                case 4:
                case 8:
                    // no rounding
                default:
                    // odd (invalid?) size
                    return newSize;
            }
        }
    }

}
