package com.mrtree.vann.index.pla;

final class PlaUtils {
    private PlaUtils() {}

    static int findLeftBound(long[] arr, long target, int l, int r) {
        int lo = Math.max(0, l);
        int hi = Math.min(arr.length - 1, r);
        int pos = hi + 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] >= target) {
                pos = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return pos;
    }
}

