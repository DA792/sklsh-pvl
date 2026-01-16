package com.mrtree.vann.verify;

import com.mrtree.vann.pvl.PXMRP;
import com.mrtree.vann.pvl.PVSHashUtils;
import com.mrtree.vann.query.dto.EklshPVLVO;
import com.mrtree.vann.query.dto.VOPoint;
import com.mrtree.vann.query.dto.WitnessVO;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 🔥 精简版 PVL Verifier（论文同款）
 * 
 * 验证流程：
 *   1. Root Digest 验证（可选，客户端本地存储）
 *   2. PXMRP 结构验证（递归，包含数量检查）
 *   3. BigKey Witness 检查（局部最优性）
 * 
 * 核心设计：
 *   - 只有一个验证入口：verify(vo, queryBigKey, clientRootDigest)
 *   - 不依赖 vo.results（从 leafPoints 提取）
 *   - 信任边界完全在 Verifier 内部
 */
public final class EklshPVLVerifier {

    private final byte[] sk0;
    private final byte[] sk1;

    public EklshPVLVerifier(byte[] sk0, byte[] sk1) {
        this.sk0 = sk0;
        this.sk1 = sk1;
    }

    /**
     * 🔥 唯一验证入口
     * 
     * @param vo VO 对象（纯数据容器）
     * @param queryBigKey 查询的完整 30 位 BigInteger Key
     * @param clientRootDigest 客户端本地存储的根摘要（可选，传 null 则跳过）
     * @return 验证是否通过
     */
    public boolean verify(EklshPVLVO vo, BigInteger queryBigKey, byte[] clientRootDigest) {
        if (vo == null || vo.root == null) {
            System.out.println("Verify failed: VO or root is null");
            return false;
        }
        
        // 1. Root Digest 验证（使用客户端本地存储的摘要）
        if (clientRootDigest != null) {
            byte[] computed = digestFromNode(vo.root);
            if (!java.util.Arrays.equals(clientRootDigest, computed)) {
                System.out.println("Verify failed: Root digest mismatch");
                return false;
            }
        }
        
        // 2. PXMRP 结构验证（递归，包含数量检查）
        if (!verifyNode(vo.root)) {
            System.out.println("Verify failed: Node PXMRP check failed");
            return false;
        }
        
        // 3. BigKey Witness Check（局部最优性）
        if (!witnessCheckBig(vo, queryBigKey)) {
            System.out.println("Verify failed: BigKey witness check failed");
            return false;
        }
        
        return true;
    }
    
    /**
     * 兼容旧接口（使用 vo.rootDigest）
     */
    public boolean verify(EklshPVLVO vo, BigInteger queryBigKey) {
        return verify(vo, queryBigKey, vo.rootDigest);
    }
    
    /**
     * 🔄 兼容 16 位 long key 的旧接口
     * 
     * 用于 GloVe 32D 等低维数据的 Demo
     * 内部转换为 BigInteger 调用新接口
     */
    public boolean verify(EklshPVLVO vo) {
        // 使用 vo.hq (16 位 key) 转换为 BigInteger
        BigInteger queryBigKey = BigInteger.valueOf(vo.hq);
        return verify(vo, queryBigKey, vo.rootDigest);
    }

    // ==================== 核心验证逻辑 ====================

    /**
     * 🔥 PXMRP 结构验证（递归）
     */
    private boolean verifyNode(EklshPVLVO.Node node) {
        byte[][] payload = buildPayload(node);
        if (!PXMRP.verify(node.piStart, node.piEnd, payload, node.startPos, node.endPos, node.r, sk0, sk1)) {
            return false;
        }
        
        if (!node.leaf) {
            // 验证子节点数量与声明的范围匹配
            if (node.children.size() != (node.endPos - node.startPos + 1)) {
                System.out.println("Verify Failed: Children count mismatch");
                return false;
            }
            
            // 递归验证子节点
            for (EklshPVLVO.Node child : node.children) {
                if (!verifyNode(child)) return false;
            }
        }
        return true;
    }

