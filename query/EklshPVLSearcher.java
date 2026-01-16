package com.mrtree.vann.query;

import com.mrtree.vann.index.EklshPVLTree;
import com.mrtree.vann.model.EKPoint;
import com.mrtree.vann.query.dto.EklshPVLVO;
import com.mrtree.vann.query.dto.WitnessVO;
import com.mrtree.vann.pvl.PXMRP;
import com.mrtree.vann.util.SkLshHasher;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * SK-LSH PVL Searcher
 *
 * 简化架构：
 *   - 输入：lshKey（16 位 long 整数，由 SK-LSH 哈希器生成）
 *   - PVL 树基于 EKPoint.ekKey（= lshKey）构建
 *   - 无分族、无归一化
 *
 * 流程：
 *   1. Phase 1: PVL-tree 导航定位 Anchor
 *   2. Phase 2: 窗口扩张收集 Top-K
 *   3. Phase 3: PXMRP + Witness 生成 VO
 */
public final class EklshPVLSearcher {

    private final EklshPVLTree tree;
    private final byte[] sk1;

    /**
     * 构造函数
     * 
     * @param tree PVL 树
     * @param hasher 已废弃，传 null 即可
     * @param sk1 PXMRP 密钥
     */
    public EklshPVLSearcher(EklshPVLTree tree, Object hasher, byte[] sk1) {
        this.tree = tree;
        this.sk1 = sk1;
    }

    /**
     * 核心查询接口：通过 lshKey 查询 Top-K
     *
     * @param lshKey 查询的 SK-LSH 键（16 位 long）
     * @param k 返回的结果数量
     * @return VO 对象
     */
    public EklshPVLVO searchByKey(long lshKey, int k) {
        return searchInternal(lshKey, k);
    }

    /**
     * ⭐ SIFT 128D 专用：通过完整 30 位 BigKey 查询 Top-K
     * 
     * 🚀 极速 Coarse-to-Fine 流程：
     *   1. PVL 预测位置，利用误差边界锁定搜索范围
     *   2. 在误差范围内用 long 二分找到 key16 桶的起点（Lower Bound）
     *   3. 线性遍历桶内所有相同 key16 的点
     *   4. 用 30 位 BigKey 筛选最近的 K 个点
     *   5. 生成 VO
     *
     * @param queryBigKey 查询的完整 30 位 BigInteger Key
     * @param k 返回的结果数量
     * @return VO 对象
     */
    public EklshPVLVO searchByBigKey(BigInteger queryBigKey, int k) {
        // 1. 提取高 16 位用于 PVL 粗预测
        long key16 = SkLshHasher.getHigh16(queryBigKey);
        
        EklshPVLVO vo = new EklshPVLVO();
        vo.k = k;
        vo.hq = key16;

        if (k <= 0 || tree == null || tree.root == null || tree.pointsSorted == null
                || tree.pointsSorted.isEmpty()) {
            vo.rootDigest = (tree != null ? tree.rootDigest : null);
            return vo;
        }

        List<EKPoint> pts = tree.pointsSorted;
        int n = pts.size();

        // 2. PVL 粗预测（使用高 16 位）
        int predictedPos = tree.predictRank(key16);
        
        // 3. 🚀 锁定搜索半径（由 PVL 误差提供）
        int errorBound = Math.max(tree.err * 2, 256);  // 安全系数
        int searchLow = Math.max(0, predictedPos - errorBound);
        int searchHigh = Math.min(n - 1, predictedPos + errorBound);

        // 4. 🔥 PGM 标准：在误差范围内找 key16 桶的**第一个**位置（Lower Bound）
        int bucketStart = findLowerBound16(pts, key16, searchLow, searchHigh);
        
        // 🔥 窗口溢出检查：如果找到的位置在窗口左边界，可能左边还有
        if (bucketStart == searchLow && searchLow > 0 && pts.get(searchLow - 1).ekKey == key16) {
            // 窗口溢出！在全局范围重新查找
            bucketStart = findLowerBound16(pts, key16, 0, searchLow);
            if (bucketStart == -1) {
                bucketStart = searchLow; // 恢复原值
            }
        }
        
        // 保底逻辑：如果在误差范围内没找到，全局查找
        if (bucketStart == -1) {
            bucketStart = findLowerBound16(pts, key16, 0, n - 1);
        }

        // 5. 🚀 找到锚点位置（使用 30 位 BigKey 桶内二分）
        int anchorPos;
        if (bucketStart != -1) {
            // 找到桶的右边界
            int bucketEnd = findUpperBound16(pts, key16, bucketStart, 
                    Math.min(n - 1, bucketStart + 10000));
            
            // ⭐ 桶内二分：在 [bucketStart, bucketEnd] 内精确定位 queryBigKey
            anchorPos = binarySearchBigKeyInBucket(pts, queryBigKey, bucketStart, bucketEnd);
        } else {
            // 没有完全匹配的 key16，用 BigKey 插入点作为锚点
            anchorPos = findInsertionPoint(pts, queryBigKey, searchLow, searchHigh);
        }

        // 6. 以锚点为中心窗口扩张（使用 BigKey 距离）
        RangeInfo range = expandByBigKey(queryBigKey, k, anchorPos);
        if (range == null) {
            return vo;
        }

        // 7. 🔥 精简版 VO 构建
        //    - 🚫 不再填充 vo.results（客户端从 leafPoints 提取）
        //    - rootDigest: 仅用于兼容，客户端应本地存储
        vo.leftWitness = range.leftWitness;
        vo.rightWitness = range.rightWitness;
        vo.root = buildProofNode(tree.root, range.proofL, range.proofR);
        
        // 向后兼容：只保留必要字段
        vo.rootDigest = tree.rootDigest;
        vo.k = k;
        vo.hq = key16;
        // 🔥 vo.results 不再填充，节省内存
        
        return vo;
    }

