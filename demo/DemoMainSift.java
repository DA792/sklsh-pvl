package com.mrtree.vann.demo;

import com.mrtree.vann.model.EKPoint;
import com.mrtree.vann.query.dto.EklshPVLVO;
import com.mrtree.vann.index.EklshPVLTree;
import com.mrtree.vann.util.SkLshHasher;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/**
 * SIFT 128D + Coarse-to-Fine PVL 索引系统 Demo
 * 
 * 架构：
 *   SIFT 128D → SK-LSH (M=30) → 30位 BigInteger → 全局排序 → 高16位 PVL
 * 
 * 策略：
 *   - 存储层：按 30 位 BigInteger 排序
 *   - 索引层：PVL 用高 16 位 (long) 预测
 *   - 查询层：粗预测 → 精锚点修正 → 窗口扩张
 */
public class DemoMainSift {

    // SIFT 参数
    private static final int D = 128;           // SIFT 特征维度
    private static final int M = 30;            // 哈希函数数量（生成 30 位 Key）
    private static final double W = 1000;     // bucket 宽度
    private static final int OFFSET = 5;        // 偏移量

    // 多表参数
    private static final int NUM_TABLES = 3;
    private static final long[] SEEDS = {1234L, 5678L, 9012L};

    public static void main(String[] args) throws IOException {
        System.out.println("==== SIFT 128D Coarse-to-Fine PVL Demo ====");
        
        // 1. 加载 SIFT 数据
        String basePath = "D:/MRtree/CSQV.JAVA/data/sift_base.csv";
        List<EKPoint> rawPoints = loadSiftData(basePath);
        System.out.println("Data: " + rawPoints.size() + " points, " + D + "D");
        
        // 保存原始 ID 映射
        Map<Integer, EKPoint> pointMap = new HashMap<>();
        for (int i = 0; i < rawPoints.size(); i++) {
            rawPoints.get(i).globalRank = i;
            pointMap.put(i, rawPoints.get(i));
        }
        
        // 2. 创建 SK-LSH 哈希器（M=30，生成 30 位 BigInteger）
        SkLshHasher[] hashers = new SkLshHasher[NUM_TABLES];
        for (int t = 0; t < NUM_TABLES; t++) {
            hashers[t] = new SkLshHasher(D, M, W, OFFSET, SEEDS[t]);
        }
        
        // 3. 构建 PVL 树
        byte[] sk0 = new byte[32];
        byte[] sk1 = new byte[32];
        new Random(123).nextBytes(sk0);
        new Random(456).nextBytes(sk1);
        
        final int ERR = 512;
        EklshPVLTree[] trees = new EklshPVLTree[NUM_TABLES];
        com.mrtree.vann.query.EklshPVLSearcher[] searchers = new com.mrtree.vann.query.EklshPVLSearcher[NUM_TABLES];
        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] tableRankToOriginalId = new HashMap[NUM_TABLES];
        
        long totalBuildTime = 0;
        for (int t = 0; t < NUM_TABLES; t++) {
            tableRankToOriginalId[t] = new HashMap<>();
            
            List<EKPoint> tablePoints = new ArrayList<>();
            for (EKPoint p : rawPoints) {
                EKPoint copy = new EKPoint();
                copy.vec = p.vec;
                copy.clusterId = p.globalRank;  // 保存原始 ID
                
                // ⭐ 计算 30 位 BigInteger Key
                copy.bigKey = hashers[t].hashBig(p.vec);
                
                // 🔥 立即缓存字节（只做这一次！）
                copy.cachedKeyBytes = bigIntegerTo16Bytes(copy.bigKey);
                
                // ⭐ 提取高 16 位给 PVL 预测
                copy.ekKey = SkLshHasher.getHigh16(copy.bigKey);
                copy.lshKey = copy.ekKey;
                
                tablePoints.add(copy);
            }
            
            // ⭐ 按 30 位 BigInteger 排序（核心！）
            tablePoints.sort((a, b) -> a.bigKey.compareTo(b.bigKey));
            
            // 分配 globalRank
            for (int i = 0; i < tablePoints.size(); i++) {
                tablePoints.get(i).globalRank = i;
                tableRankToOriginalId[t].put(i, tablePoints.get(i).clusterId);
            }
            
            // 构建 PVL 树（使用高 16 位 ekKey）
            long buildStart = System.nanoTime();
            trees[t] = com.mrtree.vann.index.EklshPVLBuilder.build(tablePoints, sk0, sk1, ERR);
            long buildEnd = System.nanoTime();
            totalBuildTime += (buildEnd - buildStart);
            
            searchers[t] = new com.mrtree.vann.query.EklshPVLSearcher(trees[t], null, sk1);
        }
        System.out.printf("Build: %d tables, %.2f ms%n", NUM_TABLES, totalBuildTime / 1_000_000.0);
        
