/*
 * MatchstickService
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
 * 
 * Add this file to support Matchstick, modified by Jianmin Zhou(toandrew@infthink), 13/10/2014.
 */

package com.connectsdk.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import tv.matchstick.fling.ApplicationMetadata;
import tv.matchstick.fling.ConnectionResult;
import tv.matchstick.fling.Fling;
import tv.matchstick.fling.Fling.ApplicationConnectionResult;
import tv.matchstick.fling.FlingDevice;
import tv.matchstick.fling.FlingManager;
import tv.matchstick.fling.MediaInfo;
import tv.matchstick.fling.MediaMetadata;
import tv.matchstick.fling.MediaStatus;
import tv.matchstick.fling.RemoteMediaPlayer;
import tv.matchstick.fling.RemoteMediaPlayer.MediaChannelResult;
import tv.matchstick.fling.ResultCallback;
import tv.matchstick.fling.Status;
import tv.matchstick.fling.images.WebImage;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.connectsdk.core.Util;
import com.connectsdk.discovery.DiscoveryManager;
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
import com.connectsdk.service.sessions.LaunchSession;
import com.connectsdk.service.sessions.LaunchSession.LaunchSessionType;
import com.connectsdk.service.sessions.FlintWebAppSession;
import com.connectsdk.service.sessions.WebAppSession;

public class FlintService extends DeviceService implements MediaPlayer, MediaControl, VolumeControl, WebAppLauncher {
	private static final String TAG = "FlintService";
	
	// @cond INTERNAL
	
	public interface ConnectionListener {
		void onConnected();
	};
	
	public static final String ID = "MatchStick";

	public final static String PLAY_STATE = "PlayState";
	public final static String VOLUME = "Volume";
	public final static String MUTE = "Mute";
	
	// @endcond
	
	String currentAppId;

	FlingManager mFlingManager;
    FlingListener mFlingClientListener;
    ConnectionCallbacks mConnectionCallbacks;
    ConnectionFailedListener mConnectionFailedListener;
    
    FlingDevice flingDevice;
    RemoteMediaPlayer mMediaPlayer;
    
    Map<String, FlintWebAppSession> sessions;
	List<URLServiceSubscription<?>> subscriptions;
    
    boolean isConnected = false;
    
	// Queue of commands that should be sent once register is complete
    LinkedHashSet<ConnectionListener> commandQueue = new LinkedHashSet<ConnectionListener>();
	
    protected static final double VOLUME_INCREMENT = 0.05;
    
	public FlintService(ServiceDescription serviceDescription, ServiceConfig serviceConfig) {
		super(serviceDescription, serviceConfig);
		
		mFlingClientListener = new FlingListener();
        mConnectionCallbacks = new ConnectionCallbacks();
        mConnectionFailedListener = new ConnectionFailedListener();
        
        sessions = new HashMap<String, FlintWebAppSession>();
        subscriptions = new ArrayList<URLServiceSubscription<?>>();
	}

	@Override
	public String getServiceName() {
		return ID;
	}

	public static JSONObject discoveryParameters() {
		JSONObject params = new JSONObject();
		
		try {
			params.put("serviceId", ID);
			params.put("filter",  "MatchStick");
		} catch (JSONException e) {
			e.printStackTrace();
		}

		return params;
	}
	
	
	@Override
	public void connect() {
		Log.e(TAG, "connect!");
		if (mFlingManager != null) {
			if ((mFlingManager.isConnected()) || (mFlingManager.isConnecting())) {
				Log.e(TAG, "ignore connect!connected?[" + mFlingManager.isConnected() + "]isConnecting[" + mFlingManager.isConnecting() + "][");
				return;
			}
			
	        mFlingManager.connect();
		}
	}

	@Override
	public void disconnect() {
		Log.e(TAG, "disconnect!");
		if (mFlingManager.isConnected()) 
			mFlingManager.disconnect();
		isConnected = false;
	}
	
	@Override
	public MediaControl getMediaControl() {
		return this;
	}

	@Override
	public CapabilityPriorityLevel getMediaControlCapabilityLevel() {
		return CapabilityPriorityLevel.NORMAL;
	}