    /**
     * 🚀 在 [low, high] 范围内用 long 二分找第一个等于 target 的位置（Lower Bound）
     * 
     * 优点：纯 long 比较，硬件级速度，比 BigInteger 快 10 倍以上
     * 
     * @return 第一个匹配的索引，或 -1 表示不存在
     */
    private int findLowerBound16(List<EKPoint> pts, long target, int low, int high) {
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midKey = pts.get(mid).ekKey;
            if (midKey >= target) {
                if (midKey == target) result = mid;  // 记录位置，继续向左试探
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return result;
    }

    /**
     * 🚀 在 [low, high] 范围内用 long 二分找最后一个等于 target 的位置（Upper Bound）
     * 
     * @return 最后一个匹配的索引，或 low（如果不存在）
     */
    private int findUpperBound16(List<EKPoint> pts, long target, int low, int high) {
        int result = low;
        int n = pts.size();
        high = Math.min(high, n - 1);
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midKey = pts.get(mid).ekKey;
            if (midKey <= target) {
                if (midKey == target) result = mid;  // 记录位置，继续向右试探
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    /**
     * 🚀 桶内二分：在 key16 相同的桶 [bucketStart, bucketEnd] 内
     *    精确定位 queryBigKey 的插入位置
     * 
     * 🔥 关键：必须确保精确命中真实点，不能有任何偏移
     * 
     * @return queryBigKey 的精确位置或最近插入点
     */
    private int binarySearchBigKeyInBucket(List<EKPoint> pts, BigInteger queryBigKey, 
                                           int bucketStart, int bucketEnd) {
        int low = bucketStart;
        int high = bucketEnd;
        
        while (low <= high) {
            int mid = (low + high) >>> 1;
            // 🔥 必须拿到 30 位全量 bigKey
            BigInteger midVal = pts.get(mid).bigKey;
            if (midVal == null) {
                // 防御性：如果 bigKey 为 null（不应该发生）
                return bucketStart;
            }
            
            int cmp = midVal.compareTo(queryBigKey);
            
            if (cmp == 0) {
                return mid;  // 🚀 精确匹配，必须立即返回！
            } else if (cmp < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        
        // 没有精确匹配，返回最近的插入点
        if (low > bucketEnd) return bucketEnd;
        if (high < bucketStart) return bucketStart;
        
        BigInteger distLow = getBigKeyDiff(pts.get(low), queryBigKey);
        BigInteger distHigh = getBigKeyDiff(pts.get(high), queryBigKey);
        
        return (distLow.compareTo(distHigh) <= 0) ? low : high;
    }

    /**
     * 在范围内找到 queryBigKey 的插入点（用于 key16 不存在的情况）
     */
    private int findInsertionPoint(List<EKPoint> pts, BigInteger queryBigKey, int low, int high) {
        while (low <= high) {
            int mid = (low + high) >>> 1;
            BigInteger midVal = pts.get(mid).bigKey;
            if (midVal == null) {
                // 防御性：如果 bigKey 为 null（不应该发生）
                return low;
            }
            int cmp = midVal.compareTo(queryBigKey);
            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid;
        }
        return Math.max(0, Math.min(low, pts.size() - 1));
    }

    /**
     * 获取点的 BigKey 与查询 BigKey 的差值（绝对值）
     */
    private BigInteger getBigKeyDiff(EKPoint p, BigInteger queryBigKey) {
        if (p.bigKey != null) {
            return p.bigKey.subtract(queryBigKey).abs();
        }
        // Fallback：如果没有 bigKey，返回最大值（不应该发生）
        return BigInteger.valueOf(Long.MAX_VALUE);
    }

    /**
     * ⭐ 基于 BigKey 的窗口扩张
     * 
     * 🔥 关键：确保窗口包含 queryBigKey 自身（如果它是真实点）
     */
    private RangeInfo expandByBigKey(BigInteger queryBigKey, int k, int anchorIdx) {
        List<EKPoint> pts = tree.pointsSorted;
        if (pts == null || pts.isEmpty()) return null;

        int n = pts.size();
        anchorIdx = Math.max(0, Math.min(n - 1, anchorIdx));

        // 🔥 自包含检查：如果 anchorIdx 指向的不是 queryBigKey，在附近修正
        BigInteger anchorKey = pts.get(anchorIdx).bigKey;
        if (anchorKey == null || anchorKey.compareTo(queryBigKey) != 0) {
            // 在附近小范围扫描，确保找到"自己"
            int searchRadius = 20;
            int bestIdx = anchorIdx;
            BigInteger bestDist = getBigKeyDiff(pts.get(anchorIdx), queryBigKey);
            
            for (int i = Math.max(0, anchorIdx - searchRadius); 
                 i < Math.min(n, anchorIdx + searchRadius); i++) {
                BigInteger pKey = pts.get(i).bigKey;
                if (pKey != null && pKey.equals(queryBigKey)) {
                    bestIdx = i;  // 找到精确匹配
                    break;
                }
                // 如果没有精确匹配，记录最近的
                BigInteger dist = getBigKeyDiff(pts.get(i), queryBigKey);
                if (dist.compareTo(bestDist) < 0) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }
            anchorIdx = bestIdx;
        }

        int L = anchorIdx;
        int R = anchorIdx;
        int count = 1;

        while (count < k) {
            boolean canLeft = (L > 0);
            boolean canRight = (R < n - 1);

            if (!canLeft && !canRight) break;

            BigInteger distL = canLeft ? getBigKeyDiff(pts.get(L - 1), queryBigKey) : null;
            BigInteger distR = canRight ? getBigKeyDiff(pts.get(R + 1), queryBigKey) : null;

            boolean goLeft;
            if (!canLeft) goLeft = false;
            else if (!canRight) goLeft = true;
            else goLeft = distL.compareTo(distR) <= 0;

            if (goLeft) {
                L--;
            } else {
                R++;
            }
            count++;
        }

        // ⭐ Witness：同时存储 long key 和 BigInteger bigKey
        WitnessVO leftWitness = (L > 0)
                ? witnessOfBig(pts.get(L - 1), queryBigKey, true)
                : null;
        WitnessVO rightWitness = (R < n - 1)
                ? witnessOfBig(pts.get(R + 1), queryBigKey, false)
                : null;

        int proofL = (leftWitness != null) ? L - 1 : L;
        int proofR = (rightWitness != null) ? R + 1 : R;

        return new RangeInfo(L, R, proofL, proofR, leftWitness, rightWitness);
    }

    /**
     * 🔥 构造精简版 WitnessVO（论文同款）
     * 
     * 只需要 Key 和方向，不传 globalRank 和 distance
     * 验证器自己计算距离，通过树结构验证位置
     */
    private static WitnessVO witnessOfBig(EKPoint point, BigInteger queryBigKey, boolean isLeft) {
        BigInteger pointBigKey = (point.bigKey != null) ? point.bigKey : BigInteger.ZERO;
        // 🔥 使用精简构造器：只传 Key 和方向
        return new WitnessVO(pointBigKey, isLeft);
    }

    /**
     * 内部实现（16 位 long key 版本）
     */
    private EklshPVLVO searchInternal(long hq, int k) {
        EklshPVLVO vo = new EklshPVLVO();
        vo.k = k;
        vo.hq = hq;

        if (k <= 0 || tree == null || tree.root == null || tree.pointsSorted == null
                || tree.pointsSorted.isEmpty()) {
            vo.rootDigest = (tree != null ? tree.rootDigest : null);
            return vo;
        }

        // Phase 1: Anchor 定位
        AnchorInfo anchor = locateAnchor(hq);
        if (anchor == null) {
            vo.rootDigest = tree.rootDigest;
            return vo;
        }

        // Phase 2: 窗口扩张
        RangeInfo range = expandExactTopK(hq, k, anchor.globalIndex);
        if (range == null) {
            vo.rootDigest = tree.rootDigest;
            return vo;
        }

        // Phase 3: VO 构建
        vo.leftWitness = range.leftWitness;
        vo.rightWitness = range.rightWitness;
        vo.root = buildProofNode(tree.root, range.proofL, range.proofR);
        vo.rootDigest = tree.rootDigest;
        return vo;
    }

    /**
     * Phase 1: Anchor 定位（PVL 路由）
     *
     * 流程：
     *   1. 从 root 到 leaf 逐层用模型预测 + 局部二分
     *   2. 在叶子中精确查找或 lower_bound
     *   3. Fallback: 全局 lower_bound
     */
    private AnchorInfo locateAnchor(long hq /* lshKey */) {
        if (tree == null || tree.root == null
                || tree.pointsSorted == null || tree.pointsSorted.isEmpty()) {
            return null;
        }

        // 1. PGM-style routing from root to a leaf
        EklshPVLTree.Node node = tree.root;
        while (!node.isLeaf()) {
            EklshPVLTree.InternalNode internal = (EklshPVLTree.InternalNode) node;
            int childIdx = tree.routeToChild(internal, hq, tree.err);
            if (childIdx < 0 || childIdx >= internal.children.length) {
                int fallback = tree.lowerBoundGlobal(hq);
                return new AnchorInfo(sanitizeGlobalIndex(fallback));
            }
            node = internal.children[childIdx];
        }

        // 2. 在葉子中做「精確點查找或 lower_bound」
        EklshPVLTree.LeafNode leaf = (EklshPVLTree.LeafNode) node;
        int localIdx = tree.findInLeaf(leaf, hq, tree.err);
        int globalIdx;

        if (localIdx >= 0) {
            // found exact match in leaf
            globalIdx = leaf.globalStart + localIdx;
        } else {
            // 3. 在模型預測窗口內做 lower_bound，必要時擴大到整個葉子
            int n = leaf.keys.length;
            int posPred = (leaf.segment != null && leaf.segment.model != null)
                    ? leaf.segment.model.find(hq)
                    : 0;

            int windowL = Math.max(0, posPred - tree.err);
            int windowR = Math.min(n - 1, posPred + tree.err);
            if (windowL > windowR) {
                windowL = 0;
                windowR = n - 1;
            }

            int lb = lowerBoundLocal(leaf.keys, hq, windowL, windowR);
            if (lb < 0) {
                lb = lowerBoundLocal(leaf.keys, hq, 0, n - 1);
            }

            if (lb >= 0) {
                globalIdx = leaf.globalStart + lb;
            } else {
                // 4. 退回全局 lower_bound（pointsSorted 也是按 ekKey/normKey 排序）
                globalIdx = tree.lowerBoundGlobal(hq);
            }
        }

        globalIdx = sanitizeGlobalIndex(globalIdx);
        return new AnchorInfo(globalIdx);
    }

    /**
     * Phase 2: 窗口扩张收集 Top-K
     *
     * 从 anchorIdx 开始，向左右按 lshKey 距离贪心扩张：
     *   - dist = |point.ekKey - hq|（ekKey = lshKey）
     *   - 保持 [L, R] 物理连续
     *   - 收集满 K 个点或到达边界
     *   - Witness = 窗口外侧各 1 个点
     */
    private RangeInfo expandExactTopK(long hq, int k, int anchorIdx) {
        List<EKPoint> pts = tree.pointsSorted;
        if (pts == null || pts.isEmpty()) return null;

        int n = pts.size();
        if (anchorIdx < 0) anchorIdx = 0;
        if (anchorIdx >= n) anchorIdx = n - 1;

        int L = anchorIdx;
        int R = anchorIdx;
        int count = 1;

        while (count < k) {
            boolean canLeft = (L > 0);
            boolean canRight = (R < n - 1);

            if (!canLeft && !canRight) {
                break;
            }

            long distL = canLeft ? Math.abs(pts.get(L - 1).ekKey - hq) : Long.MAX_VALUE;
            long distR = canRight ? Math.abs(pts.get(R + 1).ekKey - hq) : Long.MAX_VALUE;

            if (distL <= distR) {
                if (canLeft) {
                    L--;
                    count++;
                } else if (canRight) {
                    R++;
                    count++;
                }
            } else {
                if (canRight) {
                    R++;
                    count++;
                } else if (canLeft) {
                    L--;
                    count++;
                }
            }
        }

        // Witness = 紧贴窗口外侧的点
        WitnessVO leftWitness = (L > 0)
                ? witnessOf(pts.get(L - 1), hq, true)
                : null;
        WitnessVO rightWitness = (R < n - 1)
                ? witnessOf(pts.get(R + 1), hq, false)
                : null;

        // Proof 覆盖范围要把 Witness 也包含进去
        int proofL = (leftWitness != null) ? L - 1 : L;
        int proofR = (rightWitness != null) ? R + 1 : R;

        return new RangeInfo(L, R, proofL, proofR, leftWitness, rightWitness);
    }

    /**
     * Phase 2: Coverage Query - 為 [targetL, targetR] 構造 VO
     * 遞迴遍歷 PVL-tree，只收集與目標範圍有交集的節點。
     */
    private EklshPVLVO.Node buildProofNode(EklshPVLTree.Node node, int targetL, int targetR) {
        if (node == null) return null;
        if (targetR < node.globalStart || targetL > node.globalEnd) return null;

        if (node.isLeaf()) {
            return buildLeafNode((EklshPVLTree.LeafNode) node, targetL, targetR);
        }

        EklshPVLTree.InternalNode internal = (EklshPVLTree.InternalNode) node;
        List<EklshPVLVO.Node> childVOs = new ArrayList<>();
        int childStart = -1;
        int childEnd = -1;

        for (int i = 0; i < internal.children.length; i++) {
            EklshPVLVO.Node child = buildProofNode(internal.children[i], targetL, targetR);
            if (child != null) {
                if (childStart == -1) childStart = i;
                childEnd = i;
                childVOs.add(child);
            }
        }

        if (childVOs.isEmpty()) return null;

        // 🔥 判断是否完全覆盖（所有子节点都被包含）
        boolean fullyCovered = (childStart == 0 && childEnd == internal.children.length - 1);
        
        byte[] piStart = PXMRP.generateProof(node.ads.ph, childStart - 1, node.ads.r, sk1);
        byte[] piEnd = PXMRP.generateProof(node.ads.ph, childEnd, node.ads.r, sk1);

        // 🔥 使用精简版 Builder
        return EklshPVLVO.Node.builder(false)
                .range(childStart, childEnd)
                .proofs(piStart, piEnd)
                .ads(node.ads.r, node.ads.len, node.ads.lastPrefix())
                .fullyCovered(fullyCovered)
                .children(childVOs)
                .build();
    }

    /**
     * 為葉節點構造 VO，只覆蓋與 [targetL, targetR] 有交集的部分。
     * 
     * 🔥 使用 VOPoint 精简体积（从 1200 bytes/点 → 44 bytes/点）
     */
    private EklshPVLVO.Node buildLeafNode(EklshPVLTree.LeafNode leaf, int targetL, int targetR) {
        int localStart = Math.max(0, targetL - leaf.globalStart);
        int localEnd = Math.min(leaf.keys.length - 1, targetR - leaf.globalStart);

        if (localStart > localEnd || localStart >= leaf.keys.length || localEnd < 0) {
            return null;
        }

        // 🔥 判断是否完全覆盖（整个叶子都被包含）
        boolean fullyCovered = (localStart == 0 && localEnd == leaf.keys.length - 1);

        // 🔥 转换为精简版 VOPoint
        List<EKPoint> slice = new ArrayList<>();
        for (int i = localStart; i <= localEnd; i++) {
            slice.add(leaf.points[i]);
        }

        byte[] piStart = PXMRP.generateProof(leaf.ads.ph, localStart - 1, leaf.ads.r, sk1);
        byte[] piEnd = PXMRP.generateProof(leaf.ads.ph, localEnd, leaf.ads.r, sk1);

        // 🔥 使用 leafPointsFromEK 自动转换为 VOPoint
        return EklshPVLVO.Node.builder(true)
                .range(localStart, localEnd)
                .proofs(piStart, piEnd)
                .ads(leaf.ads.r, leaf.ads.len, leaf.ads.lastPrefix())
                .fullyCovered(fullyCovered)
                .leafPointsFromEK(slice)
                .build();
    }

    /**
     * 在指定區間 [L, R] 上做標準 lower_bound (第一個 >= key 的位置)。
     * 若區間無有效結果，返回 -1。
     */
    private static int lowerBoundLocal(long[] keys, long key, int L, int R) {
        if (keys == null || keys.length == 0) {
            return -1;
        }
        int lo = Math.max(0, L);
        int hi = Math.min(keys.length - 1, R);
        if (lo > hi) {
            return -1;
        }

        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long midKey = keys[mid];
            if (midKey >= key) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans;
    }

    /**
     * 🔥 构造精简版 WitnessVO（论文同款）
     * 
     * 只需要 Key 和方向，不传 globalRank 和 distance
     */
    private static WitnessVO witnessOf(EKPoint point, long hq, boolean isLeft) {
        BigInteger pointBigKey = (point.bigKey != null) ? point.bigKey : BigInteger.ZERO;
        return new WitnessVO(pointBigKey, isLeft);
    }

    private int sanitizeGlobalIndex(int idx) {
        if (tree == null || tree.pointsSorted == null || tree.pointsSorted.isEmpty()) {
            return 0;
        }
        int n = tree.pointsSorted.size();
        if (idx < 0) return 0;
        if (idx >= n) return n - 1;
        return idx;
    }

    private static final class AnchorInfo {
        final int globalIndex;

        AnchorInfo(int globalIndex) {
            this.globalIndex = globalIndex;
        }
    }

    private static final class RangeInfo {
        final int resultL;
        final int resultR;
        final int proofL;
        final int proofR;
        final WitnessVO leftWitness;
        final WitnessVO rightWitness;

        RangeInfo(int resultL, int resultR,
                  int proofL, int proofR,
                  WitnessVO leftWitness,
                  WitnessVO rightWitness) {
            this.resultL = resultL;
            this.resultR = resultR;
            this.proofL = proofL;
            this.proofR = proofR;
            this.leftWitness = leftWitness;
            this.rightWitness = rightWitness;
        }
    }
}
