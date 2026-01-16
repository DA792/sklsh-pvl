package com.mrtree.vann.demo;

import com.mrtree.vann.model.EKPoint;
import com.mrtree.vann.query.dto.EklshPVLVO;
import com.mrtree.vann.index.EklshPVLTree;
import com.mrtree.vann.util.SkLshHasher;

import java.io.IOException;
import java.util.*;

/**
 * 三表 SK-LSH PVL 索引系统 Demo
 * 
 * 架构：
 *   GloVe-32D → 3 个 SK-LSH 表 → 3 棵 PVL 树 → 多表查询 + 结果合并 + 验证
 * 
 * 多表策略：
 *   - 每个表使用不同的随机种子
 *   - 查询时在所有表中搜索
 *   - 合并结果后按欧氏距离精排取 Top-K
 */
public class DemoMain {

    // 数据维度
    private static final int D = 32;
    // 表数量
    private static final int NUM_TABLES = 3;
    // 随机种子
    private static final long[] SEEDS = {1234L, 5678L, 9012L};

    public static void main(String[] args) throws IOException {
        System.out.println("==== Multi-Table SK-LSH PVL Demo ====");
        
        // 1. 加载 32 维数据
        String basePath = "D:/MRtree/CSQV.JAVA/data/glove32_base.csv";
        List<EKPoint> rawPoints = loadGlove32Data(basePath);
        System.out.println("Data: " + rawPoints.size() + " points, " + D + "D");
        
        // 保存原始向量引用（用于后续精排）
        // 创建 globalRank -> EKPoint 映射
        Map<Integer, EKPoint> pointMap = new HashMap<>();
        for (int i = 0; i < rawPoints.size(); i++) {
            rawPoints.get(i).globalRank = i;
            pointMap.put(i, rawPoints.get(i));
        }
        
        // 2. 创建 SK-LSH 哈希器
        SkLshHasher[] hashers = new SkLshHasher[NUM_TABLES];
        for (int t = 0; t < NUM_TABLES; t++) {
            hashers[t] = new SkLshHasher(D, 16, 0.8, 4, SEEDS[t]);
        }
        
        // 3. 为每个表构建 PVL 树
        byte[] sk0 = new byte[32];
        byte[] sk1 = new byte[32];
        new Random(123).nextBytes(sk0);
        new Random(456).nextBytes(sk1);
        
        final int ERR = 128;
        EklshPVLTree[] trees = new EklshPVLTree[NUM_TABLES];
        com.mrtree.vann.query.EklshPVLSearcher[] searchers = new com.mrtree.vann.query.EklshPVLSearcher[NUM_TABLES];
        // 每个表的 tableRank -> originalId 映射
        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] tableRankToOriginalId = new HashMap[NUM_TABLES];
        
        long totalBuildTime = 0;
        for (int t = 0; t < NUM_TABLES; t++) {
            tableRankToOriginalId[t] = new HashMap<>();
            
            List<EKPoint> tablePoints = new ArrayList<>();
            for (EKPoint p : rawPoints) {
                EKPoint copy = new EKPoint();
                copy.vec = p.vec;
                copy.clusterId = p.globalRank;
                copy.lshKey = hashers[t].hash(p.vec);
                copy.ekKey = copy.lshKey;
                tablePoints.add(copy);
            }
            
            tablePoints.sort(Comparator.comparingLong(p -> p.lshKey));
            
            for (int i = 0; i < tablePoints.size(); i++) {
                tablePoints.get(i).globalRank = i;
                tableRankToOriginalId[t].put(i, tablePoints.get(i).clusterId);
            }
            
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
        int numQueries = 200;
        int K = 10;
        int candidateK = K * 3;
        
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
        
        // 6. 执行多表查询和验证（串行，适合小规模数据）
        double sumRecall10 = 0.0;
        long totalVoBytes = 0;
        double sumQueryMs = 0.0;
        double sumVerifyMs = 0.0;
        int verifyFailCount = 0;
        
        int GT_M = 100;
        int totalHitTop10 = 0, totalHitTop50 = 0, totalHitTop100 = 0;
        int totalResultCount = 0;
        
        for (int i = 0; i < queryPoints.size(); i++) {
            EKPoint queryPt = queryPoints.get(i);
            double[] q = queryPt.vec;
            
            // Ground Truth
            List<EKPoint> trueTopM = bruteForceL2TopK(rawPoints, q, GT_M);
            Map<Integer, Integer> rankMap = new HashMap<>();
            for (int r = 0; r < trueTopM.size(); r++) {
                rankMap.put(trueTopM.get(r).globalRank, r + 1);
            }
            
            // 多表查询（串行）
            Set<Integer> candidateOriginalIds = new HashSet<>();
            List<EklshPVLVO> vos = new ArrayList<>();
            long voBytes = 0;
            
            long qs = System.nanoTime();
            for (int t = 0; t < NUM_TABLES; t++) {
                long queryKey = hashers[t].hash(q);
                EklshPVLVO vo = searchers[t].searchByKey(queryKey, candidateK);
                vos.add(vo);
                
                collectCandidatesFromVO(vo.root, t, tableRankToOriginalId, candidateOriginalIds);
                voBytes += eklshVoSizeBytes(vo);
            }
            long qe = System.nanoTime();
            sumQueryMs += (qe - qs) / 1_000_000.0;
            
            // 验证所有 VO（串行）
            long vs = System.nanoTime();
            boolean allOk = true;
            for (EklshPVLVO vo : vos) {
                if (!verifier.verify(vo)) {
                    allOk = false;
                    break;
                }
            }
            long ve = System.nanoTime();
            sumVerifyMs += (ve - vs) / 1_000_000.0;
            
            if (!allOk) {
                verifyFailCount++;
                continue;
            }
            
            totalVoBytes += voBytes;
            
            // 合并候选集，按欧氏距离精排取 Top-K
            List<EKPoint> candidates = new ArrayList<>();
            for (int originalId : candidateOriginalIds) {
                EKPoint original = pointMap.get(originalId);
                if (original != null) {
                    candidates.add(original);
                }
            }
            candidates.sort(Comparator.comparingDouble(p -> distL2(q, p.vec)));
            
            // 取 Top-K
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
        
        // 7. 输出统计结果
        int validQueries = numQueries - verifyFailCount;
        double recall10 = validQueries > 0 ? sumRecall10 / validQueries : 0.0;
        double avgVo = validQueries > 0 ? totalVoBytes * 1.0 / validQueries : 0.0;
        double avgQ = sumQueryMs / numQueries;
        double avgV = sumVerifyMs / numQueries;

        System.out.println("\n===== Results =====");
        System.out.printf("Recall@%d = %.4f | VO = %.0f B | Query = %.2f ms | Verify = %.2f ms | Fail = %d/%d%n",
                K, recall10, avgVo, avgQ, avgV, verifyFailCount, numQueries);
        System.out.printf("Hit: Top10=%.1f%% Top50=%.1f%% Top100=%.1f%%%n",
                100.0 * totalHitTop10 / totalResultCount,
                100.0 * totalHitTop50 / totalResultCount,
                100.0 * totalHitTop100 / totalResultCount);
    }

    /**
     * 加载 GloVe-32 维数据（Tab 分隔，无 ID 列）
     */
    private static List<EKPoint> loadGlove32Data(String path) throws IOException {
        List<EKPoint> points = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            String line;
            int id = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts;
                if (line.contains("\t")) {
                    parts = line.split("\t");
                } else if (line.contains(",")) {
                    parts = line.split(",");
                } else {
                    parts = line.split("\\s+");
                }
                
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
     * 从 VO 树中递归提取候选集
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
}