        // 4. 创建 Verifier
        com.mrtree.vann.verify.EklshPVLVerifier verifier = 
                new com.mrtree.vann.verify.EklshPVLVerifier(sk0, sk1);
        
        // 5. 随机选取查询点（每次运行不同）
        int numQueries = 100;
        int K = 10;
        int candidateK = 100;  // ⭐ 每表返回 100 个候选
        
        // 🔥 使用当前时间作为种子，确保每次运行查询点不同
        long querySeed = System.currentTimeMillis();
        System.out.println("Query seed: " + querySeed);
        Random rng = new Random(querySeed);
        
        List<EKPoint> queryPoints = new ArrayList<>();
        Set<Integer> selectedIndices = new HashSet<>();
        while (queryPoints.size() < numQueries && selectedIndices.size() < rawPoints.size()) {
            int idx = rng.nextInt(rawPoints.size());
            if (!selectedIndices.contains(idx)) {
                selectedIndices.add(idx);
                queryPoints.add(rawPoints.get(idx));
            }
        }
        
        // ⭐ 预计算所有查询的 Ground Truth（避免查询循环中重复暴力搜索）
        int GT_M = 100;
        System.out.println("Computing Ground Truth for " + numQueries + " queries...");
        long gtStart = System.currentTimeMillis();
        List<Map<Integer, Integer>> allGtRankMaps = new ArrayList<>();
        for (int i = 0; i < queryPoints.size(); i++) {
            List<EKPoint> trueTopM = bruteForceL2TopK(rawPoints, queryPoints.get(i).vec, GT_M);
            Map<Integer, Integer> rankMap = new HashMap<>();
            for (int r = 0; r < trueTopM.size(); r++) {
                rankMap.put(trueTopM.get(r).globalRank, r + 1);
            }
            allGtRankMaps.add(rankMap);
            if ((i + 1) % 50 == 0) {
                System.out.printf("  GT progress: %d/%d%n", i + 1, numQueries);
            }
        }
        long gtEnd = System.currentTimeMillis();
        System.out.printf("Ground Truth computed in %.2f s%n", (gtEnd - gtStart) / 1000.0);
        
        // 6. 执行多表查询（🔥 并行版本）
        ExecutorService queryExecutor = Executors.newFixedThreadPool(NUM_TABLES);
        ExecutorService verifyExecutor = Executors.newFixedThreadPool(NUM_TABLES);
        
        double sumRecall10 = 0.0;
        long totalVoBytes = 0;
        double sumQueryMs = 0.0;
        double sumVerifyMs = 0.0;
        int verifyFailCount = 0;
        
        int totalHitTop10 = 0, totalHitTop50 = 0, totalHitTop100 = 0;
        int totalResultCount = 0;
        
        // 用于线程安全传递结果
        final com.mrtree.vann.query.EklshPVLSearcher[] finalSearchers = searchers;
        final SkLshHasher[] finalHashers = hashers;
        
