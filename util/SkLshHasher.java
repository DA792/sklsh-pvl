package com.mrtree.vann.util;

import java.math.BigInteger;
import java.util.Random;

/**
 * SK-LSH 哈希器：将高维向量映射为 16 位 long 整数
 * 
 * 算法：
 *   1. 对每个哈希函数 i：h_i = floor((A[i] · vec + B[i]) / W) + OFFSET
 *   2. 将 M 个哈希值拼接：key = h_0 * 10^(M-1) + h_1 * 10^(M-2) + ... + h_{M-1}
 * 
 * 参数：
 *   - D: 向量维度（如 32）
 *   - M: 哈希函数数量（16），生成 16 位整数
 *   - W: bucket 宽度（0.8）
 *   - OFFSET: 偏移量（4），确保 h ∈ [0, 9]
 */
public final class SkLshHasher {

    private final int D;           // 向量维度
    private final int M;           // 哈希函数数量
    private final double W;        // bucket 宽度
    private final int OFFSET;      // 偏移量
    private final double[][] A;    // 投影矩阵 [M x D]
    private final double[] B;      // 偏移项 [M]

    /**
     * 构造函数
     * 
     * @param D      向量维度（如 32）
     * @param M      哈希函数数量（如 16）
     * @param W      bucket 宽度（如 0.8）
     * @param offset 偏移量（如 4），确保哈希值非负
     * @param seed   随机种子，保证可重复
     */
    public SkLshHasher(int D, int M, double W, int offset, long seed) {
        this.D = D;
        this.M = M;
        this.W = W;
        this.OFFSET = offset;
        this.A = new double[M][D];
        this.B = new double[M];

        Random rng = new Random(seed);
        for (int i = 0; i < M; i++) {
            for (int j = 0; j < D; j++) {
                A[i][j] = rng.nextGaussian();  // 标准正态分布
            }
            B[i] = rng.nextDouble() * W;      // [0, W) 均匀分布
        }
    }

    /**
     * 默认构造函数：D=32, M=16, W=0.8, OFFSET=4, seed=1234
     */
    public SkLshHasher(int D) {
        this(D, 16, 0.8, 4, 1234L);
    }

    /**
     * 计算向量的 SK-LSH 哈希值（16 位 long 整数）
     * 
     * @param vec 输入向量（长度必须为 D）
     * @return 16 位 long 整数
     */
    public long hash(double[] vec) {
        if (vec == null || vec.length != D) {
            throw new IllegalArgumentException("Vector dimension mismatch: expected " + D + ", got " + (vec == null ? "null" : vec.length));
        }

        long key = 0;
        for (int i = 0; i < M; i++) {
            // 1. 计算投影：dot = A[i] · vec
            double dot = 0;
            for (int j = 0; j < D; j++) {
                dot += A[i][j] * vec[j];
            }

            // 2. LSH 哈希：h = floor((dot + b) / W) + offset
            int h = (int) Math.floor((dot + B[i]) / W) + OFFSET;

            // 3. 安全钳位：确保 h ∈ [0, 9]（单位数）
            if (h < 0) h = 0;
            if (h > 9) h = 9;

            // 4. 数学拼接：key = key * 10 + h
            key = key * 10 + h;
        }

        return key;
    }

    /**
     * 获取维度
     */
    public int getD() {
        return D;
    }

    /**
     * 获取哈希函数数量
     */
    public int getM() {
        return M;
    }

    /**
     * 获取 bucket 宽度
     */
    public double getW() {
        return W;
    }

    /**
     * 获取偏移量
     */
    public int getOffset() {
        return OFFSET;
    }

    /**
     * 计算向量的 SK-LSH 哈希值（BigInteger，用于 SIFT 128D M=30 场景）
     * 
     * 生成 M 位十进制整数（如 M=30 则生成 30 位 BigInteger）
     * 
     * @param vec 输入向量（长度必须为 D）
     * @return BigInteger（M 位十进制整数）
     */
    public BigInteger hashBig(double[] vec) {
        if (vec == null || vec.length != D) {
            throw new IllegalArgumentException("Vector dimension mismatch: expected " + D + ", got " + (vec == null ? "null" : vec.length));
        }

        BigInteger key = BigInteger.ZERO;
        BigInteger TEN = BigInteger.TEN;

        for (int i = 0; i < M; i++) {
            // 1. 计算投影：dot = A[i] · vec
            double dot = 0;
            for (int j = 0; j < D; j++) {
                dot += A[i][j] * vec[j];
            }

            // 2. LSH 哈希：h = floor((dot + b) / W) + offset
            int h = (int) Math.floor((dot + B[i]) / W) + OFFSET;

            // 3. 安全钳位：确保 h ∈ [0, 9]（单位数，保证 10 进制拼接安全）
            if (h < 0) h = 0;
            if (h > 9) h = 9;

            // 4. BigInteger 拼接：key = key * 10 + h
            key = key.multiply(TEN).add(BigInteger.valueOf(h));
        }

        return key;
    }

    /**
     * 从 BigInteger 提取高 16 位（用于 PVL 预测）
     * 
     * 健壮性处理：
     *   - null 返回 0
     *   - 长度 <= 16：直接转 long
     *   - 长度 > 16：截取前 16 位
     * 
     * @param bigKey 完整的 BigInteger Key（如 30 位）
     * @return 高 16 位的 long 值
     */
    public static long getHigh16(BigInteger bigKey) {
        if (bigKey == null) return 0L;
        
        String s = bigKey.toString();
        // 长度不足 16 位，直接解析整个数值
        if (s.length() <= 16) {
            return bigKey.longValue();
        }
        // 长度超过 16 位，截取前 16 位
        return Long.parseLong(s.substring(0, 16));
    }
}

