package com.tanosys.videolibrary;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by like-a-rolling_stone on 2017/01/31.
 */
public class AudioDecoder extends MediaDecoder {
    protected static final int MSG_FRAME_AVAILABLE = 2;
    private  int mSampleRate = 0;
    private static final String TAG = "AudioDecoder";
    private AudioTrack mAudioTrack;

    public void setPlayRate(double playRate) {
        mAudioTrack.setPlaybackRate((int) ((double)mSampleRate * playRate));
    }

    public AudioDecoder(MoviePlayer player, File sourceFile) throws IOException {
        super(player,sourceFile);
        this.TRACK_TYPE = "audio";
    }

    @Override
    protected void prepare() throws IOException {
        if (mState < STATE_PREPARED) {
            MediaFormat format;
            if (mState == STATE_UNINITIALIZED) {
                mTrackIndex = selectTrack();
                if (mTrackIndex < 0) {
                    throw new RuntimeException("No audio track found in " + mTrackIndex);
                }
                mExtractor.selectTrack(mTrackIndex);
                format = mExtractor.getTrackFormat(mTrackIndex);
                mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                mAudioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        mSampleRate,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(
                                mSampleRate,
                                AudioFormat.CHANNEL_OUT_STEREO,
                                AudioFormat.ENCODING_PCM_16BIT
                        ),
                        AudioTrack.MODE_STREAM
                );
                mState = STATE_INITIALIZED;
            } else {
                format = mExtractor.getTrackFormat(mTrackIndex);
            }

            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.d(TAG, mime);
            mMediaCodec = MediaCodec.createDecoderByType(mime);
//            mMediaCodec.setCallback(mCallback);
            mMediaCodec.configure(format, null, null, 0);
        }
        super.prepare();
    }

    @Override
    protected void output(int outputBufIndex, MediaCodec.BufferInfo bufferInfo) {
        if (mMediaCodec == null) return;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "output EOS");
        }
        ByteBuffer buf;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            buf = mMediaCodec.getOutputBuffer(outputBufIndex);
        } else {
            buf = mMediaCodec.getOutputBuffers()[outputBufIndex];
        }
        final byte[] chunk = new byte[bufferInfo.size];
        buf.get(chunk); // Read the buffer all at once
        buf.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN
        if (chunk.length > 0) {
            mAudioTrack.write(chunk, 0, chunk.length);
            mStartTime = adjustPresentationTime(mStartTime, (long) ((double)bufferInfo.presentationTimeUs/mWeakPlayer.get().getPlayRate()));
        }
        mMediaCodec.releaseOutputBuffer(outputBufIndex, false);

    }

    @Override
    protected void startPlaying() throws IOException {
        setPlayRate(mWeakPlayer.get().getPlayRate());
        mAudioTrack.play();
        super.startPlaying();
    }

    protected void stopPlaying() {
        if (mAudioTrack != null)
            mAudioTrack.stop();
    }

    @Override
    protected int getFrameAvailable() {
        return MSG_FRAME_AVAILABLE;
    }

    @Override
    public void changePlayRate() {
        super.changePlayRate();
    }

}
