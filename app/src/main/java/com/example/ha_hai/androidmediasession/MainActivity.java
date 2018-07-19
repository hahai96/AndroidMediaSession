package com.example.ha_hai.androidmediasession;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.SeekBar;


public class MainActivity extends AppCompatActivity {

    private boolean mIsTracking = false;

    private MediaBrowserCompat mMediaBrowser;
    private MediaControllerCompat mMediaController;
    private ValueAnimator mValueAnimator;

    private ImageButton mbtnPlay, mbtnPre, mbtnNext;
    private SeekBar mProgress;

    private boolean isPlaying;

    private MediaBrowserCompat.ConnectionCallback mMediaBrowserConnectionCallback = new MediaBrowserCompat.ConnectionCallback() {

        @Override
        public void onConnected() {
            super.onConnected();

            try {
                mMediaController = new MediaControllerCompat(MainActivity.this, mMediaBrowser.getSessionToken());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            mMediaController.registerCallback(mMediaControllerCallback);
            // Khi chạy android 5.0 trở lên phải gọi setSupportMediaController (media buttons)
            MediaControllerCompat.setMediaController(MainActivity.this, mMediaController);

            getMediaController().getTransportControls().playFromMediaId(String.valueOf(R.raw.emmoilanguoiyeuanh), null);
        }

        @Override
        public void onConnectionSuspended() {
            // The Service has crashed. Disable transport controls until it automatically reconnects
        }

        @Override
        public void onConnectionFailed() {
            // The Service has refused our connection
        }
    };


    private MediaControllerCompat.Callback mMediaControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            Log.d("AAA", "onPlaybackStateChanged");
            super.onPlaybackStateChanged(state);
            if (state == null) {
                return;
            }

            if (mValueAnimator != null) {
                mValueAnimator.cancel();
                mValueAnimator = null;
            }

            isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            mbtnPlay.setPressed(isPlaying);

            if (isPlaying)
                updateProgress(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            Log.d("AAA", "onMetadataChanged");

            final int max = metadata != null ? (int) metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
            mProgress.setProgress(0);
            mProgress.setMax(max);

            //xử lý sau khi mở activity từ notification
            PlaybackStateCompat stateCompat = MediaControllerCompat.getMediaController(MainActivity.this).getPlaybackState();
            if (stateCompat != null)
                onPlaybackStateChanged(stateCompat);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("AAA", "onCreate: MainActivity");
        setContentView(R.layout.activity_main);

        mbtnNext = findViewById(R.id.btnNext);
        mbtnPlay = findViewById(R.id.btnPlay);
        mbtnPre = findViewById(R.id.mbtnPre);
        mProgress = findViewById(R.id.progress);

        mMediaBrowser = new MediaBrowserCompat(this, new ComponentName(this, BackgroundAudioService.class),
                mMediaBrowserConnectionCallback, getIntent().getExtras());

        mMediaBrowser.connect();

        mbtnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isPlaying)
                    getMediaController().getTransportControls().pause();
                else {
                    getMediaController().getTransportControls().play();
                }
            }
        });

        mProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean b) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                getMediaController().getTransportControls().seekTo(seekBar.getProgress());
                mIsTracking = false;
            }
        });
    }

    private void updateProgress(PlaybackStateCompat state) {
        int progress = (state != null) ? (int) state.getPosition() : 0;
        int timeToEnd = (int) ((mProgress.getMax() - progress) / state.getPlaybackSpeed());
        mValueAnimator = ValueAnimator.ofInt(progress, mProgress.getMax());
        mValueAnimator.setDuration(timeToEnd);
        mValueAnimator.setInterpolator(new LinearInterpolator());
        mValueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                if (mIsTracking) {
                    mValueAnimator.cancel();
                    return;
                }
                int curProgress = (int) valueAnimator.getAnimatedValue();
                mProgress.setProgress(curProgress);
            }
        });
        mValueAnimator.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (getMediaController() != null) {
            MediaControllerCompat.getMediaController(MainActivity.this).unregisterCallback(mMediaControllerCallback);
        }

        mMediaBrowser.disconnect();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d("AAA", "OnKeyDown");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                mMediaController.dispatchMediaButtonEvent(event);
                getMediaController().getTransportControls().pause();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

}
