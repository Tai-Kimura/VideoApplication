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

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import static android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC;

/**
 * Created by like-a-rolling_stone on 2017/01/31.
 */

public abstract class MediaDecoder {
    private static final int TIMEOUT_USEC = 10000;
    public static final int STATE_NO_TRACK_FOUND = -2;
    public static final int STATE_UNINITIALIZED = -1;
    public static final int STATE_INITIALIZED = 0;
    public static final int STATE_PREPARED = 1;
    public static final int STATE_STOPPED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_REQUEST_STOP = 4;
    public static final int STATE_REQUEST_SEEK = 5;
    public static final int STATE_SEEKING = 6;
    public static final int STATE_WAITING_FOR_LOOP = 7;
    public static final int STATE_REQUEST_CHANGE_RATE = 8;
    public static final int STATE_CHANGE_RATE = 9;
    public static final int STATE_END_SEEK = 10;
    public static final int STATE_ERROR = 99;


    public MediaExtractor getExtractor() {
        return mExtractor;
    }

    protected MediaExtractor mExtractor = new MediaExtractor();

    public int getState() {
        return mState;
    }

    public void setState(int state) {
        if (this.mState == STATE_NO_TRACK_FOUND)
            return;
        this.mState = state;
        if (state == STATE_PLAYING) {
            mLastPresentationTime = mExtractor.getSampleTime();
            mLastSystemTime = (System.nanoTime() / 1000);
            mStartTime = mLastSystemTime - (long) ((double) mLastPresentationTime / mWeakPlayer.get().getPlayRate());
        }
    }

    protected int mState = STATE_UNINITIALIZED;

    private static final boolean DEBUG = true;    // TODO set false on release
    private static final String TAG = "MediaDecoder";

    protected String TRACK_TYPE = "";


    public int getTrackIndex() {
        return mTrackIndex;
    }

    protected int mTrackIndex;

    protected MediaCodec mMediaCodec;                // API >= 16(Android4.1.2)

    protected WeakReference<MoviePlayer> mWeakPlayer;

    public long getRequestedSeekTo() {
        return mRequestedSeekTo;
    }

    public void setRequestedSeekTo(long requestedSeekTo) {
        this.mRequestedSeekTo = requestedSeekTo;
    }

    protected long mRequestedSeekTo = -1;

    protected boolean mInputDone = false;

    protected boolean mOutputDone = false;

    protected MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    protected Object mDecoderSync = new Object();

    protected long mStartTime = 0;

    protected long mLastSystemTime = 0;

    protected long mLastPresentationTime = 0;

    public long getPresentTimeStamp() {
        return mExtractor.getSampleTime();
    }

    public WeakReference<Thread> getDecodingThread() {
        return mDecodingThread;
    }

    private WeakReference<Thread> mDecodingThread;


    public MediaDecoder(MoviePlayer moviePlayer, File sourceFile) throws IOException {
        if (moviePlayer == null) throw new NullPointerException("player is null");
        mWeakPlayer = new WeakReference<>(moviePlayer);
        mExtractor.setDataSource(sourceFile.toString());
    }