        for (int i = 0; i < queryPoints.size(); i++) {
            EKPoint queryPt = queryPoints.get(i);
            double[] q = queryPt.vec;
            
            // ⭐ 使用预计算的 Ground Truth
            Map<Integer, Integer> rankMap = allGtRankMaps.get(i);
            
            // 多表查询（🔥 并行）
            Set<Integer> candidateOriginalIds = Collections.synchronizedSet(new HashSet<>());
            EklshPVLVO[] vos = new EklshPVLVO[NUM_TABLES];
            BigInteger[] queryBigKeys = new BigInteger[NUM_TABLES];
            long[] voBytesList = new long[NUM_TABLES];
            
            long qs = System.nanoTime();
            
            // 🔥 并行提交所有查询任务
            List<Future<?>> queryFutures = new ArrayList<>();
            for (int t = 0; t < NUM_TABLES; t++) {
                final int tableIdx = t;
                final double[] qVec = q;
                queryFutures.add(queryExecutor.submit(() -> {
                    // ⭐ 计算完整 30 位 BigKey
                    BigInteger queryBigKey = finalHashers[tableIdx].hashBig(qVec);
                    queryBigKeys[tableIdx] = queryBigKey;
                    
                    // ⭐ 使用 searchByBigKey 进行 Coarse-to-Fine 查询
                    EklshPVLVO vo = finalSearchers[tableIdx].searchByBigKey(queryBigKey, candidateK);
                    vos[tableIdx] = vo;
                    
                    // 🔥 从精简版 leafPoints 提取候选集
                    collectCandidatesFromVO(vo.root, tableIdx, tableRankToOriginalId, candidateOriginalIds);
                    
                    voBytesList[tableIdx] = eklshVoSizeBytes(vo);
                }));
            }
            
            // 等待所有查询完成
            for (Future<?> f : queryFutures) {
                try { f.get(); } catch (Exception e) { e.printStackTrace(); }
            }
            
            long qe = System.nanoTime();
            sumQueryMs += (qe - qs) / 1_000_000.0;
            
            long voBytes = 0;
            for (int t = 0; t < NUM_TABLES; t++) voBytes += voBytesList[t];
            
            // ⭐ 验证（🔥 并行）
            long vs = System.nanoTime();
            List<Future<Boolean>> verifyFutures = new ArrayList<>();
            
            final com.mrtree.vann.verify.EklshPVLVerifier finalVerifier = verifier;
            for (int t = 0; t < NUM_TABLES; t++) {
                final int tableIdx = t;
                verifyFutures.add(verifyExecutor.submit(() -> 
                    finalVerifier.verify(vos[tableIdx], queryBigKeys[tableIdx])
                ));
            }
            
            // 等待所有验证完成并检查结果
            boolean allOk = true;
            for (Future<Boolean> f : verifyFutures) {
                try {
                    if (!f.get()) {
                        allOk = false;
                    }
                } catch (Exception e) {
                    allOk = false;
                    e.printStackTrace();
                }
            }
            
            long ve = System.nanoTime();
            sumVerifyMs += (ve - vs) / 1_000_000.0;
            
            if (!allOk) {
                verifyFailCount++;
                continue;
            }
            
            totalVoBytes += voBytes;
            
            // 合并候选集，按欧氏距离精排
            List<EKPoint> candidates = new ArrayList<>();
            for (int originalId : candidateOriginalIds) {
                EKPoint original = pointMap.get(originalId);
                if (original != null) {
                    candidates.add(original);
                }
            }
            candidates.sort(Comparator.comparingDouble(p -> distL2(q, p.vec)));
            
            List<EKPoint> finalResults = candidates.subList(0, Math.min(K, candidates.size()));
            
            // 计算召回率
            int hitTopK = 0, hitTop10 = 0, hitTop50 = 0, hitTop100 = 0;
            for (EKPoint p : finalResults) {
                Integer trueRank = rankMap.get(p.globalRank);
                if (trueRank != null) {
                    if (trueRank <= K) hitTopK++;
                    if (trueRank <= 10) hitTop10++;
                    if (trueRank <= 50) hitTop50++;
                    if (trueRank <= 100) hitTop100++;
                }
            }
            
            sumRecall10 += (hitTopK / (double) K);
            totalHitTop10 += hitTop10;
            totalHitTop50 += hitTop50;
            totalHitTop100 += hitTop100;
            totalResultCount += finalResults.size();
        }
        
        // 7. 输出统计
        int validQueries = numQueries - verifyFailCount;
        double recall10 = validQueries > 0 ? sumRecall10 / validQueries : 0.0;
        double avgVo = validQueries > 0 ? totalVoBytes * 1.0 / validQueries : 0.0;
        double avgQ = sumQueryMs / numQueries;
        double avgV = sumVerifyMs / numQueries;

        // 🔥 关闭线程池
        queryExecutor.shutdown();
        verifyExecutor.shutdown();
        
