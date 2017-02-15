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

import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import static android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC;
import static com.tanosys.videolibrary.MediaDecoder.STATE_NO_TRACK_FOUND;
import static com.tanosys.videolibrary.MediaDecoder.STATE_STOPPED;

/**
 * Created by like-a-rolling_stone on 2017/01/26.
 */
public class MoviePlayer {
    private static final String TAG = "MoviePlayer";


    // May be set/read by different threads.
    private volatile boolean mIsStopRequested;

    private VideoDecoder mVideoDecoder;
    private AudioDecoder mAudioDecoder;

    public ProgressListener getProgressListener() {
        return mProgressListener.get();
    }

    public void setProgressListener(ProgressListener progressListener) {
        this.mProgressListener = new WeakReference<>(progressListener);
    }

    private WeakReference<ProgressListener> mProgressListener;

    protected long mVideoDuration = 0;

    public Object getSync() {
        return mSync;
    }

    private Object mSync = new Object();

    public double getPlayRate() {
        return mPlayRate;
    }

    protected double mPlayRate = 1.0;

    private double mRequestedPlayRate = 0;

    protected boolean mPlayWhenDoneSeek = false;

    protected float mProgress = 0;

    public boolean isLooping() {
        return mIsLooping;
    }

    public void setLooping(boolean loop) {
        this.mIsLooping = loop;
    }

    private boolean mIsLooping = true;

    public void setListener(MoviePlayerListener listener) {
        this.mListener = new WeakReference<>(listener);
    }

    private WeakReference<MoviePlayerListener> mListener;


    public MoviePlayer(File sourceFile, Surface outputSurface)
            throws IOException {
        // Pop the file open and pull out the video characteristics.
        // TODO: consider leaving the extractor open.  Should be able to just seek back to
        //       the start after each iteration of play.  Need to rearrange the API a bit --
        //       currently play() is taking an all-in-one open+work+release approach.
        try {
            Log.d(TAG, sourceFile.toString());
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(sourceFile.toString());
            mVideoDuration = Long.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            Log.d(TAG, "Duration: " + mVideoDuration);
            retriever.release();

            mVideoDecoder = new VideoDecoder(this, sourceFile);
            mVideoDecoder.setOutputSurface(outputSurface);
            mAudioDecoder = new AudioDecoder(this, sourceFile);
            mVideoDecoder.prepare();
            mAudioDecoder.prepare();
        } catch (Exception ex) {
            release();
            throw new IOException(ex.getMessage());
        }
    }

    /**
     * Asks the player to stop.  Returns without waiting for playback to halt.
     * <p>
     * Called from arbitrary thread.
     */
    public void pause() {
        synchronized (mSync) {
            if (isPlaying()) {
                mVideoDecoder.requestStop();
                mAudioDecoder.requestStop();
            }
            mSync.notifyAll();
        }
    }

    public void stop() {
        synchronized (mSync) {
            if (mProgressHandler != null)
                mProgressHandler.removeCallbacks(mProgressRunnable);
            mProgressHandler = null;
            mVideoDecoder.stop();
            mAudioDecoder.stop();
            mSync.notifyAll();
        }
    }

    public boolean isPlaying() {
        return mVideoDecoder.isPlaying() && mAudioDecoder.isPlaying();
    }

    public boolean isPaused() {
        return (mVideoDecoder.getState() <= STATE_STOPPED
        ) && (mAudioDecoder.getState() <= STATE_STOPPED
        );
    }

    public boolean isSeeking() {
        return mVideoDecoder.isSeeking() && mAudioDecoder.isSeeking();
    }

    public boolean isRequestingStateChange() {
        return mVideoDecoder.isRequestingStateChange() || mAudioDecoder.isRequestingStateChange();
    }