	@Override
	public void play(final ResponseListener<Object> listener) {
		Log.e(TAG, "play!");
		if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to play", null));
			return;
		}
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				try {
					mMediaPlayer.play(mFlingManager);
					
					if (listener != null) 
						listener.onSuccess(null);
				} catch (Exception e) {
					// NOTE: older versions of Play Services required a check for IOException
					Log.w("Connect SDK", "Unable to play", e);
				}
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void pause(final ResponseListener<Object> listener) {
		Log.e(TAG, "pause!");
        if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to pause", null));
            return;
        }
        
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
		        try {
					mMediaPlayer.pause(mFlingManager);
					
					if (listener != null) 
						listener.onSuccess(null);
				} catch (Exception e) {
					// NOTE: older versions of Play Services required a check for IOException
		            Log.w("Connect SDK", "Unable to pause", e);
				}
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void stop(final ResponseListener<Object> listener) {
		Log.e(TAG, "stop!");
		if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to stop", null));
			return;
		}

		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				try {
					mMediaPlayer.stop(mFlingManager);

					if (listener != null) 
						listener.onSuccess(null);
				} catch (Exception e) {
					// NOTE: older versions of Play Services required a check for IOException
					Log.w("Connect SDK", "Unable to stop");
				}
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void rewind(ResponseListener<Object> listener) {
		if (listener != null) 
			Util.postError(listener, ServiceCommandError.notSupported());
	}

	@Override
	public void fastForward(ResponseListener<Object> listener) {
		if (listener != null) 
			Util.postError(listener, ServiceCommandError.notSupported());
	}
	
	@Override
	public void seek(final long position, final ResponseListener<Object> listener) {
		Log.e(TAG, "seek!");
		if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to seek", null));
			return;
		}
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				int resumeState = RemoteMediaPlayer.RESUME_STATE_UNCHANGED;
				
