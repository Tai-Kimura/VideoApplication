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
import static com.tanosys.videolibrary.MediaDecoder.STATE_CHANGE_RATE;
import static com.tanosys.videolibrary.MediaDecoder.STATE_END_SEEK;
import static com.tanosys.videolibrary.MediaDecoder.STATE_PLAYING;
import static com.tanosys.videolibrary.MediaDecoder.STATE_SEEKING;
import static com.tanosys.videolibrary.MediaDecoder.STATE_STOPPED;
import static com.tanosys.videolibrary.MediaDecoder.STATE_WAITING_FOR_LOOP;

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
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mVideoDecoder.prepare();
                        mAudioDecoder.prepare();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
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
            mVideoDecoder.requestStop();
            mAudioDecoder.requestStop();
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
        return mVideoDecoder.getState() == STATE_PLAYING || mAudioDecoder.getState() == STATE_PLAYING;
    }

    public boolean isSeeking() {
        return mVideoDecoder.getState() == STATE_SEEKING && mAudioDecoder.getState() == STATE_SEEKING;
    }

    /**
     * Decodes the video stream, sending frames to the surface.
     * <p>
     * Does not return until video playback is complete, or we get a "stop" signal from
     * frameCallback.
     */
    public void play() throws IOException {
        synchronized (mSync) {
            mVideoDecoder.startPlaying();
            mAudioDecoder.startPlaying();
            mProgressHandler = new Handler(Looper.getMainLooper());
            mProgressHandler.post(mProgressRunnable);
            mSync.notifyAll();
        }
    }

    public void onStopped() {
        synchronized (mSync) {
            if (mProgressHandler != null)
                mProgressHandler.removeCallbacks(mProgressRunnable);
            mProgressHandler = null;
            Log.d(TAG, "on stopped");
            if (mVideoDecoder.getState() == STATE_STOPPED && mAudioDecoder.getState() == STATE_STOPPED) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                mVideoDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                mAudioDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
            } else if (mVideoDecoder.getState() == STATE_WAITING_FOR_LOOP && mAudioDecoder.getState() == STATE_WAITING_FOR_LOOP) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                Log.d(TAG, "looping");
                try {
                    mVideoDecoder.startPlaying();
                    mAudioDecoder.startPlaying();
                    mProgressHandler = new Handler(Looper.getMainLooper());
                    mProgressHandler.post(mProgressRunnable);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (mVideoDecoder.getState() == STATE_CHANGE_RATE && mAudioDecoder.getState() == STATE_CHANGE_RATE) {
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
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (mVideoDecoder.getState() == STATE_SEEKING) {
                try {
                    mVideoDecoder.startSeeking();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (mVideoDecoder.getState() == STATE_END_SEEK) {
                if (mRequestedPlayRate != 0) {
                    mPlayRate = mRequestedPlayRate;
                    mRequestedPlayRate = 0;
                }
                mVideoDecoder.setState(STATE_STOPPED);
                mAudioDecoder.setState(STATE_STOPPED);
                mVideoDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
                mAudioDecoder.getExtractor().seekTo(mVideoDecoder.getExtractor().getSampleTime(), SEEK_TO_CLOSEST_SYNC);
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
            }
            mSync.notifyAll();
        }
    }

    public void startSeek() {
        synchronized (mSync) {
            mPlayWhenDoneSeek = isPlaying();
            if (isPlaying()) {
                mAudioDecoder.requestSeek();
                mVideoDecoder.requestSeek();
            } else if (mVideoDecoder.getState() != STATE_SEEKING && mAudioDecoder.getState() != STATE_SEEKING) {
                try {
                    mVideoDecoder.startSeeking();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mVideoDecoder.setState(STATE_SEEKING);
                mAudioDecoder.setState(STATE_SEEKING);
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
            if (isPlaying()) {
                long time;
                long videoPresentTime = mVideoDecoder.getExtractor().getSampleTime()/1000;
                long audioPresentTime = mAudioDecoder.getExtractor().getSampleTime()/1000;
                if (Math.abs(videoPresentTime - audioPresentTime) > mVideoDuration/2) {
                    time = Math.max(videoPresentTime, audioPresentTime);
                } else {
                    time = Math.min(videoPresentTime, audioPresentTime);
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
}
