package com.mrtree.vann.model;

public class NodeADS {
    public final long r;
    public final int len;
    public final byte[] ph;

    public NodeADS(long r, int len, byte[] ph) {
        this.r = r;
        this.len = len;
        this.ph = ph;
    }

    public byte[] lastPrefix() {
        if (ph == null || ph.length == 0) return new byte[32];
        byte[] last = new byte[32];
        System.arraycopy(ph, ph.length - 32, last, 0, 32);
        return last;
    }
}

