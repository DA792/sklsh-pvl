package com.mrtree.vann.query.dto;

import java.math.BigInteger;

/**
 * 🔥 VO 专用精简版点结构
 * 
 * 体积对比：
 *   - EKPoint: ≈1200 bytes（包含 128维向量 1024 bytes）
 *   - VOPoint: ≈36 bytes
 *   - 节省：97%
 * 
 * 只包含验证必需的字段：
 *   - bigKey: 30 位 BigInteger（精确验证用）
 *   - globalRank: 全局排名（序列化用）
 *   - cachedKeyBytes: 缓存的 BigKey 字节（序列化优化）
 * 
 * 不传输的字段（由客户端本地持有）：
 *   - vec[128]: 向量数据 ❌
 *   - x, y, lon, lat: 遗留字段 ❌
 *   - hash, rawKey, localRank, clusterId, familyId, skScore ❌
 */
public final class VOPoint {
    
    /** 30 位 BigInteger Key（用于精确验证） */
    public BigInteger bigKey;
    
    /** 全局排名（用于 PXMRP 序列化） */
    public int globalRank;
    
    /** 缓存的 BigKey 字节（16 bytes，序列化优化） */
    public byte[] cachedKeyBytes;
    
    // ==================== 构造器 ====================
    
    public VOPoint() {}
    
    public VOPoint(BigInteger bigKey, int globalRank, byte[] cachedKeyBytes) {
        this.bigKey = bigKey;
        this.globalRank = globalRank;
        this.cachedKeyBytes = cachedKeyBytes;
    }
    
    /**
     * 🔥 从 EKPoint 转换（零拷贝缓存复用）
     */
    public static VOPoint fromEKPoint(com.mrtree.vann.model.EKPoint p) {
        return new VOPoint(
            p.bigKey,
            p.globalRank,
            p.cachedKeyBytes  // 直接复用引用，不拷贝
        );
    }
    
    // ==================== 序列化 ====================
    
    private static final int BIGKEY_BYTES = 16;
    
    /**
     * 序列化为字节数组（用于 PXMRP）
     * 
     * 格式：[4 bytes globalRank] + [16 bytes bigKey]
     * 总计：20 bytes
     */
    public byte[] serialize() {
        byte[] result = new byte[4 + BIGKEY_BYTES];
        
        // 写入 globalRank (4 bytes, big-endian)
        result[0] = (byte)(globalRank >>> 24);
        result[1] = (byte)(globalRank >>> 16);
        result[2] = (byte)(globalRank >>> 8);
        result[3] = (byte)(globalRank);
        
        // 写入 bigKey (16 bytes)
        if (cachedKeyBytes != null) {
            System.arraycopy(cachedKeyBytes, 0, result, 4, BIGKEY_BYTES);
        } else {
            byte[] padded = new byte[BIGKEY_BYTES];
            if (bigKey != null) {
                byte[] keyBytes = bigKey.toByteArray();
                int offset = BIGKEY_BYTES - keyBytes.length;
                if (offset >= 0) {
                    System.arraycopy(keyBytes, 0, padded, offset, keyBytes.length);
                } else {
                    System.arraycopy(keyBytes, keyBytes.length - BIGKEY_BYTES, padded, 0, BIGKEY_BYTES);
                }
            }
            System.arraycopy(padded, 0, result, 4, BIGKEY_BYTES);
        }
        
        return result;
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取 BigKey（兼容 null）
     */
    public BigInteger getBigKey() {
        return bigKey != null ? bigKey : BigInteger.ZERO;
    }
    
    @Override
    public String toString() {
        return "VOPoint{rank=" + globalRank + 
               ", bigKey=" + (bigKey != null ? bigKey.toString().substring(0, Math.min(10, bigKey.toString().length())) + "..." : "null") + "}";
    }
}

