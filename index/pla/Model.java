package com.mrtree.vann.index.pla;

public final class Model {
    /**
     * Linear model:
     *   y = slope * (x_scaled - intercept)
     * where:
     *   x_scaled = key * inputScale
     *
     * 默認情況下 inputScale = 1.0，相當於直接用原始 key。
     * 在 HP-SI（雙 Key）方案下，我們會將 inputScale 設為 family 級別的 scale，
     * 並同時重參數化 slope / intercept，使得在語義上仍保持
     *   y_old(key) == y_new(key)
     * 但數值條件更好（斜率 ~ 1）。
     */
    private final double slope;
    private final double intercept;
    private final double inputScale;

    public Model(double slope, double intercept) {
        this(slope, intercept, 1.0);
    }

    public Model(double slope, double intercept, double inputScale) {
        this.slope = slope;
        this.intercept = intercept;
        this.inputScale = inputScale;
    }

    public int find(long key) {
        double x = key * inputScale;
        return (int) (slope * (x - intercept));
    }

    public double slope() {
        return slope;
    }

    public double intercept() {
        return intercept;
    }

    public double inputScale() {
        return inputScale;
    }
}

