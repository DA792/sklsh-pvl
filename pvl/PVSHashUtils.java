package com.mrtree.vann.pvl;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PVS 專用哈希工具（SHA-256 + 基本序列化）。
 * 與 Merkle/VPTree 使用的 HashUtils 解耦，避免相互影響。
 */
public final class PVSHashUtils {

	private PVSHashUtils() {}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static byte[] hashConcat(byte[]... parts) {
		MessageDigest md = newDigest();
		for (byte[] p : parts) {
			if (p != null) {
				md.update(p);
			}
		}
		return md.digest();
	}

	public static byte[] longToBytes(long v) {
		ByteBuffer buf = ByteBuffer.allocate(8);
		buf.putLong(v);
		return buf.array();
	}

	public static byte[] intToBytes(int v) {
		ByteBuffer buf = ByteBuffer.allocate(4);
		buf.putInt(v);
		return buf.array();
	}
}