    protected int selectTrack() {
        if (mWeakPlayer == null || mWeakPlayer.get() == null)
            return -1;
        // Select the first video track we find, ignore the rest.
        int numTracks = mExtractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith(TRACK_TYPE + "/")) {
                Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                return i;
            }
        }

        return -1;
    }

    protected void prepare() throws IOException {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        Log.d(TAG, TRACK_TYPE + "'s state is " + mState);
        if (mState > STATE_PREPARED) {
            configure();
        }
    }

    void startPlaying() throws IOException, IllegalStateException {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        synchronized (mWeakPlayer.get().getSync()) {
            mInputDone = mOutputDone = false;
            prepare();
            setState(STATE_PLAYING);
            mMediaCodec.start();
            Thread thread = new Thread(mRunnable, getClass().getSimpleName());
            mDecodingThread = new WeakReference<>(thread);
            thread.start();
            if (DEBUG) Log.v(TAG, "startPlaying");
            mWeakPlayer.get().getSync().notify();
        }
    }


    /**
     * Release all releated objects
     */
    protected void requestStop() {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        synchronized (mWeakPlayer.get().getSync()) {
            setState(STATE_REQUEST_STOP);
            if (DEBUG) Log.v(TAG, "request stop");
            mWeakPlayer.get().getSync().notify();
        }
    }

    protected void requestSeek() {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        synchronized (mWeakPlayer.get().getSync()) {
            if (mState == STATE_SEEKING)
                return;
            if (mDecodingThread != null && mDecodingThread.get() != null && mDecodingThread.get().isAlive()) {
                setState(STATE_REQUEST_SEEK);
                Log.v(TAG, "request seek");
            } else {
                try {
                    startSeeking();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            mWeakPlayer.get().getSync().notify();
        }
    }

    public void startSeeking() throws IOException {
        Log.d(TAG, TRACK_TYPE + "start seeking");
    }

    protected void stop() {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        synchronized (mWeakPlayer.get().getSync()) {
            Log.d(TAG, TRACK_TYPE + " is requested to stop");
            if (mDecodingThread != null && mDecodingThread.get() != null && mDecodingThread.get().isAlive()) {
                Log.d(TAG, TRACK_TYPE + "decoding thread is alive");
                mDecodingThread.get().interrupt();
            } else {
                releaseCodec();
            }
            mWeakPlayer.get().getSync().notify();
        }
    }

    protected void releaseCodec() {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        if (mMediaCodec != null) {
            try {
                mState = STATE_INITIALIZED;
                if (mExtractor != null)
                    mExtractor.seekTo(mExtractor.getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
                Log.d(TAG, TRACK_TYPE + " successfully released MediaCodec");
            } catch (Exception e) {
                Log.e(TAG, "failed releasing MediaCodec", e);
            }
        } else {
            try {
                prepare();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    protected void release() {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        stop();
        synchronized (mWeakPlayer.get().getSync()) {
            if (mExtractor != null) {
                setState(STATE_UNINITIALIZED);
                mExtractor.release();
                mExtractor = null;
            }
            mWeakPlayer.get().getSync().notify();
        }
    }

    protected void signalEndOfInputStream() {
        if (DEBUG) Log.d(TAG, "sending EOS to decoder");
        // signalEndOfInputStream is only avairable for video encoding with surface
        // and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag.
//		mMediaCodec.signalEndOfInputStream();	// API >= 18
    }

    protected final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                for (; mState == STATE_PLAYING && !mInputDone && !mOutputDone; ) {
                    if (Thread.currentThread().isInterrupted())
                        break;
                    handleInput();
                    handleOutput();
                    synchronized (mWeakPlayer.get().getSync()) {
                        mWeakPlayer.get().getSync().notify();
                    }
                }
                Log.d(TAG, TRACK_TYPE + " done io");
                synchronized (mWeakPlayer.get().getSync()) {
                    if (Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "thread interrupted");
                        releaseCodec();
                    } else {
                        mInputDone = mOutputDone = true;
                        Log.d(TAG, TRACK_TYPE + " state is " + mState);
                        if (mState == STATE_PLAYING) {
                            setState(STATE_WAITING_FOR_LOOP);
                            mExtractor.seekTo(0, SEEK_TO_CLOSEST_SYNC);
                        } else if (mState == STATE_REQUEST_CHANGE_RATE) {
                            setState(STATE_CHANGE_RATE);
                        } else if (mState == STATE_REQUEST_STOP) {
                            setState(STATE_STOPPED);
                        } else if (mState == STATE_REQUEST_SEEK) {
                            Log.d(TAG, TRACK_TYPE + "'s new state is " + mState);
                        }
                        mMediaCodec.stop();
                        mWeakPlayer.get().onStopped();
                    }
                    mWeakPlayer.get().getSync().notify();
                }
            } catch (IllegalStateException e) {
                synchronized (mWeakPlayer.get().getSync()) {
                    setState(STATE_ERROR);
                    releaseCodec();
                    mWeakPlayer.get().stop();
                }
            }
        }
    };

    protected void handleInput() {
        if ((mState == STATE_PLAYING ||  mState == STATE_SEEKING) && !mInputDone)  {
            final int inputBufIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER)
                return;
            mInputDone = input(inputBufIndex);
            Log.d(TAG, TRACK_TYPE + "input done " + mInputDone);
        }
    }

    protected void handleOutput() {
        Log.d(TAG, TRACK_TYPE + " handle output ");
        if ((mState == STATE_PLAYING|| mState == STATE_SEEKING) && !mOutputDone) {
            final int decoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            Log.d(TAG, TRACK_TYPE + " decoder status: " + decoderStatus);
            if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                final MediaFormat newFormat = mMediaCodec.getOutputFormat();
                if (DEBUG) Log.d(TAG, "video decoder output format changed: " + newFormat);
            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (DEBUG) Log.d(TAG, "video decoder output buffer changed: ");
            } else if (decoderStatus < 0) {
                throw new RuntimeException(
                        "unexpected result from video decoder.dequeueOutputBuffer: " + decoderStatus);
            } else {
                Log.d(TAG, TRACK_TYPE + " Out put");
                output(decoderStatus, mBufferInfo);
            }
            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && mState != STATE_SEEKING) {
                if (DEBUG) Log.d(TAG, "video:output EOS");
                synchronized (mWeakPlayer.get().getSync()) {
                    mOutputDone = true;
                    mWeakPlayer.get().getSync().notifyAll();
                }
            }
        }
    }


    protected boolean input(int inputBufIndex) {
        if (inputBufIndex >= 0) {
            ByteBuffer inputBuf;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                inputBuf = mMediaCodec.getInputBuffer(inputBufIndex);
            } else {
                inputBuf = mMediaCodec.getInputBuffers()[inputBufIndex];
            }
            if (mExtractor.getSampleTrackIndex() == mTrackIndex) {
                int chunkSize = mExtractor.readSampleData(inputBuf, 0);
                if (chunkSize <= 0) {
                    if (mState != STATE_SEEKING) {
                        mMediaCodec.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        Log.d(TAG, "sent input EOS");
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    long presentationTimeUs = mExtractor.getSampleTime();
                    Log.d(TAG, TRACK_TYPE + " time:" + presentationTimeUs);
                    mMediaCodec.queueInputBuffer(inputBufIndex, 0, chunkSize,
                            presentationTimeUs, 0 /*flags*/);
                    boolean b = !mExtractor.advance();
                    Log.d(TAG, TRACK_TYPE + " extractor advanced " + b);
                    return mState != STATE_SEEKING ? b : false;
//                    Log.d(TAG, "submitted frame " + presentationTimeUs);
                }
            }
        }
        return true;
    }

