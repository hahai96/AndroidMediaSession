package com.example.ha_hai.androidmediasession;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.io.IOException;
import java.util.List;

public class BackgroundAudioService extends MediaBrowserServiceCompat implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {

    public static final String COMMAND_EXAMPLE = "command_example";

    public MediaPlayer mMediaPlayer;
    private MediaSessionCompat mMediaSessionCompat;
    private AssetFileDescriptor afd;
    private MediaNotificationManager mn;

    private ResultReceiver receiver;
    private boolean mServiceInStartedState;

    private int mAudioFocusChange = 0;

    private long mSeekWhileNotPlaying = -1;

    //service đã được chạy? sau khi activity được mở bởi notification
    public static boolean isServiceRunning = false;

    private BroadcastReceiver mNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
            }
        }
    };

    @Override
    public void onCreate() {
        Log.d("AAA", "onCreate");

        super.onCreate();

//        initMediaPlayer();
        initMediaSession();
        initNoisyReceiver();
        mn = new MediaNotificationManager(this);
    }

    private MediaSessionCompat.Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            Log.d("AAA", "onAddQueueItem");
            super.onAddQueueItem(description);
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            Log.d("AAA", "onRemoveQueueItem");
            super.onRemoveQueueItem(description);
        }

        @Override
        public void onPrepare() {
            Log.d("AAA", "onPrepare: BackgroundAudioService");
            super.onPrepare();
            initMediaPlayer();
        }

        @Override
        public void onPlay() {
            Log.d("AAA", "onPlay: BackgroundAudioService");
            super.onPlay();
            if (!successfullyRetrievedAudioFocus()) {
                return;
            }

            Log.d("AAA", "mMediaPlayer = " + mMediaPlayer);

            if (mMediaPlayer == null) {
                onPlayFromMediaId(String.valueOf(R.raw.emmoilanguoiyeuanh), null);
            }

            // nếu setActive(false) session media controller có thể không được phát hiện
            // cần đặt true trước khi xử lý các media button hoặc các lệnh điều khiển
            mMediaSessionCompat.setActive(true);
            setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);

            if (!mServiceInStartedState) {
                ContextCompat.startForegroundService(BackgroundAudioService.this, new Intent(BackgroundAudioService.this, BackgroundAudioService.class));
                mServiceInStartedState = true;
            }

            showPlayingNotification();
            mMediaPlayer.start();
            isServiceRunning = true;
        }

        @Override
        public void onPause() {
            Log.d("AAA", "onPause: BackgroundAudioService");
            super.onPause();

            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
                setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                showPausedNotification();
                stopForeground(false);
            }
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            super.onPlayFromMediaId(mediaId, extras);
            Log.d("AAA", "onPlayFromMediaId: BackgroundAudioService");

            if (!isServiceRunning) {
                try {
                    onPrepare();
                    afd = getResources().openRawResourceFd(Integer.valueOf(mediaId));
                    if (afd == null) {
                        return;
                    }

                    try {
                        mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

                    } catch (IllegalStateException e) {
                        mMediaPlayer.release();
                        initMediaPlayer();
                        mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    }

                    afd.close();
                } catch (IOException e) {
                    return;
                }

                try {
                    mMediaPlayer.prepare();
                    initMediaSessionMetadata();
                } catch (IOException e) {
                }
            } else {
                //sau khi mở activity bởi notification thì phải cập nhật lại trạng thái
                initMediaSessionMetadata();
            }
            //Work with extras here if you want
        }

        @Override
        public void onCommand(String command, Bundle extras, ResultReceiver cb) {
            super.onCommand(command, extras, cb);
            if (COMMAND_EXAMPLE.equalsIgnoreCase(command)) {
                //Custom command here
            }

            if (command.equals("getCurrentPosition")) {

                receiver = cb;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (mMediaPlayer.getCurrentPosition() <= mMediaPlayer.getDuration()) {
                            Bundle bundle = new Bundle();
                            bundle.putInt("position", mMediaPlayer.getCurrentPosition());
                            receiver.send(1, bundle);
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

                thread.start();
            }
        }

        @Override
        public void onStop() {
            super.onStop();

            if (mMediaPlayer != null) {

                mMediaPlayer.release();
                mMediaPlayer = null;
                if (mAudioFocusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    setMediaPlaybackState(PlaybackStateCompat.STATE_STOPPED);
                    showPausedNotification();
                    mAudioFocusChange = 0;
                } else {
                    stopSelf();
                }
            }
        }

        @Override
        public void onSeekTo(long pos) {
            super.onSeekTo(pos);
            if (mMediaPlayer != null) {
                if (!mMediaPlayer.isPlaying())
                    mSeekWhileNotPlaying = pos;
                setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
            }
            mMediaPlayer.seekTo((int) pos);

            if (mMediaPlayer.isPlaying())
                setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onSkipToNext() {
            super.onSkipToNext();
            Log.d("AAA", "onSkipToNext");
        }

        @Override
        public void onSkipToPrevious() {
            super.onSkipToPrevious();
            Log.d("AAA", "onSkipToPrevious");
        }
    };

    private void initNoisyReceiver() {
        //Handles headphones coming unplugged. cannot be done through a manifest receiver
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(mNoisyReceiver, filter);
    }

    @Override
    public void onDestroy() {
        Log.d("AAA", "onDestroy");
        super.onDestroy();
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.abandonAudioFocus(this);
        unregisterReceiver(mNoisyReceiver);
        mMediaSessionCompat.release();
        stopForeground(true);
        stopSelf();
        NotificationManagerCompat.from(this).cancelAll();
        isServiceRunning = false;
    }

    private void initMediaPlayer() {
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setVolume(1.0f, 1.0f);
        }
    }

    private void showPlayingNotification() {
        NotificationCompat.Builder builder = mn.from(BackgroundAudioService.this, mMediaSessionCompat);
        if (builder == null) {
            return;
        }

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setOngoing(true);
        startForeground(MediaNotificationManager.NOTIFICATION_ID, builder.build());
    }

    private void showPausedNotification() {
        NotificationCompat.Builder builder = mn.from(this, mMediaSessionCompat);
        if (builder == null) {
            return;
        }

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setOngoing(false);
        builder.setAutoCancel(true);
        mn.getNotificationManager().notify(MediaNotificationManager.NOTIFICATION_ID, builder.build());
//        startForeground(MediaNotificationManager.NOTIFICATION_ID, builder.build());
    }


    private void initMediaSession() {


        //create MediaSessionCompat
        ComponentName mediaButtonReceiver = new ComponentName(getApplicationContext(), MediaButtonReceiver.class);
        mMediaSessionCompat = new MediaSessionCompat(getApplicationContext(), "Tag", mediaButtonReceiver, null);

        //handle callbacks from a media controller
        mMediaSessionCompat.setCallback(mMediaSessionCallback);
        //xử lý trong foreground activity trên phiên bản API 21+
        // Enable callbacks from MediaButtons and TransportControls
        mMediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
        mMediaSessionCompat.setMediaButtonReceiver(pendingIntent);

        // Set the session's token so that client activities can communicate with it.
        setSessionToken(mMediaSessionCompat.getSessionToken());
    }

    /**
     * MediaPlaybackState gồm có
     * Trạng thái truyền tải (đang play/pause/buffering/ tại phương thức getState())
     * Mã lỗi, và thông báo lỗi tùy chọn nếu có tại phương thức getErrorCode()
     * Vị trí người chơi
     * Các hành động điểu khiển hợp lệ có thể được xử lý trong trạng thái hiện tại
     */
    private void setMediaPlaybackState(int state) {
        final long reportPosition;
        if (mSeekWhileNotPlaying >= 0) {
            reportPosition = mSeekWhileNotPlaying;

            if (state == PlaybackStateCompat.STATE_PLAYING) {
                mSeekWhileNotPlaying = -1;
            }
        } else {
            reportPosition = mMediaPlayer == null ? 0 : mMediaPlayer.getCurrentPosition();
        }

        PlaybackStateCompat.Builder playbackstateBuilder = new PlaybackStateCompat.Builder();
        playbackstateBuilder.setActions(getAvailableActions(state));
        playbackstateBuilder.setState(state, reportPosition, 1.0f);
        mMediaSessionCompat.setPlaybackState(playbackstateBuilder.build());
    }

    private long getAvailableActions(int state) {
        long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        switch (state) {
            case PlaybackStateCompat.STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SEEK_TO;
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP;
                break;
            default:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
        }
        return actions;
    }

    /**
     * MediaMetadataCompat mô tả
     * Tên nghệ sĩ, anbum, bản nhạc
     * Thời lượng bản nhạc
     * Ảnh bìa album để hiển thị trên màn hình khóa. Hình ảnh là một bitmap với kích thước 320x320dp (nếu lớn hơn, nó được thu nhỏ)
     */
    private void initMediaSessionMetadata() {
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        //Notification icon in card
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));

        //lock screen icon for pre lollipop
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Display Title");
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Display Subtitle");
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 1);
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1);
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mMediaPlayer.getDuration());

        //chuyển dữ liệu mới đến media session
        mMediaSessionCompat.setMetadata(metadataBuilder.build());
    }

    private boolean successfullyRetrievedAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        int result = audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        return result == AudioManager.AUDIOFOCUS_GAIN;
    }


    //Not important for general audio service, required for class
    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    //Not important for general audio service, required for class
    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d("AAA", "onLoadChildren");
        result.sendResult(null);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {

        mAudioFocusChange = focusChange;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS: {
                Log.d("AAA", "AUDIOFOCUS_LOSS");
                if (mMediaPlayer.isPlaying()) {
                    mMediaSessionCallback.onStop();
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                Log.d("AAA", "AUDIOFOCUS_LOSS_TRANSIENT");
                mMediaSessionCallback.onPause();
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                Log.d("AAA", "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                if (mMediaPlayer != null) {
                    mMediaPlayer.setVolume(0.3f, 0.3f);
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_GAIN: {
                Log.d("AAA", "AUDIOFOCUS_GAIN");
                if (mMediaPlayer != null) {
                    if (!mMediaPlayer.isPlaying()) {
                        mMediaSessionCallback.onPlay();
                    }
                    mMediaPlayer.setVolume(1.0f, 1.0f);
                }
                break;
            }
        }
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("AAA", "onStartCommand");
//        phân tích cú pháp intent và gọi đến media session để xử lý
        MediaButtonReceiver.handleIntent(mMediaSessionCompat, intent);
        return START_STICKY;
    }

}

