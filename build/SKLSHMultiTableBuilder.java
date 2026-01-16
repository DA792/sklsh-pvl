package com.mrtree.vann.build;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

/**
 * SK-LSH 多表索引构建器
 * 
 * 流程：
 * 1. SK-LSH 生成多张表（3 tables），不同随机种子
 * 2. 拼成一维整数（BigInteger）
 * 3. 按 BigInteger 升序排序
 * 4. 1D K-Means 分族（16 个 family）
 * 5. 输出 family table
 */
public class SKLSHMultiTableBuilder {

    // ===================== 配置参数 =====================
    private static int D = 32;           // 原始维度
    private static final int M = 12;     // LSH 哈希函数数量
    private static final double W = 0.8; // bucket 宽度
    private static final int OFFSET = 4; // 偏移量（确保哈希值 >= 1）
    
    private static final int NUM_TABLES = 3;     // 表数量
    private static final int NUM_FAMILIES = 16;  // 每表的 family 数量
    private static final long[] SEEDS = {1234L, 5678L, 9012L}; // 各表随机种子

    // ===================== LSH 参数（每表独立） =====================
    private double[][][] A;  // 投影矩阵 [table][M][D]
    private double[][] B;    // 偏移项 [table][M]

    // ===================== 数据结构 =====================
    static class LshEntry implements Comparable<LshEntry> {
        String key;          // 原始 key: "h1,h2,...,hM,"
        BigInteger sortKey;  // 排序用的一维整数
        int id;              // 数据点 ID
        double[] vec;        // 原始向量（用于后续构建 PVL）
        int familyId;        // 分配的 family ID

        public LshEntry(String key, BigInteger sortKey, int id, double[] vec) {
            this.key = key;
            this.sortKey = sortKey;
            this.id = id;
            this.vec = vec;
            this.familyId = -1;
        }

        @Override
        public int compareTo(LshEntry o) {
            return this.sortKey.compareTo(o.sortKey);
        }

        @Override
        public String toString() {
            return familyId + " " + id + " " + key;
        }
    }

    // ===================== 主程序入口 =====================
    public static void main(String[] args) throws Exception {
        String inputFile = "data/32.csv";
        String outputDir = "output/";

        if (args.length >= 1) inputFile = args[0];
        if (args.length >= 2) outputDir = args[1];
        if (args.length >= 3) D = Integer.parseInt(args[2]);

        System.out.println("========== SK-LSH Multi-Table Builder ==========");
        System.out.println("Input file:    " + inputFile);
        System.out.println("Output dir:    " + outputDir);
        System.out.println("Dimension:     " + D);
        System.out.println("Tables:        " + NUM_TABLES);
        System.out.println("Families/table:" + NUM_FAMILIES);
        System.out.println("LSH M=" + M + ", W=" + W + ", OFFSET=" + OFFSET);
        System.out.println("================================================");

        SKLSHMultiTableBuilder builder = new SKLSHMultiTableBuilder();
        builder.run(inputFile, outputDir);
    }

    public void run(String inputFile, String outputDir) throws Exception {
        // 1. 初始化所有表的 LSH 参数
        initAllTables();

        // 2. 读取原始数据
        List<double[]> vectors = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        loadDataset(inputFile, vectors, ids);
        System.out.println("Loaded " + vectors.size() + " vectors");

        // 3. 对每张表独立处理
        for (int t = 0; t < NUM_TABLES; t++) {
            System.out.println("\n===== Processing Table " + t + " (seed=" + SEEDS[t] + ") =====");
            processTable(t, vectors, ids, outputDir);
        }

        System.out.println("\n========== All Tables Completed ==========");
    }

    // ===================== 初始化所有表的 LSH 参数 =====================
    private void initAllTables() {
        A = new double[NUM_TABLES][M][D];
        B = new double[NUM_TABLES][M];

        for (int t = 0; t < NUM_TABLES; t++) {
            Random r = new Random(SEEDS[t]);
            for (int i = 0; i < M; i++) {
                for (int j = 0; j < D; j++) {
                    A[t][i][j] = r.nextGaussian();
                }
                B[t][i] = r.nextDouble() * W;
            }
            System.out.println("Table " + t + " LSH initialized (seed=" + SEEDS[t] + ")");
        }
    }

    // ===================== 处理单张表 =====================
    private void processTable(int tableId, List<double[]> vectors, List<Integer> ids, String outputDir) throws Exception {
        // Step 1: 生成 LSH Key
        List<LshEntry> entries = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            double[] vec = vectors.get(i);
            int id = ids.get(i);
            
            String key = computeKey(tableId, vec);
            BigInteger sortKey = keyToBigInteger(key);
            entries.add(new LshEntry(key, sortKey, id, vec));
        }

        // Step 2: 按 BigInteger 排序
        long sortStart = System.currentTimeMillis();
        Collections.sort(entries);
        long sortEnd = System.currentTimeMillis();
        System.out.println("  Sorted in " + (sortEnd - sortStart) + " ms");

        // Step 3: 1D K-Means 分族
        long kmeansStart = System.currentTimeMillis();
        kMeansSplit(entries, NUM_FAMILIES);
        long kmeansEnd = System.currentTimeMillis();
        System.out.println("  K-Means split in " + (kmeansEnd - kmeansStart) + " ms");

        // Step 4: 输出
        String siftFile = outputDir + "sift_table_" + tableId + ".txt";
        String familyFile = outputDir + "family_table_" + tableId + ".txt";
        