//    protected MediaCodec.Callback mCallback = new MediaCodec.Callback() {
//        @Override
//        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {
//            if (mState == STATE_PLAYING) {
//                if (mRequestedSeekTo > 0) {
//                    mExtractor.seekTo(mRequestedSeekTo, SEEK_TO_CLOSEST_SYNC);
//                    mRequestedSeekTo = -1;
//                }
//                if (input(i) || !mExtractor.advance()) {
//                    mExtractor.seekTo(0, SEEK_TO_CLOSEST_SYNC);
//                    setState(STATE_WAITING_FOR_LOOP);
//                    mWeakPlayer.get().onStopped();
//                }
//            } else if (mState == STATE_REQUEST_STOP) {
//                mWeakPlayer.get().onStopped();
//            }
//        }
//
//        @Override
//        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
//            output(i, bufferInfo);
//        }
//
//        @Override
//        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
//
//        }
//
//        @Override
//        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
//
//        }
//    };

    protected int getFrameAvailable() {
        return 0;
    }

    protected void output(int outputBufIndex, MediaCodec.BufferInfo bufferInfo) {

    }

    protected void configure() {
        MediaFormat format = mExtractor.getTrackFormat(mTrackIndex);
        mMediaCodec.configure(format, null, null, 0);
    }

    protected long adjustPresentationTime(final long startTime, final long presentationTimeUs) {
        if (startTime > 0) {
            for (long t = presentationTimeUs - (System.nanoTime() / 1000 - startTime);
                 t > 0; t = presentationTimeUs - (System.nanoTime() / 1000 - startTime)) {
                synchronized (mDecoderSync) {
                    try {
                        mDecoderSync.wait(t / 1000, (int) ((t % 1000) * 1000));
                    } catch (final InterruptedException e) {
                        Log.d(TAG, "wait interrupted");
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if ((mState == STATE_REQUEST_STOP) || (mState == STATE_SEEKING) || (mState == STATE_CHANGE_RATE))
                        break;
                }
            }
            return startTime;
        } else {
            return System.nanoTime() / 1000;
        }
    }

    public void changePlayRate() {
        if (mState == STATE_NO_TRACK_FOUND)
            return;
        mState = STATE_REQUEST_CHANGE_RATE;
    }

    public boolean isRequestingStateChange() {
        return mState == STATE_REQUEST_STOP || mState == STATE_REQUEST_SEEK || mState == STATE_REQUEST_CHANGE_RATE || mState == STATE_WAITING_FOR_LOOP;
    }

    public boolean isPlaying() {
        return mState == STATE_PLAYING || mState == STATE_NO_TRACK_FOUND;
    }

    public boolean isStopped() {
        return mState == STATE_STOPPED || mState == STATE_NO_TRACK_FOUND;
    }

    public boolean isWaitingForLoop() {
        return mState == STATE_WAITING_FOR_LOOP || mState == STATE_NO_TRACK_FOUND;
    }

    public boolean isChangeRate() {
        return mState == STATE_CHANGE_RATE|| mState == STATE_NO_TRACK_FOUND;
    }

    public boolean isRequestSeek() {
        return mState == STATE_REQUEST_SEEK || mState == STATE_NO_TRACK_FOUND;
    }

    public boolean isSeeking() {
        return mState == STATE_SEEKING || mState == STATE_NO_TRACK_FOUND;
    }

    public boolean isEndSeek() {
        return mState == STATE_END_SEEK || mState == STATE_NO_TRACK_FOUND;
    }
}