        System.out.println("\n===== Results =====");
        System.out.printf("Recall@%d = %.4f | VO = %.0f B | Query = %.2f ms | Verify = %.2f ms | Fail = %d/%d%n",
                K, recall10, avgVo, avgQ, avgV, verifyFailCount, numQueries);
        if (totalResultCount > 0) {
            System.out.printf("Hit: Top10=%.1f%% Top50=%.1f%% Top100=%.1f%%%n",
                    100.0 * totalHitTop10 / totalResultCount,
                    100.0 * totalHitTop50 / totalResultCount,
                    100.0 * totalHitTop100 / totalResultCount);
        }
    }

    /**
     * 加载 SIFT 128D 数据（逗号分隔，无 ID 列）
     */
    private static List<EKPoint> loadSiftData(String path) throws IOException {
        List<EKPoint> points = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            String line;
            int id = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split(",");
                if (parts.length < D) continue;
                
                double[] vec = new double[D];
                try {
                    for (int i = 0; i < D; i++) {
                        vec[i] = Double.parseDouble(parts[i].trim());
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
                
                EKPoint p = new EKPoint();
                p.vec = vec;
                p.globalRank = id++;
                points.add(p);
            }
        }
        return points;
    }

    /**
     * 暴力搜索 Ground Truth Top-K
     */
    private static List<EKPoint> bruteForceL2TopK(List<EKPoint> points, double[] qVec, int k) {
        PriorityQueue<EKPoint> pq = new PriorityQueue<>(k, (a, b) -> {
            double d1 = distL2(qVec, a.vec);
            double d2 = distL2(qVec, b.vec);
            return Double.compare(d2, d1);
        });
        
        for (EKPoint p : points) {
            pq.add(p);
            if (pq.size() > k) {
                pq.poll();
            }
        }
        
        List<EKPoint> res = new ArrayList<>(pq);
        res.sort(Comparator.comparingDouble(p -> distL2(qVec, p.vec)));
        return res;
    }
    
    /**
     * 欧氏距离平方
     */
    private static double distL2(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }

    /**
     * 🔥 VO 紧凑序列化大小（真实传输大小）
     */
    private static long eklshVoSizeBytes(EklshPVLVO vo) {
        if (vo == null) return 0;
        return vo.compactSize();
    }
    
    /**
     * 🔥 分析 VO 结构
     */
    private static void analyzeVOStructure(EklshPVLVO vo) {
        if (vo == null || vo.root == null) return;
        
        int[] nodeCount = {0};
        int[] leafCount = {0};
        int[] totalLeafPoints = {0};
        int[] proofBytes = {0};
        int[] deltaBytes = {0};
        
        analyzeNodeRecursive(vo.root, nodeCount, leafCount, totalLeafPoints, proofBytes, deltaBytes, 0);
        
        int compactSize = vo.compactSize();
        long oldSize = eklshVoSizeBytesOld(vo);
        
        System.out.printf("  Node count: %d (Leaf: %d, Internal: %d)%n", 
                nodeCount[0], leafCount[0], nodeCount[0] - leafCount[0]);
        System.out.printf("  Total LeafPoints: %d%n", totalLeafPoints[0]);
        System.out.printf("  Node overhead: %d bytes (%.1f%%)%n", 
                proofBytes[0], 100.0 * proofBytes[0] / compactSize);
        System.out.printf("  LeafPoints size: %d bytes (%.1f%%)%n", 
                deltaBytes[0], 100.0 * deltaBytes[0] / compactSize);
        System.out.printf("  Compact size: %d bytes%n", compactSize);
        System.out.printf("  Old estimate: %d bytes%n", oldSize);
        System.out.printf("  Savings: %.1f%%%n", 100.0 * (oldSize - compactSize) / oldSize);
    }
    
    private static void analyzeNodeRecursive(EklshPVLVO.Node node, 
            int[] nodeCount, int[] leafCount, int[] totalLeafPoints, 
            int[] proofBytes, int[] deltaBytes, int depth) {
        nodeCount[0]++;
        
        // Node 固定开销（根据 fullyCovered 优化）
        int nodeOverhead = 1 + 8 + 2; // flags + r + VarInt(len)
        if (!node.fullyCovered) {
            nodeOverhead += 2 + 2; // VarInt(range)
            if (node.piStart != null) {
                nodeOverhead += 32 * 3; // piStart + piEnd + phLast
            }
        }
        proofBytes[0] += nodeOverhead;
        
        if (node.leaf) {
            leafCount[0]++;
            if (node.leafPoints != null) {
                totalLeafPoints[0] += node.leafPoints.size();
                // 估算 Delta 编码大小
                deltaBytes[0] += estimateDeltaSize(node.leafPoints);
            }
        } else {
            for (EklshPVLVO.Node child : node.children) {
                analyzeNodeRecursive(child, nodeCount, leafCount, totalLeafPoints, 
                        proofBytes, deltaBytes, depth + 1);
            }
        }
    }
    
    private static int estimateDeltaSize(List<com.mrtree.vann.query.dto.VOPoint> points) {
        if (points == null || points.isEmpty()) return 1;
        int size = 1 + 4 + 16; // count(VarInt) + first rank + first key
        
        java.math.BigInteger prevKey = points.get(0).getBigKey();
        for (int i = 1; i < points.size(); i++) {
            java.math.BigInteger keyDelta = points.get(i).getBigKey().subtract(prevKey);
            byte[] deltaBytes = keyDelta.toByteArray();
            size += 1 + 1 + deltaBytes.length; // rankDelta(VarInt) + lenVarInt + delta
            prevKey = points.get(i).getBigKey();
        }
        return size;
    }
    
    /**
     * 旧版估算 VO 大小（用于对比）
     */
    private static long eklshVoSizeBytesOld(EklshPVLVO vo) {
        if (vo == null || vo.root == null) return 0;
        long bytes = 0;
        if (vo.rootDigest != null) bytes += vo.rootDigest.length;
        bytes += sizeNode(vo.root);
        if (vo.leftWitness != null) bytes += 8 + 8 + 1 + 4;
        if (vo.rightWitness != null) bytes += 8 + 8 + 1 + 4;
        return bytes;
    }

    private static long sizeNode(EklshPVLVO.Node node) {
        // Node 固定开销: leaf(1) + startPos(4) + endPos(4) + r(8) + len(4)
        long bytes = 1 + 4 + 4 + 8 + 4;
        if (node.piStart != null) bytes += node.piStart.length;  // 通常 32 bytes
        if (node.piEnd != null) bytes += node.piEnd.length;      // 通常 32 bytes
        if (node.phLast != null) bytes += node.phLast.length;    // 通常 32 bytes
        
        // 🔥 VOPoint 精简后大小：globalRank(4) + cachedKeyBytes(16) = 20 bytes
        if (node.leafPoints != null) {
            bytes += (long) node.leafPoints.size() * 20;
        }
        
        for (EklshPVLVO.Node child : node.children) {
            bytes += sizeNode(child);
        }
        return bytes;
    }

    /**
     * 🔥 从精简版 VO 树中递归提取候选集
     * 
     * 不再依赖 vo.results（已废弃），直接从 leafPoints 获取 globalRank
     */
    private static void collectCandidatesFromVO(
            EklshPVLVO.Node node, 
            int tableIdx,
            Map<Integer, Integer>[] tableRankToOriginalId, 
            Set<Integer> candidateOriginalIds) {
        if (node == null) return;
        
        if (node.leaf && node.leafPoints != null) {
            for (com.mrtree.vann.query.dto.VOPoint vp : node.leafPoints) {
                Integer originalId = tableRankToOriginalId[tableIdx].get(vp.globalRank);
                if (originalId != null) {
                    candidateOriginalIds.add(originalId);
                }
            }
        } else {
            for (EklshPVLVO.Node child : node.children) {
                collectCandidatesFromVO(child, tableIdx, tableRankToOriginalId, candidateOriginalIds);
            }
        }
    }

    /**
     * 🔥 高效转换 BigInteger -> Fixed 16 Bytes
     * 只在构建时调用一次，验证时直接使用缓存
     */
    private static byte[] bigIntegerTo16Bytes(BigInteger val) {
        if (val == null) return new byte[16];
        
        byte[] raw = val.toByteArray();
        if (raw.length == 16) return raw;  // 刚好16字节，直接用
        
        byte[] padded = new byte[16];
        int offset = 16 - raw.length;
        if (offset >= 0) {
            System.arraycopy(raw, 0, padded, offset, raw.length);
        } else {
            // 截断（理论上不会发生，30位 < 128bit）
            System.arraycopy(raw, raw.length - 16, padded, 0, 16);
        }
        return padded;
    }
}

