package com.mrtree.vann.query.dto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 🔥 EklshPVLVO (精简版)
 * 
 * 核心设计：
 *   - 纯粹的验证凭据 (Proof only)
 *   - 骨肉分离：不含 Vector，不含业务参数
 *   - 体积：~4.5 KB (当 K=100 时)
 * 
 * VO 结构：
 *   - root: Merkle 证明树（叶子包含 VOPoint 列表）
 *   - leftWitness, rightWitness: ANN 特有的局部最优性证明
 */
public class EklshPVLVO {

    // ==================== 核心字段 ====================
    
    /** Merkle 证明树 */
    public Node root;
    
    /** 左边界见证 */
    public WitnessVO leftWitness;
    
    /** 右边界见证 */
    public WitnessVO rightWitness;
    
    // ==================== 兼容字段（仅 Demo 用，不传输） ====================
    
    /** 根摘要（客户端本地存储） */
    public transient byte[] rootDigest;
    
    /** 高 16 位查询 Key（客户端自己算） */
    public transient long hq;
    
    /** 返回数量（查询参数） */
    public transient int k;

    /**
     * VO 树节点
     */
    public static final class Node {
        // --- PXMRP 核心 ---
        public final boolean leaf;
        public final int startPos;
        public final int endPos;
        
        // --- 密码学证明 ---
        public final byte[] piStart;    // π_{p_l-1}
        public final byte[] piEnd;      // π_{p_r}
        public final long r;            // Random Nonce
        public final int len;           // Range Length
        public final byte[] phLast;     // Aggregated Hash
        
        // 🔥 优化：完全覆盖标志（不需要传 proofs）
        public final boolean fullyCovered;
        
        // --- 树结构 ---
        public final List<VOPoint> leafPoints;  // 精简版结果点
        public final List<Node> children;       // 子节点

        private Node(Builder builder) {
            this.leaf = builder.leaf;
            this.startPos = builder.startPos;
            this.endPos = builder.endPos;
            this.piStart = builder.piStart;
            this.piEnd = builder.piEnd;
            this.r = builder.r;
            this.len = builder.len;
            this.phLast = builder.phLast;
            this.fullyCovered = builder.fullyCovered;
            this.leafPoints = builder.leafPoints == null ? List.of() : List.copyOf(builder.leafPoints);
            this.children = builder.children == null ? List.of() : List.copyOf(builder.children);
        }

        public static Builder builder(boolean leaf) {
            return new Builder(leaf);
        }

        public static final class Builder {
            private final boolean leaf;
            private int startPos;
            private int endPos;
            private byte[] piStart;
            private byte[] piEnd;
            private long r;
            private int len;
            private byte[] phLast;
            private boolean fullyCovered;
            private List<VOPoint> leafPoints;
            private List<Node> children;

            private Builder(boolean leaf) {
                this.leaf = leaf;
            }
            
            /**
             * 🔥 标记为完全覆盖（不需要传 proofs）
             */
            public Builder fullyCovered(boolean fullyCovered) {
                this.fullyCovered = fullyCovered;
                return this;
            }

            public Builder range(int start, int end) {
                this.startPos = start;
                this.endPos = end;
                return this;
            }

            public Builder proofs(byte[] piStart, byte[] piEnd) {
                this.piStart = piStart;
                this.piEnd = piEnd;
                return this;
            }

            public Builder ads(long r, int len, byte[] phLast) {
                this.r = r;
                this.len = len;
                this.phLast = phLast;
                return this;
            }

            public Builder leafPoints(List<VOPoint> pts) {
                this.leafPoints = pts;
                return this;
            }
            
            /**
             * 从 EKPoint 列表转换为 VOPoint
             */
            public Builder leafPointsFromEK(List<com.mrtree.vann.model.EKPoint> pts) {
                if (pts == null) {
                    this.leafPoints = null;
                } else {
                    this.leafPoints = new ArrayList<>(pts.size());
                    for (com.mrtree.vann.model.EKPoint p : pts) {
                        this.leafPoints.add(VOPoint.fromEKPoint(p));
                    }
                }
                return this;
            }

