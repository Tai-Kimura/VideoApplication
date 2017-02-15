# VideoApplication
Test Application for videolibrary

# Over View
mp4 video player for Android which is capable of multi play rate and smooth seek.

# Description
videolibrary provides a mp4 video player which is capable of playing with multi play rate and smooth seeking.
you can set video rate to slowmotion and fast foward playback.

# Requirement
* Android API >= 18

## Gradle
Add repository to your project's gradle file.
    
    repositories {
      mavenCentral()
      maven { url 'http://raw.github.com/Tai-Kimura/VideoApplication/master/repository/' }
    }
    
And add dependency

`compile 'com.tanosys:videolibrary:1.0.xx'`

#Usage

Initialize MoviePlayer with a video file and surface in SurfaceView's surfaceCreated

    File file = new File(path_to_video);
    MoviePlayer moviePlayer = new MoviePlayer(file, surfaceHolder.getSurface());
    
Call play() to start playing and pause() to stop playing.
You should call stop() in Activity's onPause() method and call release when you finish using the MoviePlayer instance.

You can set play rate with setRate(). Slowest play rate and fastest play rate are depends on the device's performance.

For Seeking, you should call startSeek() when you want to start seeking and call endSeek() when you want to end seeking.
Pass progress(0-1.0) to seekTo() method to seek specified position of the video.

You can set loop mode with setLooping() method. if set looping true, player will restart when it reaches end. default value is true.

You can set MoviePlayerListener with setListener() mehod. Then you can implement functions on each event.

    void onStopped(MoviePlayer moviePlayer);

    void onReachedEnd(MoviePlayer moviePlayer);

    void onChangeRate(MoviePlayer moviePlayer);

    void onStartSeeking(MoviePlayer moviePlayer);

    void onEndSeeking(MoviePlayer moviePlayer);

methods pause(), startSeek(), endSeek() and setRate() will not immediately perform each action. They'll tell player thread to perform each action.
To know when the action is done, You should implement MoviePlayerListener.

# License

Copyright (c) 2017 taichiro kimura

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

All files in the folder are under this Apache License, Version 2.0.
    
