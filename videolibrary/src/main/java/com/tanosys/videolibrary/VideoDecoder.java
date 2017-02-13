/*
 * Copyright (c) 2017. taichiro kimura
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
 *
 */

package com.tanosys.videolibrary;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by like-a-rolling_stone on 2017/01/31.
 */
public class VideoDecoder extends MediaDecoder {
    protected static final int MSG_FRAME_AVAILABLE = 1;
    protected static final int SEEK_DIRECTION_FORWARD = 1;
    protected static final int SEEK_DIRECTION_BACKWARD = 2;
    protected static final long CLOSE_ENOUGH_TIME = 100000;
    private static final String TAG = "VideoDecoder";

    private int mVideoWidth;
    private int mVideoHeight;

    public long getSeekTargetTime() {
        return mSeekTargetTime;
    }

    private int mSeekDirection = 0;
    private long mLastSyncFrameTime = -1;
    private long mMaximumDifference = 0;
    private boolean mShouldRetreatToIFrame = false;

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public void setVideoWidth(int mVideoWidth) {
        this.mVideoWidth = mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public void setVideoHeight(int mVideoHeight) {
        this.mVideoHeight = mVideoHeight;
    }

    public WeakReference<Surface> getOutputSurface() {
        return mOutputSurface;
    }

    public void setOutputSurface(Surface mOutputSurface) {
        this.mOutputSurface = new WeakReference<>(mOutputSurface);
    }

    private WeakReference<Surface> mOutputSurface;

    public VideoDecoder(MoviePlayer player, File sourceFile) throws IOException {
        super(player, sourceFile);
        this.TRACK_TYPE = "video";
    }

    @Override
    protected void prepare() throws IOException {
        if (mState < STATE_PREPARED) {
            Log.d(TAG, "Video size is " + mVideoWidth + "x" + mVideoHeight);
            if (mState == STATE_UNINITIALIZED) {
                mTrackIndex = selectTrack();
                if (mTrackIndex < 0) {
                    throw new RuntimeException("No video track found in " + mTrackIndex);
                }
                mExtractor.selectTrack(mTrackIndex);

                mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long now = mExtractor.getSampleTime();
                long next = now + 1;
                while (mMaximumDifference == 0) {
                    mExtractor.seekTo(next, MediaExtractor.SEEK_TO_NEXT_SYNC);
                    mMaximumDifference = mExtractor.getSampleTime() - now;
                    Log.d(TAG, "iframe interval is: " + mMaximumDifference);
                    next += 100;
                    if (next > 10000) {
                        mMaximumDifference = 1000000;
                        break;
                    }
                }
                mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            mState = STATE_INITIALIZED;
            MediaFormat format = mExtractor.getTrackFormat(mTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            mMediaCodec = MediaCodec.createDecoderByType(mime);
//            mMediaCodec.setCallback(mCallback);
            int rotation;
            try {
                rotation = format.getInteger(MediaFormat.KEY_ROTATION);
            } catch (NullPointerException e) {
                rotation = 0;
            }

            mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if ((rotation % 180) == 0) {
                mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            } else {
                mVideoWidth = format.getInteger(MediaFormat.KEY_HEIGHT);
                mVideoHeight = format.getInteger(MediaFormat.KEY_WIDTH);
            }
            mMediaCodec.configure(format, mOutputSurface.get(), null, 0);
            setState(STATE_PREPARED);
        }
        super.prepare();
    }

    @Override
    protected void output(int outputBufIndex, MediaCodec.BufferInfo bufferInfo) {
        if (mMediaCodec == null) return;
//        Log.d(TAG, "Presentation: " + mBufferInfo.presentationTimeUs);
//        Log.d(TAG, "Now: " + System.nanoTime());
        if (mState != STATE_SEEKING)
            mStartTime = adjustPresentationTime(mStartTime, (long) ((double) bufferInfo.presentationTimeUs / mWeakPlayer.get().getPlayRate()));
        if (mState != STATE_SEEKING) {
            mMediaCodec.releaseOutputBuffer(outputBufIndex, true);
        } else {
            boolean isSyncFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            long presentationTimeDiff = mSeekTargetTime - bufferInfo.presentationTimeUs;
            Log.d(TAG, "time diff: " + presentationTimeDiff);
            boolean isCloseEnoughToTargetTime = (Math.abs(presentationTimeDiff) < CLOSE_ENOUGH_TIME);
            if (isSyncFrame) {
                synchronized (mDecoderSync) {
                    mLastSyncFrameTime = bufferInfo.presentationTimeUs;
                    mDecoderSync.notify();
                }
            }
            if (isCloseEnoughToTargetTime) {
                mMediaCodec.releaseOutputBuffer(outputBufIndex, true);
                if (mSeekDirection == SEEK_DIRECTION_BACKWARD)
                    Log.d(TAG, "backward render at: " + bufferInfo.presentationTimeUs + " target at: " + mSeekTargetTime);
                synchronized (mDecoderSync) {
                    if (isSyncFrame) {
                        mLastSyncFrameTime = bufferInfo.presentationTimeUs;
                    }
                    if (isCloseEnoughToTargetTime) {
                        Log.d(TAG, "close enough to direction " + mSeekDirection);
                        mIsSeeking = false;
                    }
                    mDecoderSync.notify();
                }
            } else {
                mMediaCodec.releaseOutputBuffer(outputBufIndex, false);
            }
        }
    }

    @Override
    protected int getFrameAvailable() {
        return MSG_FRAME_AVAILABLE;
    }

    protected void configure() {
        MediaFormat format = mExtractor.getTrackFormat(mTrackIndex);
        mMediaCodec.configure(format, mOutputSurface.get(), null, 0);
    }

    @Override
    public void startSeeking() throws IOException {
        super.startSeeking();
        Log.d(TAG, "start seeking state is: " + mState);
        if (mState == STATE_SEEKING)
            return;
        prepare();
        setState(STATE_SEEKING);
        mInputDone = mOutputDone = false;
        mSeekDirection = SEEK_DIRECTION_FORWARD;
        mMediaCodec.start();
        mSeekTargetTime = mExtractor.getSampleTime();
        mExtractor.seekTo(mSeekTargetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        mIsSeeking = true;
        new Thread(mSeekRunnable, getClass().getSimpleName()).start();
    }


    public void seekTo(long presentationTime) {
        synchronized (mDecoderSync) {
            if (mIsSeeking)
                return;
            Log.d(TAG, "seek diff:" + (presentationTime - mSeekTargetTime));
            mInputDone = mOutputDone = false;
            mSeekDirection = presentationTime >= mSeekTargetTime ? SEEK_DIRECTION_FORWARD : SEEK_DIRECTION_BACKWARD;
            if ((mSeekDirection == SEEK_DIRECTION_BACKWARD) || mSeekDirection == SEEK_DIRECTION_FORWARD && Math.abs(presentationTime - mLastSyncFrameTime) > mMaximumDifference) {
                mExtractor.seekTo(presentationTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                mLastSyncFrameTime = mExtractor.getSampleTime();
            }
            mSeekTargetTime = presentationTime;
            mIsSeeking = true;
            mDecoderSync.notifyAll();
        }
    }

    public void endSeeking() {
        synchronized (mDecoderSync) {
            mState = STATE_REQUEST_STOP;
        }
    }

    private Runnable mSeekRunnable = new Runnable() {
        @Override
        public void run() {
            while (mState == STATE_SEEKING) {
                if (!mIsSeeking) {
                    synchronized (mDecoderSync) {
                        mDecoderSync.notify();
                    }
                    continue;
                }
                handleInput();
                handleOutput();
            }
            mMediaCodec.stop();
            setState(STATE_END_SEEK);
            synchronized (mWeakPlayer.get().getSync()) {
                mWeakPlayer.get().onStopped();
                mWeakPlayer.get().getSync().notify();
            }
        }
    };
}
