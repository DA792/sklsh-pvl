package com.mrtree.vann.index;

import com.mrtree.vann.index.pla.Segment;
import com.mrtree.vann.model.EKPoint;
import com.mrtree.vann.model.NodeADS;

import java.util.List;

public final class EklshPVLTree {

    public abstract static class Node {
        public final int nodeId;
        public final Segment segment;
        public final long minKey;
        public final long maxKey;
        public final int size;
        public final int globalStart;
        public final int globalEnd;
        public NodeADS ads;

        protected Node(int nodeId,
                        Segment segment,
                        long minKey,
                        long maxKey,
                        int size,
                        int globalStart,
                        int globalEnd) {
            this.nodeId = nodeId;
            this.segment = segment;
            this.minKey = minKey;
            this.maxKey = maxKey;
            this.size = size;
            this.globalStart = globalStart;
            this.globalEnd = globalEnd;
        }

        public abstract boolean isLeaf();

        public abstract int findLeftBound(long key, int err);
    }

    public static final class LeafNode extends Node {
        public final EKPoint[] points;
        public final long[] keys;

        public LeafNode(int nodeId, Segment segment, EKPoint[] points, long[] keys, int globalStart) {
            super(nodeId, segment, keys[0], keys[keys.length - 1], keys.length, globalStart, globalStart + keys.length - 1);
            this.points = points;
            this.keys = keys;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public int findLeftBound(long key, int err) {
            int idx = segment.findLeftBound(key, err);
            if (idx < 0) idx = 0;
            if (idx >= keys.length) idx = keys.length - 1;
            return idx;
        }
    }

    public static final class InternalNode extends Node {
        public final Node[] children;
        public final long[] childMinKeys;

        public InternalNode(int nodeId, Segment segment, Node[] children, long[] childMinKeys) {
            super(nodeId, segment,
                    childMinKeys[0],
                    childMinKeys[childMinKeys.length - 1],
                    childMinKeys.length,
                    children[0].globalStart,
                    children[children.length - 1].globalEnd);
            this.children = children;
            this.childMinKeys = childMinKeys;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public int findLeftBound(long key, int err) {
            int idx = segment.findLeftBound(key, err);
            if (idx < 0) idx = 0;
            if (idx >= childMinKeys.length) idx = childMinKeys.length - 1;
            return idx;
        }
    }

    public final Node root;
    public final byte[] rootDigest;
    public final int err;
    // HP-SI: family 級縮放信息（key -> scaledKey），主要由 Builder 計算並灌入 Model 中。
    public final double scale;
    public final int hashBits;
    public final List<EKPoint> pointsSorted;

    public EklshPVLTree(Node root,
                        byte[] rootDigest,
                        int err,
                        List<EKPoint> pointsSorted,
                        double scale,
                        int hashBits) {
        this.root = root;
        this.rootDigest = rootDigest;
        this.err = err;
        this.pointsSorted = pointsSorted;
        this.scale = scale;
        this.hashBits = hashBits;
    }

    public int predictRank(long key) {
        if (root == null || pointsSorted.isEmpty()) return 0;
        int idx = predictRec(root, key);
        if (idx < 0) idx = 0;
        if (idx >= pointsSorted.size()) idx = pointsSorted.size() - 1;
        return idx;
    }

    private int predictRec(Node node, long key) {
        if (node.isLeaf()) {
            int local = node.findLeftBound(key, err);
            return node.globalStart + local;
        }
        InternalNode internal = (InternalNode) node;
        int childIdx = internal.findLeftBound(key, err);
        return predictRec(internal.children[childIdx], key);
    }

    /**
     * PGM/PVL 正統「點查詢」接口：
     *  - 自頂向下：在每個 InternalNode 上用模型預測 + 局部二分，路由到對應子節點；
     *  - 到葉子後：用葉子段的模型預測 + 局部二分，在 leaf.keys[] 中查找是否存在 key。
     *
     * @return true 表示 key 存在於本 PVL 樹中；false 則不存在。
     */
    public boolean pointExists(long key) {
        if (root == null) return false;

        Node node = root;
        // 1. 自頂向下路由到葉子
        while (!node.isLeaf()) {
            InternalNode internal = (InternalNode) node;
            int childIdx = routeToChild(internal, key, err);
            if (childIdx < 0 || childIdx >= internal.children.length) {
                return false;
            }
            node = internal.children[childIdx];
        }

        // 2. 在葉子中做局部二分查找
        LeafNode leaf = (LeafNode) node;
        int idx = findInLeaf(leaf, key, err);
        return idx >= 0;
    }