    /**
     * Decodes the video stream, sending frames to the surface.
     * <p>
     * Does not return until video playback is complete, or we get a "stop" signal from
     * frameCallback.
     */
    public void play() {
        Log.d(TAG, "play");
        synchronized (mSync) {
            if (isPaused()) {
                try {
                    mVideoDecoder.startPlaying();
                    mAudioDecoder.startPlaying();
                    mProgressHandler = new Handler(Looper.getMainLooper());
                    mProgressHandler.post(mProgressRunnable);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "failed to play: " + e.getMessage());
                    Log.e(TAG, "video failed to play: " + mVideoDecoder.getState());
                    Log.e(TAG, "audio failed to play: " + mAudioDecoder.getState());
                    mVideoDecoder.stop();
                    mAudioDecoder.stop();
                } catch (IOException e) {
                    Log.e(TAG, "failed to play: " + e.getMessage());
                    mVideoDecoder.stop();
                    mAudioDecoder.stop();
                }
            }
            mSync.notifyAll();
        }
    }

    public void onStopped() {
        synchronized (mSync) {
            if (mProgressHandler != null)
                mProgressHandler.removeCallbacks(mProgressRunnable);
            mProgressHandler = null;
            Log.d(TAG, "on stopped");
            if (mVideoDecoder.isStopped() && mAudioDecoder.isStopped()) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                mVideoDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                mAudioDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(mOnStoppedRunnable);
            } else if (mVideoDecoder.isWaitingForLoop() && mAudioDecoder.isWaitingForLoop()) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                Log.d(TAG, "looping");
                if (!mIsLooping) {
                    mVideoDecoder.setState(STATE_STOPPED);
                    mAudioDecoder.setState(STATE_STOPPED);
                }
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(mOnReachedEndRunnable);
                if (mIsLooping) {
                    try {
                        mVideoDecoder.startPlaying();
                        mAudioDecoder.startPlaying();
                        mProgressHandler = new Handler(Looper.getMainLooper());
                        mProgressHandler.post(mProgressRunnable);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } else if (mVideoDecoder.isChangeRate() && mAudioDecoder.isChangeRate()) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                mVideoDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                mAudioDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                try {
                    mVideoDecoder.startPlaying();
                    mAudioDecoder.startPlaying();
                    mProgressHandler = new Handler(Looper.getMainLooper());
                    mProgressHandler.post(mProgressRunnable);
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(mOnChangeRateRunnable);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (mVideoDecoder.isRequestSeek() && mAudioDecoder.isRequestSeek()) {
                try {
                    mVideoDecoder.startSeeking();
                    mAudioDecoder.startSeeking();
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(mOnStartSeekingRunnable);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (mVideoDecoder.isEndSeek()) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                mVideoDecoder.setState(STATE_STOPPED);
                mAudioDecoder.setState(STATE_STOPPED);
                long sampleTime = mVideoDecoder.getSeekTargetTime();
                mVideoDecoder.getExtractor().seekTo(sampleTime, SEEK_TO_CLOSEST_SYNC);
                mAudioDecoder.getExtractor().seekTo(sampleTime, SEEK_TO_CLOSEST_SYNC);
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(mOnEndSeekingRunnable);
                if (mPlayWhenDoneSeek) {
                    try {
                        mVideoDecoder.startPlaying();
                        mAudioDecoder.startPlaying();
                        mProgressHandler = new Handler(Looper.getMainLooper());
                        mProgressHandler.post(mProgressRunnable);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            mSync.notifyAll();
        }
    }

    public void release() {
        synchronized (mSync) {
            if (mVideoDecoder != null) {
                mVideoDecoder.release();
            }
            // release everything we grabbed
            if (mAudioDecoder != null) {
                mAudioDecoder.release();
            }
        }
    }

    public void setRate(double rate) {
        synchronized (mSync) {
            if (isPlaying()) {
                mRequestedPlayRate = rate;
                mVideoDecoder.changePlayRate();
                mAudioDecoder.changePlayRate();
            } else {
                mPlayRate = rate;
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(mOnChangeRateRunnable);
            }
            mSync.notifyAll();
        }
    }

    public void startSeek() {
        synchronized (mSync) {
            mPlayWhenDoneSeek = isPlaying();
            if (!isPaused() && !isSeeking()) {
                Log.d(TAG, "start seeking with: request Seek");
                mAudioDecoder.requestSeek();
                mVideoDecoder.requestSeek();
            } else if (!isSeeking()) {
                Log.d(TAG, "start seeking with: seeking");
                try {
                    mVideoDecoder.startSeeking();
                    mAudioDecoder.startSeeking();
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(mOnStartSeekingRunnable);
                } catch (IOException e) {
                    Log.d(TAG, "error when start seek");
                }

            }
            mSync.notifyAll();
        }
    }

    public void seekTo(float progress) {
        synchronized (mSync) {
            Log.d(TAG, "video seeking state:" + mVideoDecoder.getState());
            Log.d(TAG, "audio seeking state:" + mAudioDecoder.getState());
            if (!isSeeking())
                return;
            Log.d(TAG, "Progress: " + progress);
            mProgress = progress;
            long presentationTime = (long) (((double) mVideoDuration) * progress) * 1000;
            Log.d(TAG, "PresentationTime: " + presentationTime);
            mVideoDecoder.seekTo(presentationTime);
            mSync.notifyAll();
        }
    }

    public void endSeek() {
        synchronized (mSync) {
            if (isSeeking())
                mVideoDecoder.endSeeking();
        }
    }

    public float getProgress() {
        return mProgress;
    }

    public long getPresentTimeStamp() {
        return mVideoDecoder.getPresentTimeStamp();
    }

    Handler mProgressHandler;

    Runnable mProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying() || isRequestingStateChange()) {
                long time;
                long videoPresentTime = mVideoDecoder.getExtractor().getSampleTime() / 1000;
                long audioPresentTime = mAudioDecoder.getExtractor().getSampleTime() / 1000;
                if (mAudioDecoder.getState() == STATE_NO_TRACK_FOUND) {
                    time = videoPresentTime;
                } else {
                    if (Math.abs(videoPresentTime - audioPresentTime) > mVideoDuration / 2) {
                        time = Math.max(videoPresentTime, audioPresentTime);
                    } else {
                        time = Math.min(videoPresentTime, audioPresentTime);
                    }
                }
                mProgress = (float) ((double) (time) / (double) (mVideoDuration));
                if (mProgress < 0)
                    mProgress = 1.0f;
                if (mProgressListener != null && mProgressListener.get() != null)
                    mProgressListener.get().onProgressChange(mProgress);
                mProgressHandler = new Handler(Looper.getMainLooper());
                mProgressHandler.postDelayed(mProgressRunnable, 50);
            } else {
                mProgressHandler = null;
            }
        }
    };

    public interface ProgressListener {
        void onProgressChange(float progress);
    }

    public int getVideoWidth() {
        return mVideoDecoder != null ? mVideoDecoder.getVideoWidth() : 0;
    }

    public int getVideoHeight() {
        return mVideoDecoder != null ? mVideoDecoder.getVideoHeight() : 0;
    }

    public void setOutputSurface(Surface surface) {
        if (mVideoDecoder != null)
            mVideoDecoder.setOutputSurface(surface);
    }

    private Runnable mOnStoppedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListener != null && mListener.get() != null)
                mListener.get().onStopped(MoviePlayer.this);
        }
    };

    private Runnable mOnChangeRateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListener != null && mListener.get() != null)
                mListener.get().onChangeRate(MoviePlayer.this);
        }
    };

    private Runnable mOnStartSeekingRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListener != null && mListener.get() != null)
                mListener.get().onStartSeeking(MoviePlayer.this);
        }
    };

    private Runnable mOnEndSeekingRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListener != null && mListener.get() != null)
                mListener.get().onEndSeeking(MoviePlayer.this);
        }
    };

    private Runnable mOnReachedEndRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListener != null && mListener.get() != null)
                mListener.get().onReachedEnd(MoviePlayer.this);
        }
    };

    public interface MoviePlayerListener {
        void onStopped(MoviePlayer moviePlayer);

        void onReachedEnd(MoviePlayer moviePlayer);

        void onChangeRate(MoviePlayer moviePlayer);

        void onStartSeeking(MoviePlayer moviePlayer);

        void onEndSeeking(MoviePlayer moviePlayer);
    }
}
