package com.mnemolyst.videotest;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

/**
 * Created by joshua on 4/18/17.
 */

class BufferDataInfoPair {

    private ByteBuffer data;
    private MediaCodec.BufferInfo info;

    public BufferDataInfoPair(ByteBuffer data, MediaCodec.BufferInfo info) {
        this.data = data;
        this.info = info;
    }

    public ByteBuffer getData() {
        return data;
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    public MediaCodec.BufferInfo getInfo() {
        return info;
    }

    public void setInfo(MediaCodec.BufferInfo info) {
        this.info = info;
    }
}
