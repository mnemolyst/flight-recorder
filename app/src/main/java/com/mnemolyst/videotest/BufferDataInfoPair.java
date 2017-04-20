package com.mnemolyst.videotest;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

/**
 * Created by joshua on 4/18/17.
 */

class BufferDataInfoPair {

    private long dataId;
    private MediaCodec.BufferInfo info;

    BufferDataInfoPair(long dataId, MediaCodec.BufferInfo info) {
        this.dataId = dataId;
        this.info = info;
    }

    public long getDataId() {
        return dataId;
    }

    public void setDataId(long dataId) {
        this.dataId = dataId;
    }

    public MediaCodec.BufferInfo getInfo() {
        return info;
    }

    public void setInfo(MediaCodec.BufferInfo info) {
        this.info = info;
    }
}