		        mMediaPlayer.seek(mFlingManager, position, resumeState).setResultCallback(
		                new ResultCallback<MediaChannelResult>() {

		                	@Override
		                    public void onResult(MediaChannelResult result) {
		                        Status status = result.getStatus();
		                        if (status.isSuccess()) {
		                        	Log.d("Connect SDK", "Seek Successfull");
		                        	Util.postSuccess(listener, result);
		                        } else {
		                            Log.w("Connect SDK", "Unable to seek: " + status.getStatusCode());
		                            Util.postError(listener, new ServiceCommandError(status.getStatusCode(), status.getStatusMessage(), status));
		                        }
		                    }

		                });
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void getDuration(final DurationListener listener) {
		Log.e(TAG, "getDuration!");
		if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to get duration", null));
			return;
		}
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				Util.postSuccess(listener, mMediaPlayer.getStreamDuration());
			}
		};
		
		runCommand(connectionListener);
	}
	
	@Override
	public void getPosition(final PositionListener listener) {
		Log.e(TAG, "getPosition!");
		if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to get position", null));
			return;
		}
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				Util.postSuccess(listener, mMediaPlayer.getApproximateStreamPosition());
			}
		};
		
		runCommand(connectionListener);
	}
	
	
	@Override
	public MediaPlayer getMediaPlayer() {
		return this;
	}

	@Override
	public CapabilityPriorityLevel getMediaPlayerCapabilityLevel() {
		return CapabilityPriorityLevel.NORMAL;
	}
	
	private void attachMediaPlayer() {
		Log.e(TAG, "attachMediaPlayer!");
        if (mMediaPlayer != null) {
            Log.e(TAG, "mMediaPlayer!= null, ignore attachMediaPlayer!but run reattachMediaPlayer???");
            reattachMediaPlayer();
            return;
        }
        mMediaPlayer = new RemoteMediaPlayer();
        mMediaPlayer.setOnStatusUpdatedListener(new RemoteMediaPlayer.OnStatusUpdatedListener() {

            @Override
            public void onStatusUpdated() {
                if (subscriptions.size() > 0) {
                	for (URLServiceSubscription<?> subscription: subscriptions) {
                		if (subscription.getTarget().equalsIgnoreCase(PLAY_STATE)) {
    						for (int i = 0; i < subscription.getListeners().size(); i++) {
    							@SuppressWarnings("unchecked")
    							ResponseListener<Object> listener = (ResponseListener<Object>) subscription.getListeners().get(i);
    							PlayStateStatus status = convertPlayerStateToPlayStateStatus(mMediaPlayer.getMediaStatus().getPlayerState());
    							Util.postSuccess(listener, status);
    						}
                		}
                	}
                }
            }
        });

        mMediaPlayer.setOnMetadataUpdatedListener(new RemoteMediaPlayer.OnMetadataUpdatedListener() {
            @Override
            public void onMetadataUpdated() {
                Log.d("Connect SDK", "MediaControlChannel.onMetadataUpdated");
            }
        });
        
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
		        try {
		            Log.e("FlintService", "attachMediaPlayer !setMessageReceivedCallbacks:" + mMediaPlayer.getNamespace());
		            Fling.FlingApi.setMessageReceivedCallbacks(mFlingManager, mMediaPlayer.getNamespace(),
		                    mMediaPlayer);
		        } catch (IOException e) {
		            Log.w("Connect SDK", "Exception while creating media channel", e);
		        }
			}
		};
		
		runCommand(connectionListener);
    }
	
	@SuppressWarnings("unused")
	private void reattachMediaPlayer() {
		Log.e(TAG, "reattachMediaPlayer!");
        if (mMediaPlayer != null) {
    		ConnectionListener connectionListener = new ConnectionListener() {
    			
    			@Override
    			public void onConnected() {
    	            try {
    	                Log.e("FlintSerivce", "reattachMediaPlayer: setMessageReceivedCallbacks:" + mMediaPlayer.getNamespace());
    	                Fling.FlingApi.setMessageReceivedCallbacks(mFlingManager, mMediaPlayer.getNamespace(),
    	                        mMediaPlayer);
    	            } catch (IOException e) {
    	                Log.w("Connect SDK", "Exception while launching application", e);
    	            }
    			}
    		};
    		
    		runCommand(connectionListener);
        }
    }

    private void detachMediaPlayer() {
    	Log.e(TAG, "detachMediaPlayer!");
        if (mMediaPlayer != null) {
    		ConnectionListener connectionListener = new ConnectionListener() {
    			
    			@Override
    			public void onConnected() {
    	            try {
    	                Fling.FlingApi.removeMessageReceivedCallbacks(mFlingManager,
    	                        mMediaPlayer.getNamespace());
    	            } catch (IOException e) {
    	                Log.w("Connect SDK", "Exception while launching application", e);
    	            }
    	            mMediaPlayer = null;
    			}
    		};
    		
    		runCommand(connectionListener);
        }
    }
	
	private void playMediaInternal(final MediaInfo media, final LaunchListener listener) {
		Log.e(TAG, "playMediaInternal!");
        if (media == null) {
        	Util.postError(listener, new ServiceCommandError(500, "MediaInfo is null", null));
            return;
        }
        
        if (mMediaPlayer == null) {
        	Util.postError(listener, new ServiceCommandError(500, "Trying to play a video with no active media session", null));
            return;
        }

        if (mFlingManager == null) {
        	Util.postError(listener, new ServiceCommandError(500, "Fling Manager is null", null));
            return;
        }

		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
		        mMediaPlayer.load(mFlingManager, media, true).setResultCallback(
		    			new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {

		    				@Override
		    				public void onResult(MediaChannelResult result) {
		    					if (result.getStatus().isSuccess()) {
		    						LaunchSession launchSession = LaunchSession.launchSessionForAppId("~samplemediaplayer");
		    						launchSession.setService(FlintService.this);
		    						launchSession.setSessionType(LaunchSessionType.Media);

		    						Util.postSuccess(listener, new MediaLaunchObject(launchSession, FlintService.this));
		    					} else {
		    			        	Util.postError(listener, new ServiceCommandError(result.getStatus().getStatusCode(), result.getStatus().getStatusMessage(), result));
		    					}
		    				}
		    			});
			}
		};
		
		runCommand(connectionListener);
    }

	@Override
	public void displayImage(final String url, final String mimeType, final String title,
			final String description, final String iconSrc, final LaunchListener listener) {
		Log.e(TAG, "displayImage!");
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				String mediaAppId = "http://openflint.github.io/simple-player-demo/receiver/index.html";
				
				MediaMetadata mMediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO);
				mMediaMetadata.putString(MediaMetadata.KEY_TITLE, title);
				//mMediaMetadata.putString(MediaMetadata.KEY_SUBTITLE, description);

				if (iconSrc != null) {
					Uri iconUri = Uri.parse(iconSrc);
					WebImage image = new WebImage(iconUri, 100, 100);
					mMediaMetadata.addImage(image);
				}
				
				MediaInfo mediaInfo = new MediaInfo.Builder(url)
					    .setContentType(mimeType)
					    .setStreamType(MediaInfo.STREAM_TYPE_NONE)
					    .setMetadata(mMediaMetadata)
					              .build();
				
				boolean relaunchIfRunning = false;
				
				/*
				if (Fling.FlingApi.getApplicationStatus(mFlingManager) != null && mediaAppId.equals(currentAppId)) {
					relaunchIfRunning = false;
				}
				else {
					relaunchIfRunning = true;
				}
                */
				
				Fling.FlingApi.launchApplication(mFlingManager, mediaAppId, relaunchIfRunning)
		    		.setResultCallback(new ApplicationConnectionResultCallback(mediaInfo, listener));
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void playMedia(final String url, final String mimeType, final String title,
			final String description, final String iconSrc, final boolean shouldLoop,
			final LaunchListener listener) {
		Log.e(TAG, "playMedia!");
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				String mediaAppUrl = "http://openflint.github.io/simple-player-demo/receiver/index.html";

				MediaMetadata mMediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
				mMediaMetadata.putString(MediaMetadata.KEY_TITLE, title);
				//mMediaMetadata.putString(MediaMetadata.KEY_SUBTITLE, description);
/*
				if (iconSrc != null) {
					Uri iconUri = Uri.parse(iconSrc);
					WebImage image = new WebImage(iconUri, 100, 100);
					mMediaMetadata.addImage(image);
				}
*/
				MediaInfo mediaInfo = new MediaInfo.Builder(url)
					    .setContentType(mimeType)
					    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
					    .setMetadata(mMediaMetadata)
					              .build();
				
				boolean relaunchIfRunning = true;

				/*
				Log.e(TAG, "playMedia![" + Fling.FlingApi.getApplicationStatus(mFlingManager) + "][" + mediaAppId +"][" + currentAppId + "]");
				
				if (Fling.FlingApi.getApplicationStatus(mFlingManager) != null && mediaAppId.equals(currentAppId)) {
					Log.e(TAG, "relaunchIfRunning set to false");
					relaunchIfRunning = false;
				}
				else {
					Log.e(TAG, "relaunchIfRunning set to true");
					relaunchIfRunning = true;
				}
				*/
				

				Log.e("MatchstickService", "relaunchIfRunning:" + relaunchIfRunning);
		        Fling.FlingApi.launchApplication(mFlingManager, mediaAppUrl, relaunchIfRunning)
		    		.setResultCallback(new ApplicationConnectionResultCallback(mediaInfo, listener));
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void closeMedia(final LaunchSession launchSession, final ResponseListener<Object> listener) {
		Log.e(TAG, "closeMedia!");
		if (!mFlingManager.isConnected()) {
			Util.postError(listener, new ServiceCommandError(-1, "FlingService not connected", null));
			return;
		}
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				Fling.FlingApi.stopApplication(mFlingManager).setResultCallback(new ResultCallback<Status>() {
					
					@Override
					public void onResult(Status result) {
						if (result.isSuccess()) {
							((FlintService) launchSession.getService()).detachMediaPlayer();
							
							Util.postSuccess(listener, result);
						} else {
							Util.postError(listener, new ServiceCommandError(result.getStatusCode(), result.getStatusMessage(), result));
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
		return CapabilityPriorityLevel.NORMAL;
	}

	@Override
	public void launchWebApp(final String webAppId, final WebAppSession.LaunchListener listener) {
		launchWebApp(webAppId, true, listener);
	}
	
	@Override
	public void joinWebApp(final LaunchSession webAppLaunchSession, final WebAppSession.LaunchListener listener) {
		final ResultCallback<Fling.ApplicationConnectionResult> resultCallback = new ResultCallback<Fling.ApplicationConnectionResult>() {

			@Override
			public void onResult(Fling.ApplicationConnectionResult result) {
				Status status = result.getStatus();

				if (status.isSuccess()) {
					final FlintWebAppSession webAppSession;
					
					if (sessions.containsKey(webAppLaunchSession.getAppId())) {
						webAppSession = sessions.get(webAppLaunchSession.getAppId());
					}
					else {
						webAppSession = new FlintWebAppSession(webAppLaunchSession, FlintService.this);
						sessions.put(webAppLaunchSession.getAppId(), webAppSession);
					}
					
					webAppSession.join(new ResponseListener<Object>() {
						
						@Override
						public void onError(ServiceCommandError error) {
							Util.postError(listener, error);
						}
						
						@Override
						public void onSuccess(Object object) {
							Util.postSuccess(listener, webAppSession);
						}
					});
				}
				else {
					Util.postError(listener, new ServiceCommandError(status.getStatusCode(), status.getStatusMessage(), result));
				}
			}
		};
	
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				Fling.FlingApi.joinApplication(mFlingManager, webAppLaunchSession.getAppId()).setResultCallback(resultCallback);
			}
		};
		
		runCommand(connectionListener);
	}
	
	@Override
	public void joinWebApp(String webAppId, WebAppSession.LaunchListener listener) {
		LaunchSession launchSession = LaunchSession.launchSessionForAppId(webAppId);
		launchSession.setSessionType(LaunchSessionType.WebApp);
		launchSession.setService(this);
		
		joinWebApp(launchSession, listener);
	}

	@Override
	public void launchWebApp(String webAppId, JSONObject params, WebAppSession.LaunchListener listener) {
		launchWebApp(webAppId, true, listener);
	}
	
	@Override
	public void launchWebApp(final String webAppId, final boolean relaunchIfRunning, final WebAppSession.LaunchListener listener) {
		Log.d(TAG, "FlintService::launchWebApp() | webAppId = " + webAppId);
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				Fling.FlingApi.launchApplication(mFlingManager, webAppId, relaunchIfRunning)
				.setResultCallback(
						new ResultCallback<Fling.ApplicationConnectionResult>() {

							@Override
							public void onResult(Fling.ApplicationConnectionResult result) {
								Status status = result.getStatus();

								if (status.isSuccess()) {
									currentAppId = webAppId;
									
		    						LaunchSession launchSession = LaunchSession.launchSessionForAppId(webAppId);
		    						launchSession.setService(FlintService.this);
		    						launchSession.setSessionType(LaunchSessionType.WebApp);

		    						FlintWebAppSession webAppSession = sessions.get(webAppId);
		    						
		    						if (webAppSession == null) {
		    							webAppSession = new FlintWebAppSession(launchSession, FlintService.this);
		    							sessions.put(webAppId, webAppSession);
		    						}
		    						
		    						Util.postSuccess(listener, webAppSession);
								}
								else {
									Util.postError(listener, new ServiceCommandError(status.getStatusCode(), status.getStatusMessage(), result));
								}
							}
						});
			}
		};
		
		runCommand(connectionListener);
	}
	
	@Override
	public void launchWebApp(String webAppId, JSONObject params, boolean relaunchIfRunning, WebAppSession.LaunchListener listener) {
		launchWebApp(webAppId, relaunchIfRunning, listener);
	}
	
	@Override
	public void closeWebApp(LaunchSession launchSession, final ResponseListener<Object> listener) {
		final ResultCallback<Status> resultCallback = new ResultCallback<Status>() {
			@Override
			public void onResult(final Status result) {
				if (result.isSuccess())
					Util.postSuccess(listener, null);
				else
					Util.postError(listener, new ServiceCommandError(0, "TV Error", null));
			}
		};
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				Fling.FlingApi.stopApplication(mFlingManager).setResultCallback(resultCallback);
			}
		};
		
		runCommand(connectionListener);
	}
	
	
	@Override
	public VolumeControl getVolumeControl() {
		return this;
	}

	@Override
	public CapabilityPriorityLevel getVolumeControlCapabilityLevel() {
		return CapabilityPriorityLevel.NORMAL;
	}

	@Override
	public void volumeUp(final ResponseListener<Object> listener) {
		getVolume(new VolumeListener() {
			
			@Override
			public void onSuccess(final Float volume) {
	    		ConnectionListener connectionListener = new ConnectionListener() {
	    			
	    			@Override
	    			public void onConnected() {
						try {
				        	float newVolume; 
				        	if (volume + VOLUME_INCREMENT >= 1.0) {
				        		newVolume = 1;
				        	}
				        	else {
				        		newVolume = (float) (volume + VOLUME_INCREMENT);
				        	}

							Fling.FlingApi.setVolume(mFlingManager, newVolume);
						} catch (IllegalArgumentException e) {
							e.printStackTrace();
						} catch (IllegalStateException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
	    			}
	    		};
	    		
	    		runCommand(connectionListener);
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
				ConnectionListener connectionListener = new ConnectionListener() {
					
					@Override
					public void onConnected() {
			        	float newVolume; 
			        	if (volume - VOLUME_INCREMENT <= 0) {
			        		newVolume = 0;
			        	}
			        	else {
			        		newVolume = (float) (volume - VOLUME_INCREMENT);
			        	}
			        	
						try {
							Fling.FlingApi.setVolume(mFlingManager, newVolume);
						} catch (IllegalArgumentException e) {
							e.printStackTrace();
						} catch (IllegalStateException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				};
				
				runCommand(connectionListener);
			}
			
			@Override
			public void onError(ServiceCommandError error) {
					Util.postError(listener, error);
			}
		});
	}

	@Override
	public void setVolume(final float volume, ResponseListener<Object> listener) {
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				try {
					Fling.FlingApi.setVolume(mFlingManager, volume);
				} catch (IllegalArgumentException e) {
					e.printStackTrace();
				} catch (IllegalStateException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}			
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void getVolume(final VolumeListener listener) {
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
		        float volume = (float) Fling.FlingApi.getVolume(mFlingManager);
		        Util.postSuccess(listener, volume);		
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void setMute(final boolean isMute, ResponseListener<Object> listener) {
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
		        try {
					Fling.FlingApi.setMute(mFlingManager, isMute);
				} catch (IllegalStateException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public void getMute(final MuteListener listener) {
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				boolean isMute = Fling.FlingApi.isMute(mFlingManager);
				Util.postSuccess(listener, isMute);
			}
		};
		
		runCommand(connectionListener);
	}

	@Override
	public ServiceSubscription<VolumeListener> subscribeVolume(VolumeListener listener) {
		URLServiceSubscription<VolumeListener> request = new URLServiceSubscription<VolumeListener>(this, VOLUME, null, null);
		request.addListener(listener);
		addSubscription(request);

		return request;
	}

	@Override
	public ServiceSubscription<MuteListener> subscribeMute(MuteListener listener) {
		URLServiceSubscription<MuteListener> request = new URLServiceSubscription<MuteListener>(this, MUTE, null, null);
		request.addListener(listener);
		addSubscription(request);

		return request;
	}
	
	@Override
	protected void updateCapabilities() {
		List<String> capabilities = new ArrayList<String>();
		
		for (String capability : MediaPlayer.Capabilities) { capabilities.add(capability); }
		for (String capability : VolumeControl.Capabilities) { capabilities.add(capability); }
		
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
	
    private class FlingListener extends Fling.Listener {
        @Override
        public void onApplicationDisconnected(int statusCode) {
            Log.d("Connect SDK", "Fling.Listener.onApplicationDisconnected: " + statusCode);
            
            if (currentAppId == null)
            	return;
            
            FlintWebAppSession webAppSession = sessions.get(currentAppId);

            if (webAppSession == null)
            	return;

            Log.e(TAG, "handleAppClose![" + currentAppId + "]");
            webAppSession.handleAppClose();
        }

		@Override
		public void onApplicationStatusChanged() {
			ConnectionListener connectionListener = new ConnectionListener() {

				@Override
				public void onConnected() {
					ApplicationMetadata applicationMetadata = Fling.FlingApi.getApplicationMetadata(mFlingManager);
/*
					if (applicationMetadata != null)
						currentAppId = applicationMetadata.getApplicationId();
						*/
				}
			};
			
			runCommand(connectionListener);
		}

		@Override
		public void onVolumeChanged() {
            if (subscriptions.size() > 0) {
            	for (URLServiceSubscription<?> subscription: subscriptions) {
            		if (subscription.getTarget().equalsIgnoreCase(VOLUME)) {
						for (int i = 0; i < subscription.getListeners().size(); i++) {
							@SuppressWarnings("unchecked")
							final ResponseListener<Object> listener = (ResponseListener<Object>) subscription.getListeners().get(i);
							
							ConnectionListener connectionListener = new ConnectionListener() {
								
								@Override
								public void onConnected() {
							        try {
								        float volume = (float) Fling.FlingApi.getVolume(mFlingManager);
								        Util.postSuccess(listener, volume);
									} catch (IllegalStateException e) {
										e.printStackTrace();
									}
								}
							};
							
							runCommand(connectionListener);
						}
            		}
            		else if (subscription.getTarget().equalsIgnoreCase(MUTE)) {
						for (int i = 0; i < subscription.getListeners().size(); i++) {
							@SuppressWarnings("unchecked")
							final ResponseListener<Object> listener = (ResponseListener<Object>) subscription.getListeners().get(i);
							
							ConnectionListener connectionListener = new ConnectionListener() {
								
								@Override
								public void onConnected() {
							        try {
										boolean isMute = Fling.FlingApi.isMute(mFlingManager);
										Util.postSuccess(listener, isMute);
									} catch (IllegalStateException e) {
										e.printStackTrace();
									}
								}
							};
							
							runCommand(connectionListener);
						}
            		}
            	}
            }
		}
    }
    
    private class ConnectionCallbacks implements FlingManager.ConnectionCallbacks {
        @Override
        public void onConnectionSuspended(final int cause) {
            Log.d(TAG, "ConnectionCallbacks.onConnectionSuspended");
            
            disconnect();
            detachMediaPlayer();
            
            Util.runOnUI(new Runnable() {
				@Override
				public void run() {
					if (listener != null) {
						ServiceCommandError error;
			            
			            switch (cause) {
			            	case FlingManager.ConnectionCallbacks.CAUSE_NETWORK_LOST:
			            		error = new ServiceCommandError(cause, "Peer device connection was lost", null);
			            		break;
			            	
			            	case FlingManager.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED:
			            		error = new ServiceCommandError(cause, "The service has been killed", null);
			            		break;
			            	
			            	default:
			            		error = new ServiceCommandError(cause, "Unknown connection error", null);
			            }
			            
						listener.onDisconnect(FlintService.this, error);
					}
				}
			});
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "ConnectionCallbacks.onConnected");
            isConnected = true;

    		if (!commandQueue.isEmpty()) {
    			LinkedHashSet<ConnectionListener> tempHashSet = new LinkedHashSet<ConnectionListener>(commandQueue);
    			for (ConnectionListener listener : tempHashSet) {
    				listener.onConnected();
    				commandQueue.remove(listener);
    			}
    		}
    		
    		reportConnected(true);
        }
    }

    private class ConnectionFailedListener implements FlingManager.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed(final ConnectionResult result) {
            Log.d(TAG, "ConnectionFailedListener.onConnectionFailed");
            
            detachMediaPlayer();
            isConnected = false;
            
            Util.runOnUI(new Runnable() {
				
				@Override
				public void run() {
					if (listener != null) {
						ServiceCommandError error = new ServiceCommandError(result.getErrorCode(), "Failed to connect to Matchstick Fling device", result);
						
						listener.onConnectionFailure(FlintService.this, error);
					}
				}
			});
        }
    }
    
    private final class ApplicationConnectionResultCallback implements
    		ResultCallback<Fling.ApplicationConnectionResult> {
    	
    	MediaInfo mediaInfo;
    	LaunchListener listener;
    	
    	public ApplicationConnectionResultCallback(MediaInfo mediaInfo, LaunchListener listener) {
    		this.mediaInfo = mediaInfo;
    		this.listener = listener;
    	}

    	@Override
    	public void onResult(ApplicationConnectionResult result) {
    		Status status = result.getStatus();
    		Log.d(TAG, "ApplicationConnectionResultCallback.onResult: statusCode: " + status.getStatusCode());
    
    		if (status.isSuccess()) {
    			/*ApplicationMetadata applicationMetadata = result.getApplicationMetadata();
    			currentAppId = applicationMetadata.getApplicationId();
    			
    			String sessionId = result.getSessionId();
    			String applicationStatus = result.getApplicationStatus();
    			boolean wasLaunched = result.getWasLaunched();
    			Log.d(TAG, "application name: " + applicationMetadata.getName()
    					+ ", status: " + applicationStatus + ", sessionId: " + sessionId
    					+ ", wasLaunched: " + wasLaunched);
                */
    		    currentAppId = Fling.FlingApi.getApplicationId();
    			attachMediaPlayer();
    			playMediaInternal(mediaInfo, listener);
    			
				LaunchSession launchSession = LaunchSession.launchSessionForAppId(Fling.FlingApi.getApplicationId());
				launchSession.setService(FlintService.this);
				launchSession.setSessionType(LaunchSessionType.Media);

				FlintWebAppSession webAppSession = sessions.get(Fling.FlingApi.getApplicationId());
				
				if (webAppSession == null) {
					webAppSession = new FlintWebAppSession(launchSession, FlintService.this);
					sessions.put(Fling.FlingApi.getApplicationId(), webAppSession);
				}
    		} else {
    			Util.postError(listener, new ServiceCommandError(status.getStatusCode(), status.getStatusMessage(), status));
    		}
    	}
    }
    
    public FlingDevice getDevice() {
    	return flingDevice;
    }
    
    @Override
    public void getPlayState(PlayStateListener listener) {
		if (mMediaPlayer == null) {
			Util.postError(listener, new ServiceCommandError(0, "Unable to get play state", null));
			return;
		}
		
		PlayStateStatus status = convertPlayerStateToPlayStateStatus(mMediaPlayer.getMediaStatus().getPlayerState());
		Util.postSuccess(listener, status);
    }
    
    private PlayStateStatus convertPlayerStateToPlayStateStatus(int playerState) {
		PlayStateStatus status = PlayStateStatus.Unknown;
		
		switch (playerState) {
    		case MediaStatus.PLAYER_STATE_BUFFERING:
    			status = PlayStateStatus.Buffering;
    			break;
    		case MediaStatus.PLAYER_STATE_IDLE:
    			status = PlayStateStatus.Idle;
    			break;
    		case MediaStatus.PLAYER_STATE_PAUSED:
    			status = PlayStateStatus.Paused;
    			break;
    		case MediaStatus.PLAYER_STATE_PLAYING:
    			status = PlayStateStatus.Playing;
    			break;
    		case MediaStatus.PLAYER_STATE_UNKNOWN:
    		default:
    			status = PlayStateStatus.Unknown;
    			break;
		}
		
		return status;
    }
    
    public FlingManager getApiClient() {
    	return mFlingManager;
    }
    
    @Override
    public void setServiceDescription(ServiceDescription serviceDescription) {
    	super.setServiceDescription(serviceDescription);
		
		if (serviceDescription instanceof FlintServiceDescription)
			this.flingDevice = ((FlintServiceDescription)serviceDescription).getFlingDevice();
		
        if (this.flingDevice != null) {
        	Fling.FlingOptions.Builder apiOptionsBuilder = Fling.FlingOptions
                      	.builder(flingDevice, mFlingClientListener);

        	mFlingManager = new FlingManager.Builder(DiscoveryManager.getInstance().getContext())
                              	.addApi(Fling.API, apiOptionsBuilder.build())
                              	.addConnectionCallbacks(mConnectionCallbacks)
                              	.addOnConnectionFailedListener(mConnectionFailedListener)
                              	.build();
        }
    }
    
    //////////////////////////////////////////////////
    //		Device Service Methods
    //////////////////////////////////////////////////
    @Override
    public boolean isConnectable() {
    	return true;
    }
    
    @Override
    public boolean isConnected() {
    	//return isConnected;
    	return mFlingManager!= null ? mFlingManager.isConnected() : false;
    }

	@Override
	public ServiceSubscription<PlayStateListener> subscribePlayState(PlayStateListener listener) {
		URLServiceSubscription<PlayStateListener> request = new URLServiceSubscription<PlayStateListener>(this, PLAY_STATE, null, null);
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
	
    public void runCommand(ConnectionListener connectionListener) {
		if (mFlingManager.isConnected()) {
			connectionListener.onConnected();
		}
		else {
			connect();
			commandQueue.add(connectionListener);
		}
    }

	public List<URLServiceSubscription<?>> getSubscriptions() {
		return subscriptions;
	}

	public void setSubscriptions(List<URLServiceSubscription<?>> subscriptions) {
		this.subscriptions = subscriptions;
	}
}