            public Builder children(List<Node> children) {
                this.children = children;
                return this;
            }

            public Node build() {
                return new Node(this);
            }
        }
    }
    
    /**
     * 提取所有叶子节点数据
     */
    public List<VOPoint> extractLeafPoints() {
        List<VOPoint> list = new ArrayList<>();
        collect(this.root, list);
        return list;
    }

    private void collect(Node node, List<VOPoint> list) {
        if (node == null) return;
        if (node.leaf) {
            list.addAll(node.leafPoints);
        } else {
            for (Node child : node.children) {
                collect(child, list);
            }
        }
    }
    
    // ==================== 🔥 紧凑序列化 ====================
    
    /**
     * 🔥 紧凑序列化为字节数组
     * 
     * 优化：
     *   - VarInt 编码整数（节省 50%+）
     *   - Delta 编码 leafPoints（节省 60%+）
     *   - 无 Java 对象头开销
     * 
     * @return 紧凑字节数组
     */
    public byte[] toCompactBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            
            // 1. 写入 flags (1 byte)
            byte flags = 0;
            if (root != null) flags |= 0x01;
            if (leftWitness != null) flags |= 0x02;
            if (rightWitness != null) flags |= 0x04;
            out.writeByte(flags);
            
            // 2. 写入 root 树
            if (root != null) {
                writeNodeCompact(out, root);
            }
            
