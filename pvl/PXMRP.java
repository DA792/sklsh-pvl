package com.mrtree.vann.pvl;

import com.mrtree.vann.model.NodeADS;

import java.util.Arrays;
import java.util.Random;

public class PXMRP {

    public static NodeADS embedADS(byte[][] data, byte[] sk0, byte[] sk1, Random rng) {
        long r = rng.nextLong();
        byte[] ph = buildPrefixHashes(data, sk0);
        return new NodeADS(r, data.length, ph);
    }

    public static byte[] buildPrefixHashes(byte[][] data, byte[] sk0) {
        int n = data.length;
        if (n == 0) return new byte[0];
        int hashLen = 32;
        byte[] ph = new byte[n * hashLen];
        byte[] prevPh = new byte[hashLen];
        for (int i = 0; i < n; i++) {
            byte[] h = PVSHashUtils.hashConcat(sk0, data[i]);
            byte[] current = xor(prevPh, h);
            System.arraycopy(current, 0, ph, i * hashLen, hashLen);
            prevPh = current;
        }
        return ph;
    }

    public static byte[] generateProof(byte[] ph, int index, long r, byte[] sk1) {
        int hashLen = 32;
        byte[] ph_i = new byte[hashLen];
        if (index >= 0) {
            if ((index + 1) * hashLen <= ph.length) {
                System.arraycopy(ph, index * hashLen, ph_i, 0, hashLen);
            } else {
                // Out of bounds? Should not happen if logic is correct.
                // But if it happens, maybe return 0 or handle error.
                // For safety, let's assume 0 implies invalid?
                return new byte[32];
            }
        }
        byte[] mask = generateMask(index, r, sk1);
        return xor(ph_i, mask);
    }

    public static byte[] generateMask(int index, long r, byte[] sk1) {
        return PVSHashUtils.hashConcat(sk1, PVSHashUtils.longToBytes(r), PVSHashUtils.intToBytes(index));
    }

    public static boolean verify(byte[] piStart, byte[] piEnd, byte[][] dataSubset,
                                 int start, int end, long r, byte[] sk0, byte[] sk1) {
        byte[] maskStartPrev = generateMask(start - 1, r, sk1);
        byte[] phStartPrev = xor(piStart, maskStartPrev);

        byte[] hAgg = new byte[32];
        for (byte[] d : dataSubset) {
            byte[] h = PVSHashUtils.hashConcat(sk0, d);
            hAgg = xor(hAgg, h);
        }

        byte[] phEndCalc = xor(phStartPrev, hAgg);

        byte[] maskEnd = generateMask(end, r, sk1);
        byte[] phEndClaim = xor(piEnd, maskEnd);
        return Arrays.equals(phEndCalc, phEndClaim);
    }

    public static byte[] computeDigest(NodeADS ads) {
        return PVSHashUtils.hashConcat(
                PVSHashUtils.longToBytes(ads.r),
                PVSHashUtils.intToBytes(ads.len),
                ads.lastPrefix()
        );
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] res = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            res[i] = (byte) (a[i] ^ b[i]);
        }
        return res;
    }
}

