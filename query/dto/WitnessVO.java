package com.mrtree.vann.query.dto;

import java.math.BigInteger;

/**
 * 🔥 WitnessVO (精简版)
 * 
 * 见证点：结果集边界外的"门神"，用于证明局部最优性
 * 
 * 只需要 Key 信息：
 *   - bigKey: 完整 30 位 Key（SIFT 128D）
 *   - isLeft: 是否是左边界见证
 * 
 * 验证器自己计算距离，不需要传输 distance
 */
public class WitnessVO {
    
    // ==================== 核心字段 ====================
    
    /** 完整 30 位 BigInteger Key（SIFT 128D 用） */
    public BigInteger bigKey;
    
    /** 是否是左边界见证 */
    public final boolean isLeft;

    // ==================== 构造器 ====================

    /**
     * 标准构造器
     */
    public WitnessVO(BigInteger bigKey, boolean isLeft) {
        this.bigKey = bigKey;
        this.isLeft = isLeft;
    }
    
    /**
     * 获取 BigKey
     */
    public BigInteger getBigKey() {
        return bigKey;
    }
}
