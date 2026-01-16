package com.mrtree.vann.index.pla;

import java.util.ArrayList;
import java.util.List;

public final class OptPLA {
    private final List<Segment> segmentList = new ArrayList<>();
    private final List<long[]> lower = new ArrayList<>();
    private final List<long[]> upper = new ArrayList<>();
    private final List<Long> segDataList = new ArrayList<>();
    private final long[][] rectangle = new long[4][2];
    private int upperStart = 0;
    private int lowerStart = 0;
    private final int err;

    public OptPLA(int err) {
        this.err = err;
    }

    public OptPLA(long[] dataset, int err) {
        this(err);
        for (long data : dataset) {
            addKey(data);
        }
        stop();
    }

    public void addKey(long key) {
        long x = key;
        long y = segDataList.size();
        long[] p1 = new long[]{x, y + err};
        long[] p2 = new long[]{x, y - err};

        if (segDataList.isEmpty()) {
            rectangle[0] = p1;
            rectangle[1] = p2;
            upper.clear();
            upper.add(p1);
            lower.clear();
            lower.add(p2);
            segDataList.add(key);
            return;
        }
        if (segDataList.size() == 1) {
            rectangle[2] = p2;
            rectangle[3] = p1;
            upper.add(p1);
            lower.add(p2);
            segDataList.add(key);
            return;
        }

        double slope1 = getSlope(rectangle[2], rectangle[0]);
        double slope2 = getSlope(rectangle[3], rectangle[1]);
        if (getSlope(p1, rectangle[2]) < slope1 || getSlope(p2, rectangle[3]) > slope2) {
            addRegionToSegments();
            addKey(key);
            return;
        }

        if (getSlope(p1, rectangle[1]) < slope2) {
            double min = getSlope(lower.get(lowerStart), p1);
            int minIdx = lowerStart;
            for (int i = lowerStart + 1; i < lower.size(); ++i) {
                double val = getSlope(lower.get(i), p1);
                if (val > min) break;
                min = val;
                minIdx = i;
            }
            rectangle[1] = lower.get(minIdx);
            rectangle[3] = p1;
            lowerStart = minIdx;

            int end = upper.size();
            while (end >= upperStart + 2 && cross(upper.get(end - 2), upper.get(end - 1), p1) <= 0) {
                upper.remove(--end);
            }
            upper.add(p1);
        }

        if (getSlope(p2, rectangle[0]) > slope1) {
            double max = getSlope(upper.get(upperStart), p2);
            int maxIdx = upperStart;
            for (int i = upperStart + 1; i < upper.size(); ++i) {
                double val = getSlope(upper.get(i), p2);
                if (val < max) break;
                max = val;
                maxIdx = i;
            }
            rectangle[0] = upper.get(maxIdx);
            rectangle[2] = p2;
            upperStart = maxIdx;

            int end = lower.size();
            while (end >= lowerStart + 2 && cross(lower.get(end - 2), lower.get(end - 1), p2) >= 0) {
                lower.remove(--end);
            }
            lower.add(p2);
        }

        segDataList.add(key);
    }

    public void stop() {
        if (!segDataList.isEmpty()) {
            addRegionToSegments();
        }
    }

    public Segment[] getSegments() {
        return segmentList.toArray(new Segment[0]);
    }

    private void addRegionToSegments() {
        double[] slopeIntercept = getSlopeAndIntercept();
        Model model = new Model(slopeIntercept[0], slopeIntercept[1]);
        long[] data = segDataList.stream().mapToLong(Long::longValue).toArray();
        segmentList.add(new Segment(model, data));
        clear();
    }

    private void clear() {
        lower.clear();
        upper.clear();
        lowerStart = 0;
        upperStart = 0;
        for (int i = 0; i < 4; i++) {
            rectangle[i] = new long[2];
        }
        segDataList.clear();
    }

    private double[] getSlopeAndIntercept() {
        if (segDataList.size() <= 1) {
            return new double[]{0.0, (rectangle[0][1] + rectangle[1][1]) / 2.0};
        }
        double slope1 = getSlope(rectangle[2], rectangle[0]);
        double slope2 = getSlope(rectangle[3], rectangle[1]);
        double slope = (slope1 + slope2) / 2.0;
        double intercept;
        if (slope1 == slope2) {
            intercept = rectangle[0][0] - rectangle[0][1] / slope;
        } else {
            double tmp = slope2 - slope1;
            double x0 = (rectangle[0][1] - slope1 * rectangle[0][0] + slope2 * rectangle[1][0] - rectangle[1][1]) / tmp;
            double y0 = (slope1 * slope2 * (rectangle[1][0] - rectangle[0][0]) + rectangle[0][1] * slope2 - rectangle[1][1] * slope1) / tmp;
            intercept = x0 - y0 / slope;
        }
        return new double[]{slope, intercept};
    }

    private double getSlope(long[] p1, long[] p2) {
        long dx = p2[0] - p1[0];
        // 防止重复 x 导致除零（出现 Inf/NaN 影响后续截距计算）
        if (dx == 0) {
            return (p2[1] >= p1[1]) ? Double.MAX_VALUE : -Double.MAX_VALUE;
        }
        return ((double) (p2[1] - p1[1])) / dx;
    }

    private double cross(long[] o, long[] a, long[] b) {
        return getSlope(b, o) - getSlope(a, o);
    }
}

