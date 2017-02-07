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

package com.tanosys.videoapplication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.SeekBar;

import com.tanosys.videolibrary.MoviePlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;

public class VideoPlayActivity extends AppCompatActivity implements SurfaceHolder.Callback, MoviePlayer.ProgressListener {
    private String videoPath;
    MoviePlayer moviePlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_play);
        findViewById(R.id.play_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (moviePlayer.isPlaying()) {
                    moviePlayer.pause();
                } else {
                    try {
                        moviePlayer.play();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        findViewById(R.id.rate_1_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                moviePlayer.setRate(1.0);

            }
        });

        findViewById(R.id.rate_half_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                moviePlayer.setRate(0.6);

            }
        });

        findViewById(R.id.rate_2_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                moviePlayer.setRate(2.0);

            }
        });

        ((SeekBar) findViewById(R.id.seekBar)).setOnSeekBarChangeListener(seekBarChangeListener);
    }

    private SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                if (moviePlayer != null) {
                    moviePlayer.seekTo((float) progress / seekBar.getMax());
                }
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (moviePlayer != null) {
                moviePlayer.startSeek();
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (moviePlayer != null) {
                moviePlayer.endSeek();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        if (videoPath == null)
            moveToGallery();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (moviePlayer != null)
            moviePlayer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (moviePlayer != null)
            moviePlayer.release();
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        if (requestCode == 1000 && resultCode == RESULT_OK) {
            Uri videoUri = data.getData();
            try {
                setVideoPath(videoUri);
                SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface_view);
                surfaceView.getHolder().addCallback(this);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void createFile() {
        Calendar c = Calendar.getInstance();
        String s = c.get(Calendar.YEAR)
                + "_" + (c.get(Calendar.MONTH) + 1)
                + "_" + c.get(Calendar.DAY_OF_MONTH)
                + "_" + c.get(Calendar.HOUR_OF_DAY)
                + "_" + c.get(Calendar.MINUTE)
                + "_" + c.get(Calendar.SECOND)
                + "_" + c.get(Calendar.MILLISECOND)
                + ".mp4";
        File folder = getCacheDir();
        File file;
        videoPath = folder + "/" + s;
    }

    private void setVideoPath(Uri uri) throws IOException {

        createFile();
        InputStream is = getContentResolver().openInputStream(uri);
        OutputStream output = new FileOutputStream(videoPath);
        int DEFAULT_BUFFER_SIZE = 1024 * 4;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int size = -1;
        while (-1 != (size = is.read(buffer))) {
            output.write(buffer, 0, size);
        }
        is.close();
        output.close();
    }

    protected void moveToGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/mp4");
        startActivityForResult(intent, 1000);
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        try {
            File folder = Environment.getExternalStorageDirectory();
            File file = new File(videoPath);
            moviePlayer = new MoviePlayer(file, surfaceHolder.getSurface());
            moviePlayer.setProgressListener(VideoPlayActivity.this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

    }

    @Override
    public void onProgressChange(float progress) {
        ((SeekBar) findViewById(R.id.seekBar)).setProgress((int) (progress * 100.0f));
    }
}