    /**
     * 在 InternalNode 上執行「PGM 風格」路由：
     *  1) 利用節點的線性模型預測 key 應該落在哪個子節點附近 (posPred)
     *  2) 在 [posPred - err, posPred + err] 這個子節點索引範圍內，
     *     對 internal.childMinKeys[] 做二分查找，尋找負責 key 的 child index。
     * 
     * 🔥 PGM 標準：必須導航到**第一個** minKey <= key 的子節點
     */
    public int routeToChild(InternalNode node, long key, int err) {
        int n = node.childMinKeys.length;
        if (n == 0 || node.segment == null || node.segment.model == null) {
            return -1;
        }

        // 1. 模型預測「大致的 child index」
        int posPred = node.segment.model.find(key);

        // 2. 限制搜索窗口到 [L, R]
        int L = Math.max(0, posPred - err);
        int R = Math.min(n - 1, posPred + err);
        if (L > R) {
            L = 0;
            R = n - 1;
        }

        // 3. 在 childMinKeys[L..R] 上找「最後一個 <= key」的位置
        //    這是 Upper Bound - 1 的語義
        long[] keys = node.childMinKeys;
        int lo = L, hi = R;
        int childIdx = L; // 默認為左邊界
        
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long midKey = keys[mid];
            if (midKey <= key) {
                childIdx = mid;  // 記錄位置，繼續向右試探
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        // 🔥 PGM 關鍵：如果有多個相同的 minKey，回溯到第一個
        while (childIdx > 0 && keys[childIdx - 1] == keys[childIdx]) {
            childIdx--;
        }
        
        // 🔥 窗口溢出檢查：如果 childIdx 在窗口左邊界，可能左邊還有
        if (childIdx == L && L > 0 && keys[L - 1] <= key) {
            // 窗口溢出，需要在全局範圍內重新查找
            return routeToChildGlobal(node, key);
        }

        if (childIdx < 0) childIdx = 0;
        if (childIdx >= n) childIdx = n - 1;
        return childIdx;
    }
    
    /**
     * 🔥 全局路由（窗口溢出時的保底）
     */
    private int routeToChildGlobal(InternalNode node, long key) {
        long[] keys = node.childMinKeys;
        int n = keys.length;
        
        // 找最後一個 <= key 的位置
        int lo = 0, hi = n - 1;
        int childIdx = 0;
        
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] <= key) {
                childIdx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        
        // 回溯到第一個相同的 minKey
        while (childIdx > 0 && keys[childIdx - 1] == keys[childIdx]) {
            childIdx--;
        }
        
        return childIdx;
    }

    /**
     * 在葉子節點中查找 key：
     *  1) 用葉子段的模型預測 key 在本葉中的大致位置 posPred
     *  2) 在 [posPred - err, posPred + err] 範圍內對 leaf.keys[] 做二分查找
     *
     * @return 找到則返回局部 index，否則返回 -1
     */
    public int findInLeaf(LeafNode leaf, long key, int err) {
        int n = leaf.keys.length;
        if (n == 0 || leaf.segment == null || leaf.segment.model == null) {
            return -1;
        }

        // 1. 模型預測在本葉內的大致下標
        int posPred = leaf.segment.model.find(key);

        // 2. 限制到 [0, n-1] 並套用誤差窗口
        int L = Math.max(0, posPred - err);
        int R = Math.min(n - 1, posPred + err);
        if (L > R) {
            L = 0;
            R = n - 1;
        }

        // 3. 在 keys[L..R] 上做標準二分查找 key
        long[] keys = leaf.keys;
        int lo = L, hi = R;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long midKey = keys[mid];
            if (midKey == key) {
                return mid;  // 找到了
            } else if (midKey < key) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;   // 沒找到
    }

    /**
     * 在全局排序後的一維陣列 pointsSorted 上執行 lower_bound(key)：
     *  - 返回第一個 ekKey >= key 的全局下標；
     *  - 若 key 大於所有元素，則返回最後一個下標（錨定到最右側）。
     */
    public int lowerBoundGlobal(long key) {
        if (pointsSorted == null || pointsSorted.isEmpty()) {
            return 0;
        }
        int lo = 0;
        int hi = pointsSorted.size() - 1;
        int ans = pointsSorted.size();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long midKey = pointsSorted.get(mid).ekKey;
            if (midKey >= key) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (ans == pointsSorted.size()) {
            return pointsSorted.size() - 1;
        }
        return Math.max(ans, 0);
    }
}