            // 3. 写入 witnesses
            if (leftWitness != null) {
                writeWitnessCompact(out, leftWitness);
            }
            if (rightWitness != null) {
                writeWitnessCompact(out, rightWitness);
            }
            
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Compact serialization failed", e);
        }
    }
    
    /**
     * 🔥 计算紧凑字节大小（不实际序列化）
     */
    public int compactSize() {
        int size = 1; // flags
        if (root != null) size += sizeNodeCompact(root);
        if (leftWitness != null) size += sizeWitnessCompact(leftWitness);
        if (rightWitness != null) size += sizeWitnessCompact(rightWitness);
        return size;
    }
    
    // ==================== 私有序列化方法 ====================
    
    private void writeNodeCompact(DataOutputStream out, Node node) throws IOException {
        // flags: leaf(1) + hasProofs(1) + hasChildren(1) + fullyCovered(1)
        byte nodeFlags = 0;
        if (node.leaf) nodeFlags |= 0x01;
        // 🔥 完全覆盖的节点不传 proofs
        boolean needProofs = node.piStart != null && !node.fullyCovered;
        if (needProofs) nodeFlags |= 0x02;
        if (!node.children.isEmpty()) nodeFlags |= 0x04;
        if (node.fullyCovered) nodeFlags |= 0x08;
        out.writeByte(nodeFlags);
        
        // 🔥 完全覆盖节点只需要 r 和 len（客户端自己重算）
        out.writeLong(node.r);
        writeVarInt(out, node.len);
        
        // 🔥 只有非完全覆盖才传 range 和 proofs
        if (!node.fullyCovered) {
            writeVarInt(out, node.startPos);
            writeVarInt(out, node.endPos);
            
            if (needProofs) {
                out.write(node.piStart);
                out.write(node.piEnd);
                out.write(node.phLast);
            }
        }
        
        // leafPoints (Delta 编码)
        if (node.leaf && node.leafPoints != null) {
            writeLeafPointsDelta(out, node.leafPoints);
        }
        
        // children (递归)
        if (!node.children.isEmpty()) {
            writeVarInt(out, node.children.size());
            for (Node child : node.children) {
                writeNodeCompact(out, child);
            }
        }
    }
    
    private int sizeNodeCompact(Node node) {
        int size = 1; // nodeFlags
        size += 8; // r (long)
        size += sizeVarInt(node.len);
        
        // 🔥 完全覆盖节点省掉 range 和 proofs
        if (!node.fullyCovered) {
            size += sizeVarInt(node.startPos);
            size += sizeVarInt(node.endPos);
            
            if (node.piStart != null) {
                size += 32 * 3; // piStart + piEnd + phLast
            }
        }
        
        if (node.leaf && node.leafPoints != null) {
            size += sizeLeafPointsDelta(node.leafPoints);
        }
        
        if (!node.children.isEmpty()) {
            size += sizeVarInt(node.children.size());
            for (Node child : node.children) {
                size += sizeNodeCompact(child);
            }
        }
        
        return size;
    }
    
    private void writeWitnessCompact(DataOutputStream out, WitnessVO w) throws IOException {
        out.writeBoolean(w.isLeft);
        // bigKey (16 bytes 固定)
        byte[] keyBytes = bigIntegerTo16Bytes(w.getBigKey());
        out.write(keyBytes);
    }
    
    private int sizeWitnessCompact(WitnessVO w) {
        return 1 + 16; // isLeft + bigKey
    }
    
    // ==================== Delta 编码 leafPoints ====================
    
    /**
     * 🔥 Delta 编码写入 leafPoints
     * 
     * 格式：
     *   - count (VarInt)
     *   - 第一个点: rank(4) + bigKey(16)
     *   - 后续点: rankDelta(VarInt) + keyDeltaLen(VarInt) + keyDelta(bytes)
     */
    private void writeLeafPointsDelta(DataOutputStream out, List<VOPoint> points) throws IOException {
        writeVarInt(out, points.size());
        if (points.isEmpty()) return;
        
        // 第一个点：完整写入
        VOPoint first = points.get(0);
        out.writeInt(first.globalRank);
        byte[] firstKey = bigIntegerTo16Bytes(first.getBigKey());
        out.write(firstKey);
        
        // 后续点：Delta 编码
        int prevRank = first.globalRank;
        BigInteger prevKey = first.getBigKey();
        
        for (int i = 1; i < points.size(); i++) {
            VOPoint p = points.get(i);
            
            // Rank Delta (通常是 1，VarInt 只需 1 byte)
            int rankDelta = p.globalRank - prevRank;
            writeVarInt(out, rankDelta);
            prevRank = p.globalRank;
            
            // Key Delta
            BigInteger keyDelta = p.getBigKey().subtract(prevKey);
            byte[] deltaBytes = keyDelta.toByteArray();
            writeVarInt(out, deltaBytes.length);
            out.write(deltaBytes);
            prevKey = p.getBigKey();
        }
    }
    
    private int sizeLeafPointsDelta(List<VOPoint> points) {
        int size = sizeVarInt(points.size());
        if (points.isEmpty()) return size;
        
        // 第一个点
        size += 4 + 16; // rank + bigKey
        
        // 后续点 Delta
        int prevRank = points.get(0).globalRank;
        BigInteger prevKey = points.get(0).getBigKey();
        
        for (int i = 1; i < points.size(); i++) {
            VOPoint p = points.get(i);
            int rankDelta = p.globalRank - prevRank;
            size += sizeVarInt(rankDelta);
            prevRank = p.globalRank;
            
            BigInteger keyDelta = p.getBigKey().subtract(prevKey);
            byte[] deltaBytes = keyDelta.toByteArray();
            size += sizeVarInt(deltaBytes.length);
            size += deltaBytes.length;
            prevKey = p.getBigKey();
        }
        
        return size;
    }
    
    // ==================== VarInt 工具方法 ====================
    
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }
    
    private static int sizeVarInt(int value) {
        int size = 1;
        while ((value & 0xFFFFFF80) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }
    
    private static byte[] bigIntegerTo16Bytes(BigInteger val) {
        if (val == null) return new byte[16];
        byte[] raw = val.toByteArray();
        if (raw.length == 16) return raw;
        
        byte[] padded = new byte[16];
        int offset = 16 - raw.length;
        if (offset >= 0) {
            System.arraycopy(raw, 0, padded, offset, raw.length);
        } else {
            System.arraycopy(raw, raw.length - 16, padded, 0, 16);
        }
        return padded;
    }
}