    /**
     * 🔥 BigKey 局部最优性检查
     * 
     * Witness 距离必须 >= 结果集最远距离
     * 🔥 使用精简版 VOPoint
     */
    private boolean witnessCheckBig(EklshPVLVO vo, BigInteger queryBigKey) {
        // 🔥 在 Verifier 内部提取叶子节点数据
        List<VOPoint> leafPoints = collectTrustedPoints(vo.root);
        
        if (leafPoints == null || leafPoints.isEmpty()) {
            return true;
        }
        
        // 获取 Witness 的 BigKey（用于排除）
        BigInteger leftWitnessKey = (vo.leftWitness != null) ? getWitnessBigKey(vo.leftWitness) : null;
        BigInteger rightWitnessKey = (vo.rightWitness != null) ? getWitnessBigKey(vo.rightWitness) : null;
        
        // 🔥 计算结果集（排除 Witness）中最远的距离
        BigInteger worstDist = BigInteger.ZERO;
        for (VOPoint p : leafPoints) {
            BigInteger key = p.getBigKey();
            
            // 排除 Witness 点
            if (leftWitnessKey != null && key.equals(leftWitnessKey)) continue;
            if (rightWitnessKey != null && key.equals(rightWitnessKey)) continue;
            
            BigInteger dist = key.subtract(queryBigKey).abs();
            if (dist.compareTo(worstDist) > 0) {
                worstDist = dist;
            }
        }
        
        // 检查 Witness 距离
        if (vo.leftWitness != null) {
            BigInteger leftDist = leftWitnessKey.subtract(queryBigKey).abs();
            if (leftDist.compareTo(worstDist) < 0) {
                return false;
            }
        }
        
        if (vo.rightWitness != null) {
            BigInteger rightDist = rightWitnessKey.subtract(queryBigKey).abs();
            if (rightDist.compareTo(worstDist) < 0) {
                return false;
            }
        }
        
        return true;
    }

    // ==================== 辅助方法 ====================

    /**
     * 🔥 从验证后的树中提取所有叶子节点数据
     * 
     * 信任边界在 Verifier 内部，不依赖 VO 的任何方法
     * 🔥 使用精简版 VOPoint
     */
    private List<VOPoint> collectTrustedPoints(EklshPVLVO.Node node) {
        List<VOPoint> result = new ArrayList<>();
        collectPointsRecursive(node, result);
        return result;
    }
    
    private void collectPointsRecursive(EklshPVLVO.Node node, List<VOPoint> acc) {
        if (node.leaf) {
            acc.addAll(node.leafPoints);
        } else {
            for (EklshPVLVO.Node child : node.children) {
                collectPointsRecursive(child, acc);
            }
        }
    }

    /**
     * 获取 Witness 的 BigKey
     */
    private BigInteger getWitnessBigKey(WitnessVO w) {
        return w.getBigKey();
    }

    /**
     * 构建 PXMRP 验证的 payload
     * 🔥 使用精简版 VOPoint
     */
    private byte[][] buildPayload(EklshPVLVO.Node node) {
        if (node.leaf) {
            byte[][] data = new byte[node.leafPoints.size()][];
            for (int i = 0; i < node.leafPoints.size(); i++) {
                // 🔥 直接使用 VOPoint.serialize()
                data[i] = node.leafPoints.get(i).serialize();
            }
            return data;
        }
        // 内部节点：payload 是子节点的摘要
        byte[][] data = new byte[node.children.size()][];
        for (int i = 0; i < node.children.size(); i++) {
            data[i] = digestFromNode(node.children.get(i));
        }
        return data;
    }

    /**
     * 计算节点摘要
     */
    private static byte[] digestFromNode(EklshPVLVO.Node node) {
        return PVSHashUtils.hashConcat(
                PVSHashUtils.longToBytes(node.r),
                PVSHashUtils.intToBytes(node.len),
                node.phLast
        );
    }
}