        createOutputDir(siftFile);
        saveSiftTable(siftFile, entries);
        saveFamilyTable(familyFile, entries);

        // 统计
        printFamilyStats(entries);
    }

    // ===================== 计算 LSH Key =====================
    private String computeKey(int tableId, double[] x) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < M; i++) {
            double dot = 0;
            for (int j = 0; j < D; j++) {
                dot += A[tableId][i][j] * x[j];
            }
            int h = (int) Math.floor((dot + B[tableId][i]) / W) + OFFSET;
            sb.append(h).append(",");
        }
        return sb.toString();
    }

    // ===================== Key 转 BigInteger（不补位，直接拼接） =====================
    private BigInteger keyToBigInteger(String key) {
        // "2,5,4,4,6,6,2,5,3,3,3,2," → "254466253332"
        String[] parts = key.split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(p);
            }
        }
        if (sb.length() == 0) return BigInteger.ZERO;
        return new BigInteger(sb.toString());
    }

    // ===================== 1D K-Means 分族 =====================
    private void kMeansSplit(List<LshEntry> entries, int K) {
        int N = entries.size();
        if (N == 0) return;

        // 转换为 double 数组（用于 K-Means）
        double[] values = new double[N];
        for (int i = 0; i < N; i++) {
            values[i] = entries.get(i).sortKey.doubleValue();
        }

        // 初始化 K 个中心点（均匀分布）
        double[] centers = new double[K];
        double minVal = values[0];
        double maxVal = values[N - 1];
        for (int k = 0; k < K; k++) {
            centers[k] = minVal + (maxVal - minVal) * (k + 0.5) / K;
        }

        // K-Means 迭代
        int[] assignments = new int[N];
        for (int iter = 0; iter < 100; iter++) {
            // 分配每个点到最近的中心
            for (int i = 0; i < N; i++) {
                double minDist = Double.MAX_VALUE;
                int bestK = 0;
                for (int k = 0; k < K; k++) {
                    double dist = Math.abs(values[i] - centers[k]);
                    if (dist < minDist) {
                        minDist = dist;
                        bestK = k;
                    }
                }
                assignments[i] = bestK;
            }

            // 更新中心点
            double[] sums = new double[K];
            int[] counts = new int[K];
            for (int i = 0; i < N; i++) {
                sums[assignments[i]] += values[i];
                counts[assignments[i]]++;
            }

            boolean converged = true;
            for (int k = 0; k < K; k++) {
                if (counts[k] > 0) {
                    double newCenter = sums[k] / counts[k];
                    if (Math.abs(newCenter - centers[k]) > 1e-9) {
                        converged = false;
                    }
                    centers[k] = newCenter;
                }
            }

            if (converged) {
                System.out.println("    K-Means converged at iteration " + iter);
                break;
            }
        }

        // 写入 familyId
        for (int i = 0; i < N; i++) {
            entries.get(i).familyId = assignments[i];
        }
    }

    // ===================== 读取数据集 =====================
    private void loadDataset(String file, List<double[]> vectors, List<Integer> ids) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(file));
        String line;
        int lineNum = 0;

        // 检测分隔符
        String delimiter = file.endsWith(".csv") ? "," : "\\s+";

        while ((line = br.readLine()) != null) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                String[] arr = line.split(delimiter);
                
                // 跳过表头
                if (lineNum == 1 && !Character.isDigit(arr[0].charAt(0)) && arr[0].charAt(0) != '-') {
                    continue;
                }

                int id;
                double[] x = new double[D];

                if (arr.length == D + 1) {
                    // 第一列是 ID
                    id = Integer.parseInt(arr[0].trim());
                    for (int i = 0; i < D; i++) {
                        x[i] = Double.parseDouble(arr[i + 1].trim());
                    }
                } else if (arr.length == D) {
                    // 没有 ID 列
                    id = lineNum;
                    for (int i = 0; i < D; i++) {
                        x[i] = Double.parseDouble(arr[i].trim());
                    }
                } else {
                    continue;
                }

                vectors.add(x);
                ids.add(id);

            } catch (NumberFormatException e) {
                // 跳过解析错误的行
            }
        }
        br.close();
    }

    // ===================== 保存 sift_table =====================
    private void saveSiftTable(String filename, List<LshEntry> entries) throws Exception {
        BufferedWriter bw = new BufferedWriter(new FileWriter(filename));
        for (LshEntry e : entries) {
            bw.write(e.key + " " + e.id);
            bw.newLine();
        }
        bw.close();
        System.out.println("  Saved sift table: " + filename);
    }

    // ===================== 保存 family_table =====================
    private void saveFamilyTable(String filename, List<LshEntry> entries) throws Exception {
        BufferedWriter bw = new BufferedWriter(new FileWriter(filename));
        for (LshEntry e : entries) {
            bw.write(e.familyId + " " + e.id + " " + e.key);
            bw.newLine();
        }
        bw.close();
        System.out.println("  Saved family table: " + filename);
    }

    // ===================== 打印 family 统计 =====================
    private void printFamilyStats(List<LshEntry> entries) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (LshEntry e : entries) {
            counts.merge(e.familyId, 1, Integer::sum);
        }
        System.out.println("  Family distribution:");
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            System.out.printf("    Family %2d: %d points%n", e.getKey(), e.getValue());
        }
    }

    // ===================== 创建输出目录 =====================
    private void createOutputDir(String outputFile) {
        File f = new File(outputFile);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }
}
