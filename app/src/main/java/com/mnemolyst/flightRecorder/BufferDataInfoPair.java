package com.mnemolyst.flightRecorder;

import android.media.MediaCodec;

/**
 * Created by joshua on 4/18/17.
 */

class BufferDataInfoPair {

    private long dataId;
    private MediaCodec.BufferInfo bufferInfo;
    private boolean isThumbnail;

    BufferDataInfoPair(long dataId, MediaCodec.BufferInfo videoBufferInfo) {
        this.dataId = dataId;
        this.bufferInfo = videoBufferInfo;
        this.isThumbnail = false;
    }

    public long getDataId() {
        return dataId;
    }

    public void setDataId(long dataId) {
        this.dataId = dataId;
    }

    public MediaCodec.BufferInfo getBufferInfo() {
        return bufferInfo;
    }

    public void setBufferInfo(MediaCodec.BufferInfo bufferInfo) {
        this.bufferInfo = bufferInfo;
    }

    public boolean getIsThumbnail() {
        return isThumbnail;
    }

    public void setIsThumbnail(boolean isThumbnail) {
        this.isThumbnail = isThumbnail;
    }
}