package com.mrtree.vann.index.pla;

public final class Segment {
    public Model model;
    public long[] segData;

    public Segment() {}

    public Segment(Segment other) {
        this.model = other.model;
        this.segData = other.segData;
    }

    public Segment(Model model, long[] segData) {
        this.model = model;
        this.segData = segData;
    }

    public long getKey() {
        return segData[segData.length - 1];
    }

    public int size() {
        return segData.length;
    }

    public int findLeftBound(long key, int err) {
        int pos = model.find(key);
        int l = Math.max(0, pos - err);
        int r = Math.min(segData.length - 1, pos + err);
        return PlaUtils.findLeftBound(segData, key, l, r);
    }
}

