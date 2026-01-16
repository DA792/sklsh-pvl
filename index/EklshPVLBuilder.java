package com.mrtree.vann.index;

import com.mrtree.vann.index.pla.OptPLA;
import com.mrtree.vann.index.pla.Segment;
import com.mrtree.vann.model.EKPoint;
import com.mrtree.vann.pvl.PXMRP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SK-LSH PVL Builder
 * 
 * 构建 PVL 树索引：
 *   - 输入：按 lshKey 排序的点列表
 *   - 输出：PVL 树（带 PXMRP 认证）
 * 
 * 简化架构：无归一化，直接使用 lshKey（16 位 long）
 */
public final class EklshPVLBuilder {
    private static final int DEFAULT_ERR = 64;

    private EklshPVLBuilder() {}

    public static EklshPVLTree build(List<EKPoint> points, byte[] sk0, byte[] sk1) {
        return build(points, sk0, sk1, DEFAULT_ERR);
    }

    /**
     * 构建 PVL 树
     * 
     * @param points 按 ekKey (lshKey) 升序排序的点列表
     * @param sk0 PXMRP 密钥 0
     * @param sk1 PXMRP 密钥 1
     * @param err PLA 误差容限
     * @return PVL 树
     */
    public static EklshPVLTree build(List<EKPoint> points,
                                     byte[] sk0,
                                     byte[] sk1,
                                     int err) {
        if (points == null || points.isEmpty()) {
            return new EklshPVLTree(null, null, err, List.of(), 1.0, 0);
        }
        // ⭐ 重要：不要重新排序！
        // 调用者（DemoMainSift）已经按 bigKey 排序并分配了 globalRank
        // 按 bigKey 排序后，ekKey（高 16 位）也是近似单调的
        List<EKPoint> sorted = new ArrayList<>(points);
        // sorted.sort(java.util.Comparator.comparingLong(p -> p.ekKey)); // ❌ 删除！
        EKPoint[] arr = sorted.toArray(new EKPoint[0]);
        long[] keys = Arrays.stream(arr).mapToLong(p -> p.ekKey).toArray();

        Random rng = new Random(2025L);
        AtomicInteger idGen = new AtomicInteger();
        List<EklshPVLTree.Node> current = buildLeaves(arr, keys, sk0, sk1, err, rng, idGen);
        while (current.size() > 1) {
            current = buildInternalLevel(current, sk0, sk1, err, rng, idGen);
        }
        EklshPVLTree.Node root = current.get(0);

        // ⭐ 不再需要 applyScaleToModels，因為 normKey 已經歸一化到 [0, C*N] 範圍
        // Model 直接在 normKey 上訓練，斜率自然穩定

        byte[] rootDigest = PXMRP.computeDigest(root.ads);
        return new EklshPVLTree(root, rootDigest, err, List.copyOf(sorted), 1.0, 0);
    }

    private static List<EklshPVLTree.Node> buildLeaves(EKPoint[] points,
                                                       long[] keys,
                                                       byte[] sk0,
                                                       byte[] sk1,
                                                       int err,
                                                       Random rng,
                                                       AtomicInteger idGen) {
        OptPLA pla = new OptPLA(keys, err);
        Segment[] segments = pla.getSegments();
        List<EklshPVLTree.Node> leaves = new ArrayList<>(segments.length);
        int pos = 0;
        for (Segment segment : segments) {
            int len = segment.segData.length;
            EKPoint[] slice = Arrays.copyOfRange(points, pos, pos + len);
            long[] segKeys = Arrays.copyOfRange(keys, pos, pos + len);
            EklshPVLTree.LeafNode leaf = new EklshPVLTree.LeafNode(
                    idGen.getAndIncrement(),
                    new Segment(segment),
                    slice,
                    segKeys,
                    pos
            );
            leaf.ads = PXMRP.embedADS(serializeLeaf(slice), sk0, sk1, rng);
            leaves.add(leaf);
            pos += len;
        }
        return leaves;
    }

    private static List<EklshPVLTree.Node> buildInternalLevel(List<EklshPVLTree.Node> childrenLevel,
                                                              byte[] sk0,
                                                              byte[] sk1,
                                                              int err,
                                                              Random rng,
                                                              AtomicInteger idGen) {
        int n = childrenLevel.size();
        long[] childKeys = new long[n];
        for (int i = 0; i < n; i++) {
            childKeys[i] = childrenLevel.get(i).minKey;
        }
        OptPLA pla = new OptPLA(childKeys, err);
        Segment[] segments = pla.getSegments();
        List<EklshPVLTree.Node> parents = new ArrayList<>(segments.length);
        int pos = 0;
        for (Segment segment : segments) {
            int len = segment.segData.length;
            EklshPVLTree.Node[] slice = new EklshPVLTree.Node[len];
            long[] minKeys = new long[len];
            for (int i = 0; i < len; i++) {
                slice[i] = childrenLevel.get(pos + i);
                minKeys[i] = slice[i].minKey;
            }
            EklshPVLTree.InternalNode parent = new EklshPVLTree.InternalNode(
                    idGen.getAndIncrement(),
                    new Segment(segment),
                    slice,
                    minKeys
            );
            parent.ads = PXMRP.embedADS(buildInternalData(slice), sk0, sk1, rng);
            parents.add(parent);
            pos += len;
        }
        return parents;
    }

    private static byte[][] serializeLeaf(EKPoint[] points) {
        byte[][] data = new byte[points.length][];
        for (int i = 0; i < points.length; i++) {
            data[i] = serializePoint(points[i]);
        }
        return data;
    }

    private static byte[][] buildInternalData(EklshPVLTree.Node[] children) {
        byte[][] data = new byte[children.length][];
        for (int i = 0; i < children.length; i++) {
            data[i] = PXMRP.computeDigest(children[i].ads);
        }
        return data;
    }

    // 30位十进制数 (10^30) 约为 100 bit，16字节(128bit) 足够存储
    private static final int BIGKEY_BYTES = 16;

    /**
     * 🔥 零拷贝序列化点用于 ADS（极速版）
     * 
     * 格式：Rank(4) + BigKey(16) = 20 bytes
     * 
     * 优化：
     *   - 使用 cachedKeyBytes 缓存，避免反复 toByteArray()
     *   - 手动写入 int，避免 ByteBuffer 开销
     * 
     * 注意：必须与 EklshPVLVerifier.serializePoint 完全一致！
     */
    private static byte[] serializePoint(EKPoint p) {
        byte[] result = new byte[4 + BIGKEY_BYTES];
        
        // 1. 手动写入 int (Rank) - 比 ByteBuffer 快
        int rank = p.globalRank;
        result[0] = (byte)(rank >>> 24);
        result[1] = (byte)(rank >>> 16);
        result[2] = (byte)(rank >>> 8);
        result[3] = (byte)(rank);
        
        // 2. 直接拷贝缓存好的 Key 字节 - 零计算！
        if (p.cachedKeyBytes != null) {
            System.arraycopy(p.cachedKeyBytes, 0, result, 4, BIGKEY_BYTES);
        } else {
            // 防御性 fallback（如果没做缓存优化）
            byte[] padded = new byte[BIGKEY_BYTES];
            if (p.bigKey != null) {
                byte[] keyBytes = p.bigKey.toByteArray();
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
}

