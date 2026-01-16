package com.mrtree.vann.model;

import java.math.BigInteger;

/**
 * 🔥 精简版 EKPoint
 * 
 * 只保留核心字段：
 *   - vec: 向量数据（本地持有，不传输）
 *   - ekKey: 高 16 位 Key（PVL 预测用）
 *   - bigKey: 完整 30 位 Key（排序、验证用）
 *   - globalRank: 全局排名（PXMRP 序列化用）
 *   - cachedKeyBytes: 缓存序列化（性能优化）
 */
public final class EKPoint {
    
    // ==================== 核心字段 ====================
    
    /** 高维向量（SIFT 128D 或 GloVe 32D） */
    public double[] vec;
    
    /** 高 16 位 Key（用于 PVL 预测） */
    public long ekKey;
    
    /** 完整 30 位 BigInteger Key（用于排序、验证） */
    public BigInteger bigKey;
    
    /** 全局排名（用于 PXMRP 序列化） */
    public int globalRank;
    
    /** 缓存序列化后的 BigKey 字节（16 bytes，性能优化） */
    public byte[] cachedKeyBytes;
    
    // ==================== 兼容字段（临时保留） ====================
    
    /** @deprecated 使用 ekKey 代替 */
    @Deprecated
    public long lshKey;
    
    /** 临时存储原始 ID（构建时用） */
    public int clusterId;
}
