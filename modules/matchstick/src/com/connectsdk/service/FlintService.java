/*
 * FlintService
 * Connect SDK
 *
 * Copyright (c) 2014 LG Electronics.
 * Created by Hyun Kook Khang on 23 Feb 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.connectsdk.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

import org.json.JSONObject;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.connectsdk.core.ImageInfo;
import com.connectsdk.core.MediaInfo;
import com.connectsdk.core.Util;
import com.connectsdk.discovery.DiscoveryFilter;
import com.connectsdk.discovery.DiscoveryManager;
import com.connectsdk.service.capability.CapabilityMethods;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.capability.VolumeControl;
import com.connectsdk.service.capability.WebAppLauncher;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommandError;
import com.connectsdk.service.command.ServiceSubscription;
import com.connectsdk.service.command.URLServiceSubscription;
import com.connectsdk.service.config.FlintServiceDescription;
import com.connectsdk.service.config.ServiceConfig;
import com.connectsdk.service.config.ServiceDescription;
import com.connectsdk.service.sessions.FlintWebAppSession;
import com.connectsdk.service.sessions.LaunchSession;
import com.connectsdk.service.sessions.LaunchSession.LaunchSessionType;
import com.connectsdk.service.sessions.WebAppSession;
import com.connectsdk.service.sessions.WebAppSession.WebAppPinStatusListener;
import tv.matchstick.flint.ApplicationMetadata;
import tv.matchstick.flint.Flint;
import tv.matchstick.flint.Flint.ApplicationConnectionResult;
import tv.matchstick.flint.FlintDevice;
import tv.matchstick.flint.FlintMediaControlIntent;
import tv.matchstick.flint.MediaMetadata;
import tv.matchstick.flint.RemoteMediaPlayer;
import tv.matchstick.flint.RemoteMediaPlayer.MediaChannelResult;
import tv.matchstick.flint.ConnectionResult;
import tv.matchstick.flint.FlintManager;
import tv.matchstick.flint.ResultCallback;
import tv.matchstick.flint.Status;
import tv.matchstick.flint.images.WebImage;

public class FlintService extends DeviceService implements MediaPlayer,
        MediaControl, VolumeControl, WebAppLauncher {
    interface ConnectionListener {
        void onConnected();
    };

    public interface LaunchWebAppListener {
        void onSuccess(WebAppSession webAppSession);

        void onFailure(ServiceCommandError error);
    };

    // @cond INTERNAL

    public static final String ID = "Matchstick";
    public final static String TAG = "FlintService";

    public final static String PLAY_STATE = "PlayState";
    public final static String Flint_SERVICE_VOLUME_SUBSCRIPTION_NAME = "volume";
    public final static String Flint_SERVICE_MUTE_SUBSCRIPTION_NAME = "mute";

    // @endcond

    String currentAppId;
    String launchingAppId;

    FlintManager mApiClient;
    FlintListener mFlintClientListener;
    ConnectionCallbacks mConnectionCallbacks;

    FlintDevice flintDevice;
    RemoteMediaPlayer mMediaPlayer;

    Map<String, FlintWebAppSession> sessions;
    List<URLServiceSubscription<?>> subscriptions;

    float currentVolumeLevel;
    boolean currentMuteStatus;
    boolean mWaitingForReconnect;

    float currentStreamVolumeLevel;
    boolean currentStreamMuteStatus;

    static String applicationID = "~flintplayer";

    private String APPLICATION_URL = "http://openflint.github.io/flint-player/player.html";

    // Queue of commands that should be sent once register is complete
    CopyOnWriteArraySet<ConnectionListener> commandQueue = new CopyOnWriteArraySet<ConnectionListener>();

    public FlintService(ServiceDescription serviceDescription,
            ServiceConfig serviceConfig) {
        super(serviceDescription, serviceConfig);

        Flint.FlintApi.setApplicationId(getApplicationID());

        mFlintClientListener = new FlintListener();
        mConnectionCallbacks = new ConnectionCallbacks();

        sessions = new HashMap<String, FlintWebAppSession>();
        subscriptions = new ArrayList<URLServiceSubscription<?>>();

        mWaitingForReconnect = false;
    }

    @Override
    public String getServiceName() {
        return ID;
    }

    public static DiscoveryFilter discoveryFilter() {
        return new DiscoveryFilter(ID, "Matchstick");
    }

    public static void setApplicationID(String id) {
        applicationID = id;
    }

    public static String getApplicationID() {
        return applicationID;
    }

    @Override
    public CapabilityPriorityLevel getPriorityLevel(
            Class<? extends CapabilityMethods> clazz) {
        if (clazz.equals(MediaPlayer.class)) {
            return getMediaPlayerCapabilityLevel();
        } else if (clazz.equals(MediaControl.class)) {
            return getMediaControlCapabilityLevel();
        } else if (clazz.equals(VolumeControl.class)) {
            return getVolumeControlCapabilityLevel();
        } else if (clazz.equals(WebAppLauncher.class)) {
            return getWebAppLauncherCapabilityLevel();
        }
        return CapabilityPriorityLevel.NOT_SUPPORTED;
    }

    @Override
    public void connect() {
        if (connected && mApiClient != null && mApiClient.isConnecting()
                && mApiClient.isConnected())
            return;

        if (flintDevice == null) {
            if (getServiceDescription() instanceof FlintServiceDescription)
                this.flintDevice = ((FlintServiceDescription) getServiceDescription())
                        .getFlintDevice();
        }

        if (mApiClient == null) {
            mApiClient = createApiClient();
            mApiClient.connect();
        }
    }

    protected FlintManager createApiClient() {
        Flint.FlintOptions.Builder apiOptionsBuilder = Flint.FlintOptions
                .builder(flintDevice, mFlintClientListener);

        return new FlintManager.Builder(DiscoveryManager.getInstance()
                .getContext()).addApi(Flint.API, apiOptionsBuilder.build())
                .addConnectionCallbacks(mConnectionCallbacks).build();
    }

    @Override
    public void disconnect() {
        if (!connected)
            return;

        connected = false;
        mWaitingForReconnect = false;

        detachMediaPlayer();

        if (!commandQueue.isEmpty())
            commandQueue.clear();

        if (mApiClient != null && mApiClient.isConnected()) {
            Log.e("Flint", "disconnect!leaveApplication!!!");
            Flint.FlintApi.leaveApplication(mApiClient);
            mApiClient.disconnect();
        }
        mApiClient = null;

        Util.runOnUI(new Runnable() {

            @Override
            public void run() {
                if (getListener() != null) {
                    getListener().onDisconnect(FlintService.this, null);
                }
            }
        });
    }

    @Override
    public MediaControl getMediaControl() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getMediaControlCapabilityLevel() {
        return CapabilityPriorityLevel.HIGH;
    }

    @Override
    public void play(final ResponseListener<Object> listener) {
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                try {
                    mMediaPlayer.play(mApiClient);
                    Util.postSuccess(listener, null);
                } catch (Exception e) {
                    Util.postError(listener, new ServiceCommandError(0,
                            "Unable to play", null));
                }
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void pause(final ResponseListener<Object> listener) {
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                try {
                    mMediaPlayer.pause(mApiClient);

                    Util.postSuccess(listener, null);
                } catch (Exception e) {
                    Util.postError(listener, new ServiceCommandError(0,
                            "Unable to pause", null));
                }
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void stop(final ResponseListener<Object> listener) {
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                try {
                    mMediaPlayer.stop(mApiClient);

                    Util.postSuccess(listener, null);
                } catch (Exception e) {
                    Util.postError(listener, new ServiceCommandError(0,
                            "Unable to stop", null));
                }
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void rewind(ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void fastForward(ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void previous(ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void next(ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void seek(final long position,
            final ResponseListener<Object> listener) {
        if (mMediaPlayer == null || mMediaPlayer.getMediaStatus() == null) {
            Util.postError(listener, new ServiceCommandError(0,
                    "There is no media currently available", null));
            return;
        }

        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                mMediaPlayer.seek(mApiClient, position,
                        RemoteMediaPlayer.RESUME_STATE_UNCHANGED)
                        .setResultCallback(
                                new ResultCallback<MediaChannelResult>() {

                                    @Override
                                    public void onResult(
                                            MediaChannelResult result) {
                                        Status status = result.getStatus();

                                        if (status.isSuccess()) {
                                            Util.postSuccess(listener, null);
                                        } else {
                                            Util.postError(
                                                    listener,
                                                    new ServiceCommandError(
                                                            status.getStatusCode(),
                                                            status.getStatusMessage(),
                                                            status));
                                        }
                                    }
                                });
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void getDuration(final DurationListener listener) {
        if (mMediaPlayer != null && mMediaPlayer.getMediaStatus() != null) {
            Util.postSuccess(listener, mMediaPlayer.getStreamDuration());
        } else {
            Util.postError(listener, new ServiceCommandError(0,
                    "There is no media currently available", null));
        }
    }

    @Override
    public void getPosition(final PositionListener listener) {
        if (mMediaPlayer != null && mMediaPlayer.getMediaStatus() != null) {
            Util.postSuccess(listener,
                    mMediaPlayer.getApproximateStreamPosition());
        } else {
            Util.postError(listener, new ServiceCommandError(0,
                    "There is no media currently available", null));
        }
    }

    @Override
    public MediaPlayer getMediaPlayer() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getMediaPlayerCapabilityLevel() {
        return CapabilityPriorityLevel.HIGH;
    }

    @Override
    public void getMediaInfo(MediaInfoListener listener) {
        if (mMediaPlayer == null)
            return;

        if (mMediaPlayer.getMediaInfo() != null) {
            String url = mMediaPlayer.getMediaInfo().getContentId();
            String mimeType = mMediaPlayer.getMediaInfo().getContentType();
            String iconUrl = mMediaPlayer.getMediaInfo().getMetadata()
                    .getImages().get(0).getUrl().toString();
            String title = mMediaPlayer.getMediaInfo().getMetadata()
                    .getString(MediaMetadata.KEY_TITLE);
            String description = mMediaPlayer.getMediaInfo().getMetadata()
                    .getString(MediaMetadata.KEY_SUBTITLE);

            ArrayList<ImageInfo> list = new ArrayList<ImageInfo>();
            list.add(new ImageInfo(iconUrl));
            MediaInfo info = new MediaInfo(url, mimeType, title, description,
                    list);

            Util.postSuccess(listener, info);
        } else {
            Util.postError(listener, new ServiceCommandError(0,
                    "Media Info is null", null));
        }
    }

    @Override
    public ServiceSubscription<MediaInfoListener> subscribeMediaInfo(
            MediaInfoListener listener) {
        URLServiceSubscription<MediaInfoListener> request = new URLServiceSubscription<MediaInfoListener>(
                this, "info", null, null);
        request.addListener(listener);
        addSubscription(request);

        return request;
    }

    private void attachMediaPlayer() {
        if (mMediaPlayer != null) {
            return;
        }

        mMediaPlayer = createMediaPlayer();
        mMediaPlayer
                .setOnStatusUpdatedListener(new RemoteMediaPlayer.OnStatusUpdatedListener() {

                    @Override
                    public void onStatusUpdated() {
                        if (subscriptions.size() > 0) {
                            for (URLServiceSubscription<?> subscription : subscriptions) {
                                if (subscription.getTarget().equalsIgnoreCase(
                                        PLAY_STATE)) {
                                    for (int i = 0; i < subscription
                                            .getListeners().size(); i++) {
                                        @SuppressWarnings("unchecked")
                                        ResponseListener<Object> listener = (ResponseListener<Object>) subscription
                                                .getListeners().get(i);
                                        PlayStateStatus status = PlayStateStatus
                                                .convertPlayerStateToPlayStateStatus(mMediaPlayer
                                                        .getMediaStatus()
                                                        .getPlayerState());
                                        Util.postSuccess(listener, status);
                                    }
                                }
                            }
                        }
                    }
                });

        mMediaPlayer
                .setOnMetadataUpdatedListener(new RemoteMediaPlayer.OnMetadataUpdatedListener() {
                    @Override
                    public void onMetadataUpdated() {
                        if (subscriptions.size() > 0) {
                            for (URLServiceSubscription<?> subscription : subscriptions) {
                                if (subscription.getTarget().equalsIgnoreCase(
                                        "info")) {
                                    for (int i = 0; i < subscription
                                            .getListeners().size(); i++) {
                                        MediaInfoListener listener = (MediaInfoListener) subscription
                                                .getListeners().get(i);
                                        getMediaInfo(listener);
                                    }
                                }
                            }
                        }
                    }
                });

        if (mApiClient != null) {
            try {
                Flint.FlintApi.setMessageReceivedCallbacks(mApiClient,
                        mMediaPlayer.getNamespace(), mMediaPlayer);
            } catch (Exception e) {
                Log.w("Connect SDK", "Exception while creating media channel",
                        e);
            }
        }
    }

    protected RemoteMediaPlayer createMediaPlayer() {
        return new RemoteMediaPlayer();
    }

    private void detachMediaPlayer() {
        if ((mMediaPlayer != null) && (mApiClient != null)) {
            try {
                Flint.FlintApi.removeMessageReceivedCallbacks(mApiClient,
                        mMediaPlayer.getNamespace());
            } catch (IOException e) {
                Log.w("Connect SDK", "Exception while launching application", e);
            }
        }
        mMediaPlayer = null;
    }

    @Override
    public void displayImage(String url, String mimeType, String title,
            String description, String iconSrc, LaunchListener listener) {
        MediaMetadata mMediaMetadata = new MediaMetadata(
                MediaMetadata.MEDIA_TYPE_PHOTO);
        mMediaMetadata.putString(MediaMetadata.KEY_TITLE, title);
        mMediaMetadata.putString(MediaMetadata.KEY_SUBTITLE, description);

        if (iconSrc != null) {
            Uri iconUri = Uri.parse(iconSrc);
            WebImage image = new WebImage(iconUri, 100, 100);
            mMediaMetadata.addImage(image);
        }

        tv.matchstick.flint.MediaInfo mediaInformation = new tv.matchstick.flint.MediaInfo.Builder(
                url).setContentType(mimeType)
                .setStreamType(tv.matchstick.flint.MediaInfo.STREAM_TYPE_NONE)
                .setMetadata(mMediaMetadata).setStreamDuration(0)
                .setCustomData(null).build();

        playMedia(mediaInformation, APPLICATION_URL, listener);
    }

    @Override
    public void displayImage(MediaInfo mediaInfo, LaunchListener listener) {
        ImageInfo imageInfo = mediaInfo.getImages().get(0);
        String iconSrc = imageInfo.getUrl();

        displayImage(mediaInfo.getUrl(), mediaInfo.getMimeType(),
                mediaInfo.getTitle(), mediaInfo.getDescription(), iconSrc,
                listener);
    }

    @Override
    public void playMedia(String url, String mimeType, String title,
            String description, String iconSrc, boolean shouldLoop,
            LaunchListener listener) {
        MediaMetadata mMediaMetadata = new MediaMetadata(
                MediaMetadata.MEDIA_TYPE_MOVIE);
        mMediaMetadata.putString(MediaMetadata.KEY_TITLE, title);
        mMediaMetadata.putString(MediaMetadata.KEY_SUBTITLE, description);

        if (iconSrc != null) {
            Uri iconUri = Uri.parse(iconSrc);
            WebImage image = new WebImage(iconUri, 100, 100);
            mMediaMetadata.addImage(image);
        }

        tv.matchstick.flint.MediaInfo mediaInformation = new tv.matchstick.flint.MediaInfo.Builder(
                url)
                .setContentType(mimeType)
                .setStreamType(
                        tv.matchstick.flint.MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(mMediaMetadata).setStreamDuration(1000)
                .setCustomData(null).build();

        playMedia(mediaInformation, APPLICATION_URL, listener);
    }

    @Override
    public void playMedia(MediaInfo mediaInfo, boolean shouldLoop,
            LaunchListener listener) {
        ImageInfo imageInfo = mediaInfo.getImages().get(0);
        String iconSrc = imageInfo.getUrl();

        playMedia(mediaInfo.getUrl(), mediaInfo.getMimeType(),
                mediaInfo.getTitle(), mediaInfo.getDescription(), iconSrc,
                shouldLoop, listener);
    }

    private void playMedia(
            final tv.matchstick.flint.MediaInfo mediaInformation,
            final String mediaAppId, final LaunchListener listener) {
        Log.e(TAG, "playMedia!");
        final ApplicationConnectionResultCallback webAppLaunchCallback = new ApplicationConnectionResultCallback(
                new LaunchWebAppListener() {

                    @Override
                    public void onSuccess(final WebAppSession webAppSession) {
                        ConnectionListener connectionListener = new ConnectionListener() {

                            @Override
                            public void onConnected() {
                                Log.e(TAG, "playMedia!onConnected!");
                                
                                Util.postSuccess(
                                        listener,
                                        new MediaLaunchObject(
                                                webAppSession.launchSession,
                                                FlintService.this));
                                
                                mMediaPlayer
                                        .load(mApiClient, mediaInformation,
                                                true)
                                        .setResultCallback(
                                                new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {

                                                    @Override
                                                    public void onResult(
                                                            MediaChannelResult result) {

                                                        Status status = result
                                                                .getStatus();
                                                        
                                                        Log.e(TAG, "mMediaPlayer.load:onResult[" + status);
                                                        
                                                        if (status.isSuccess()) {
                                                            webAppSession.launchSession
                                                                    .setSessionType(LaunchSessionType.Media);

                                                            Util.postSuccess(
                                                                    listener,
                                                                    new MediaLaunchObject(
                                                                            webAppSession.launchSession,
                                                                            FlintService.this));
                                                        } else {
                                                            Util.postError(
                                                                    listener,
                                                                    new ServiceCommandError(
                                                                            status.getStatusCode(),
                                                                            status.getStatusMessage(),
                                                                            status));
                                                        }
                                                    }
                                                });
                            }
                        };

                        runCommand(connectionListener);
                    }

                    @Override
                    public void onFailure(ServiceCommandError error) {
                        Util.postError(listener, error);
                    }
                });

        launchingAppId = mediaAppId;

        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                boolean relaunchIfRunning = false;

                Log.e(TAG, "playMedia: status[" + Flint.FlintApi.getApplicationStatus(mApiClient) + "]currentAppId[" + currentAppId + "]mediaAppId[" + mediaAppId + "]");
//                if (Flint.FlintApi.getApplicationStatus(mApiClient) == null
//                        || (!mediaAppId.equals(currentAppId)))
              if (!mediaAppId.equals(currentAppId))
                    relaunchIfRunning = true;

                Flint.FlintApi.launchApplication(mApiClient, mediaAppId,
                        relaunchIfRunning).setResultCallback(
                        webAppLaunchCallback);
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void closeMedia(final LaunchSession launchSession,
            final ResponseListener<Object> listener) {
        Log.e(TAG, "closeMedia!");
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                Flint.FlintApi.stopApplication(mApiClient).setResultCallback(
                        new ResultCallback<Status>() {

                            @Override
                            public void onResult(Status result) {
                                Log.e(TAG, "closeMedia!onResult:" + result);
                                if (result.isSuccess()) {
                                    Util.postSuccess(listener, result);
                                } else {
                                    Util.postError(
                                            listener,
                                            new ServiceCommandError(result
                                                    .getStatusCode(), result
                                                    .getStatusMessage(), result));
                                }
                            }
                        });
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public WebAppLauncher getWebAppLauncher() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getWebAppLauncherCapabilityLevel() {
        return CapabilityPriorityLevel.HIGH;
    }

    @Override
    public void launchWebApp(String webAppId,
            WebAppSession.LaunchListener listener) {
        launchWebApp(webAppId, true, listener);
    }

    @Override
    public void launchWebApp(final String webAppId,
            final boolean relaunchIfRunning,
            final WebAppSession.LaunchListener listener) {
        launchingAppId = webAppId;

        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                Flint.FlintApi.launchApplication(mApiClient, webAppId,
                        relaunchIfRunning).setResultCallback(
                        new ApplicationConnectionResultCallback(
                                new LaunchWebAppListener() {

                                    @Override
                                    public void onSuccess(
                                            WebAppSession webAppSession) {
                                        Util.postSuccess(listener,
                                                webAppSession);
                                    }

                                    @Override
                                    public void onFailure(
                                            ServiceCommandError error) {
                                        Util.postError(listener, error);
                                    }
                                }));
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void launchWebApp(String webAppId, JSONObject params,
            WebAppSession.LaunchListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void launchWebApp(String webAppId, JSONObject params,
            boolean relaunchIfRunning, WebAppSession.LaunchListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void joinWebApp(final LaunchSession webAppLaunchSession,
            final WebAppSession.LaunchListener listener) {
        final ApplicationConnectionResultCallback webAppLaunchCallback = new ApplicationConnectionResultCallback(
                new LaunchWebAppListener() {

                    @Override
                    public void onSuccess(final WebAppSession webAppSession) {
                        webAppSession.connect(new ResponseListener<Object>() {

                            @Override
                            public void onSuccess(Object object) {
                                Util.postSuccess(listener, webAppSession);
                            }

                            @Override
                            public void onError(ServiceCommandError error) {
                                Util.postError(listener, error);
                            }
                        });
                    }

                    @Override
                    public void onFailure(ServiceCommandError error) {
                        Util.postError(listener, error);
                    }
                });

        launchingAppId = webAppLaunchSession.getAppId();

        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                Flint.FlintApi.joinApplication(mApiClient,
                        webAppLaunchSession.getAppId()).setResultCallback(
                        webAppLaunchCallback);
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void joinWebApp(String webAppId,
            WebAppSession.LaunchListener listener) {
        LaunchSession launchSession = LaunchSession
                .launchSessionForAppId(webAppId);
        launchSession.setSessionType(LaunchSessionType.WebApp);
        launchSession.setService(this);

        joinWebApp(launchSession, listener);
    }

    @Override
    public void closeWebApp(LaunchSession launchSession,
            final ResponseListener<Object> listener) {
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                Flint.FlintApi.stopApplication(mApiClient).setResultCallback(
                        new ResultCallback<Status>() {

                            @Override
                            public void onResult(Status status) {
                                if (status.isSuccess()) {
                                    Util.postSuccess(listener, null);
                                } else {
                                    Util.postError(
                                            listener,
                                            new ServiceCommandError(status
                                                    .getStatusCode(), status
                                                    .getStatusMessage(), status));
                                }
                            }
                        });
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void pinWebApp(String webAppId, ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void unPinWebApp(String webAppId, ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void isWebAppPinned(String webAppId, WebAppPinStatusListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public ServiceSubscription<WebAppPinStatusListener> subscribeIsWebAppPinned(
            String webAppId, WebAppPinStatusListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
        return null;
    }

    @Override
    public VolumeControl getVolumeControl() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getVolumeControlCapabilityLevel() {
        return CapabilityPriorityLevel.HIGH;
    }

    @Override
    public void volumeUp(final ResponseListener<Object> listener) {
        getVolume(new VolumeListener() {

            @Override
            public void onSuccess(final Float volume) {
                if (volume >= 1.0) {
                    Util.postSuccess(listener, null);
                } else {
                    float newVolume = (float) (volume + 0.01);

                    if (newVolume > 1.0)
                        newVolume = (float) 1.0;

                    setVolume(newVolume, listener);

                    Util.postSuccess(listener, null);
                }
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        });
    }

    @Override
    public void volumeDown(final ResponseListener<Object> listener) {
        getVolume(new VolumeListener() {

            @Override
            public void onSuccess(final Float volume) {
                if (volume <= 0.0) {
                    Util.postSuccess(listener, null);
                } else {
                    float newVolume = (float) (volume - 0.01);

                    if (newVolume < 0.0)
                        newVolume = (float) 0.0;

                    setVolume(newVolume, listener);

                    Util.postSuccess(listener, null);
                }
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        });
    }

    @Override
    public void setVolume(final float volume,
            final ResponseListener<Object> listener) {
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                try {
                    Log.e("Connect SDK", "volume:" + volume);
                    if (mMediaPlayer != null
                            && mMediaPlayer.getMediaStatus() != null) {
                        Log.e("Connect SDK", "stream volume:" + volume);
                        mMediaPlayer.setStreamVolume(mApiClient, volume);
                    } else {
                        Log.e("Connect SDK", "device volume:" + volume);
                        Flint.FlintApi.setVolume(mApiClient, volume);
                    }

                    Util.postSuccess(listener, null);
                } catch (IOException e) {
                    Util.postError(listener, new ServiceCommandError(0,
                            "setting volume level failed", null));
                }
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void getVolume(VolumeListener listener) {
        try {
            currentVolumeLevel = (float) Flint.FlintApi
                    .getVolume(mApiClient);
            currentMuteStatus = Flint.FlintApi.isMute(mApiClient);

            if (mMediaPlayer != null
                    && mMediaPlayer.getMediaStatus() != null) {
                currentStreamVolumeLevel = (float) mMediaPlayer
                        .getMediaStatus().getStreamVolume();
                currentStreamMuteStatus = mMediaPlayer
                        .getMediaStatus().isMute();
            }

        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
        
        if (mMediaPlayer != null && mMediaPlayer.getMediaStatus() != null) {
            // Log.e("Matchstick", "getVolume: streamVolume[" +
            // currentStreamVolumeLevel + "]");
            Util.postSuccess(listener, currentStreamVolumeLevel);
        } else {
            // Log.e("Matchstick", "getVolume: deviceVolume[" +
            // currentStreamVolumeLevel + "]");
            Util.postSuccess(listener, currentVolumeLevel);
        }
    }

    @Override
    public void setMute(final boolean isMute,
            final ResponseListener<Object> listener) {
        ConnectionListener connectionListener = new ConnectionListener() {

            @Override
            public void onConnected() {
                try {
                    if (mMediaPlayer != null
                            && mMediaPlayer.getMediaStatus() != null) {
                        Log.e("Connect SDK", "set stream Mute:" + isMute);
                        mMediaPlayer.setStreamMute(mApiClient, isMute);
                    } else {
                        Log.e("Connect SDK", "set device Mute:" + isMute);
                        Flint.FlintApi.setMute(mApiClient, isMute);
                    }

                    Util.postSuccess(listener, null);
                } catch (IOException e) {
                    Util.postError(listener, new ServiceCommandError(0,
                            "setting mute status failed", null));
                }
            }
        };

        runCommand(connectionListener);
    }

    @Override
    public void getMute(final MuteListener listener) {
        if (mMediaPlayer != null
                && mMediaPlayer.getMediaStatus() != null) {
            Util.postSuccess(listener, currentStreamMuteStatus);
        } else {
            Util.postSuccess(listener, currentMuteStatus);
        }
    }

    @Override
    public ServiceSubscription<VolumeListener> subscribeVolume(
            VolumeListener listener) {
        URLServiceSubscription<VolumeListener> request = new URLServiceSubscription<VolumeListener>(
                this, Flint_SERVICE_VOLUME_SUBSCRIPTION_NAME, null, null);
        request.addListener(listener);
        addSubscription(request);

        return request;
    }

    @Override
    public ServiceSubscription<MuteListener> subscribeMute(MuteListener listener) {
        URLServiceSubscription<MuteListener> request = new URLServiceSubscription<MuteListener>(
                this, Flint_SERVICE_MUTE_SUBSCRIPTION_NAME, null, null);
        request.addListener(listener);
        addSubscription(request);

        return request;
    }

    @Override
    protected void updateCapabilities() {
        List<String> capabilities = new ArrayList<String>();

        for (String capability : MediaPlayer.Capabilities) {
            capabilities.add(capability);
        }
        for (String capability : VolumeControl.Capabilities) {
            capabilities.add(capability);
        }

        capabilities.add(Play);
        capabilities.add(Pause);
        capabilities.add(Stop);
        capabilities.add(Duration);
        capabilities.add(Seek);
        capabilities.add(Position);
        capabilities.add(PlayState);
        capabilities.add(PlayState_Subscribe);

        capabilities.add(WebAppLauncher.Launch);
        capabilities.add(Message_Send);
        capabilities.add(Message_Receive);
        capabilities.add(Message_Send_JSON);
        capabilities.add(Message_Receive_JSON);
        capabilities.add(WebAppLauncher.Connect);
        capabilities.add(WebAppLauncher.Disconnect);
        capabilities.add(WebAppLauncher.Join);
        capabilities.add(WebAppLauncher.Close);

        setCapabilities(capabilities);
    }

    private class FlintListener extends Flint.Listener {
        @Override
        public void onApplicationDisconnected(int statusCode) {
            Log.d("Connect SDK", "Flint.Listener.onApplicationDisconnected: "
                    + statusCode);

            if (currentAppId == null)
                return;

            FlintWebAppSession webAppSession = sessions.get(currentAppId);

            if (webAppSession == null)
                return;

            webAppSession.handleAppClose();

            currentAppId = null;
        }

        @Override
        public void onApplicationStatusChanged() {
            ConnectionListener connectionListener = new ConnectionListener() {

                @Override
                public void onConnected() {
                    ApplicationMetadata applicationMetadata = Flint.FlintApi
                            .getApplicationMetadata(mApiClient);

                    // if (applicationMetadata != null)
                    // currentAppId = applicationMetadata.getApplicationId();
                }
            };

            runCommand(connectionListener);
        }

        @Override
        public void onVolumeChanged() {
            ConnectionListener connectionListener = new ConnectionListener() {

                @Override
                public void onConnected() {
                    try {
                        currentVolumeLevel = (float) Flint.FlintApi
                                .getVolume(mApiClient);
                        currentMuteStatus = Flint.FlintApi.isMute(mApiClient);

                        if (mMediaPlayer != null
                                && mMediaPlayer.getMediaStatus() != null) {
                            currentStreamVolumeLevel = (float) mMediaPlayer
                                    .getMediaStatus().getStreamVolume();
                            currentStreamMuteStatus = mMediaPlayer
                                    .getMediaStatus().isMute();
                        }

                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                    }

                    if (subscriptions.size() > 0) {
                        for (URLServiceSubscription<?> subscription : subscriptions) {
                            if (subscription.getTarget().equals(
                                    Flint_SERVICE_VOLUME_SUBSCRIPTION_NAME)) {
                                for (int i = 0; i < subscription.getListeners()
                                        .size(); i++) {
                                    @SuppressWarnings("unchecked")
                                    ResponseListener<Object> listener = (ResponseListener<Object>) subscription
                                            .getListeners().get(i);

                                    if (mMediaPlayer != null
                                            && mMediaPlayer.getMediaStatus() != null) {
                                        Util.postSuccess(listener,
                                                currentStreamVolumeLevel);
                                    } else {
                                        Util.postSuccess(listener,
                                                currentVolumeLevel);
                                    }
                                }
                            } else if (subscription.getTarget().equals(
                                    Flint_SERVICE_MUTE_SUBSCRIPTION_NAME)) {
                                for (int i = 0; i < subscription.getListeners()
                                        .size(); i++) {
                                    @SuppressWarnings("unchecked")
                                    ResponseListener<Object> listener = (ResponseListener<Object>) subscription
                                            .getListeners().get(i);

                                    if (mMediaPlayer != null
                                            && mMediaPlayer.getMediaStatus() != null) {
                                        Util.postSuccess(listener,
                                                currentStreamMuteStatus);
                                    } else {
                                        Util.postSuccess(listener,
                                                currentMuteStatus);
                                    }
                                }
                            }
                        }
                    }
                }
            };

            runCommand(connectionListener);
        }
    }

    private class ConnectionCallbacks implements
            FlintManager.ConnectionCallbacks {
        @Override
        public void onConnectionSuspended(final int cause) {
            Log.d(TAG, "ConnectionCallbacks.onConnectionSuspended");

            mWaitingForReconnect = true;
            detachMediaPlayer();
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG,
                    "ConnectionCallbacks.onConnected, wasWaitingForReconnect: "
                            + mWaitingForReconnect);

            attachMediaPlayer();

            if (mWaitingForReconnect) {
                mWaitingForReconnect = false;

                if (Flint.FlintApi.getApplicationStatus(mApiClient) != null
                        && currentAppId != null) {
                    FlintWebAppSession webAppSession = sessions
                            .get(currentAppId);

                    webAppSession.connect(null);
                }
            } else {
                connected = true;

                reportConnected(true);
            }

            if (!commandQueue.isEmpty()) {
                for (ConnectionListener listener : commandQueue) {
                    listener.onConnected();
                    commandQueue.remove(listener);
                }
            }
        }

        @Override
        public void onConnectionFailed(final ConnectionResult result) {
            Log.d(TAG, "ConnectionFailedListener.onConnectionFailed "
                    + (result != null ? result : ""));

            detachMediaPlayer();
            connected = false;
            mWaitingForReconnect = false;
            mApiClient = null;

            Util.runOnUI(new Runnable() {

                @Override
                public void run() {
                    if (listener != null) {
                        ServiceCommandError error = new ServiceCommandError(
                                result.getErrorCode(),
                                "Failed to connect to Matchstick device",
                                result);

                        listener.onConnectionFailure(FlintService.this, error);
                    }
                }
            });
        }
    }

    private class ApplicationConnectionResultCallback implements
            ResultCallback<Flint.ApplicationConnectionResult> {
        LaunchWebAppListener listener;

        public ApplicationConnectionResultCallback(LaunchWebAppListener listener) {
            this.listener = listener;
        }

        @Override
        public void onResult(ApplicationConnectionResult result) {
            Status status = result.getStatus();
            Log.e(TAG, "ApplicationConnectionResultCallback:onResult[" + status + "]");
            if (status.isSuccess()) {
                ApplicationMetadata applicationMetadata = result
                        .getApplicationMetadata();
                currentAppId = APPLICATION_URL; // applicationMetadata.getApplicationId();

                LaunchSession launchSession = LaunchSession
                        .launchSessionForAppId(currentAppId);
                // launchSession.setAppName(applicationMetadata.getName());
                // launchSession.setSessionId(result.getSessionId());
                launchSession.setSessionType(LaunchSessionType.WebApp);
                launchSession.setService(FlintService.this);

                FlintWebAppSession webAppSession = new FlintWebAppSession(
                        launchSession, FlintService.this);
                webAppSession.setMetadata(applicationMetadata);

                sessions.put(currentAppId, webAppSession);

                if (listener != null) {
                    listener.onSuccess(webAppSession);
                }

                launchingAppId = null;
            } else {
                if (listener != null) {
                    listener.onFailure(new ServiceCommandError(status
                            .getStatusCode(), status.getStatusMessage(), status));
                }
            }
        }
    }

    @Override
    public void getPlayState(PlayStateListener listener) {
        if (mMediaPlayer != null && mMediaPlayer.getMediaStatus() != null) {
            PlayStateStatus status = PlayStateStatus
                    .convertPlayerStateToPlayStateStatus(mMediaPlayer
                            .getMediaStatus().getPlayerState());
            Util.postSuccess(listener, status);
        } else {
            Util.postError(listener, new ServiceCommandError(0,
                    "There is no media currently available", null));
        }
    }

    public FlintManager getApiClient() {
        return mApiClient;
    }

    // ////////////////////////////////////////////////
    // Device Service Methods
    // ////////////////////////////////////////////////
    @Override
    public boolean isConnectable() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public ServiceSubscription<PlayStateListener> subscribePlayState(
            PlayStateListener listener) {
        URLServiceSubscription<PlayStateListener> request = new URLServiceSubscription<PlayStateListener>(
                this, PLAY_STATE, null, null);
        request.addListener(listener);
        addSubscription(request);

        return request;
    }

    private void addSubscription(URLServiceSubscription<?> subscription) {
        subscriptions.add(subscription);
    }

    @Override
    public void unsubscribe(URLServiceSubscription<?> subscription) {
        subscriptions.remove(subscription);
    }

    public List<URLServiceSubscription<?>> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(List<URLServiceSubscription<?>> subscriptions) {
        this.subscriptions = subscriptions;
    }

    private void runCommand(ConnectionListener connectionListener) {
        if (mApiClient != null && mApiClient.isConnected()) {
            connectionListener.onConnected();
        } else {
            connect();
            commandQueue.add(connectionListener);
        }
    }

}
