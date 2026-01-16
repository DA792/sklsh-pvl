package com.mrtree.vann.pvl;

import java.nio.charset.StandardCharsets;

/**
 * 實驗用 PVS 密鑰（sk0, sk1）。
 * 真實部署時應由 DO/Client 通過安全通道共享，SP 不持有。
 */
public final class PVSKeys {

	private PVSKeys() {}

	// 簡單使用常量字串作為密鑰種子（長度適中，便於實驗復現）。
	public static final byte[] SK0 = "VANN-PVS-SK0-TEST-KEY-0001".getBytes(StandardCharsets.UTF_8);
	public static final byte[] SK1 = "VANN-PVS-SK1-TEST-KEY-0002".getBytes(StandardCharsets.UTF_8);
}


