/*
 * MatchstickWebAppSession
 * Connect SDK
 * 
 * Copyright (c) 2014 LG Electronics.
 * Created by Hyun Kook Khang on 07 Mar 2014
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

package com.connectsdk.service.sessions;

import java.io.IOException;

import org.json.JSONObject;

import android.util.Log;

import com.connectsdk.core.Util;
import com.connectsdk.service.FlintService;
import com.connectsdk.service.FlintService.ConnectionListener;
import com.connectsdk.service.FlintServiceChannel;
import com.connectsdk.service.DeviceService;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommandError;
import com.connectsdk.service.command.URLServiceSubscription;

import tv.matchstick.fling.Fling;
import tv.matchstick.fling.Fling.MessageReceivedCallback;
import tv.matchstick.fling.FlingDevice;
import tv.matchstick.fling.ResultCallback;
import tv.matchstick.fling.Status;

public class FlintWebAppSession extends WebAppSession {
	protected FlintService service;
    protected FlintServiceChannel mFlingServiceChannel;
	
	public FlintWebAppSession(LaunchSession launchSession, DeviceService service) {
		super(launchSession, service);
		
		this.service = (FlintService) service;
	}
	
	@Override
	public void connect(final ResponseListener<Object> listener) {
		if (mFlingServiceChannel != null) {
			disconnectFromWebApp(launchSession);
		}
		
		mFlingServiceChannel = new FlintServiceChannel(launchSession.getAppId(), this);
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				try {
				    Log.e("FlintWebAppSession", "setMessageReceivedCallbacks:" + mFlingServiceChannel.getNamespace());
					Fling.FlingApi.setMessageReceivedCallbacks(service.getApiClient(),
							mFlingServiceChannel.getNamespace(),
							mFlingServiceChannel);
					
					Util.postSuccess(listener, null);
				} catch (IOException e) {
					Util.postError(listener, new ServiceCommandError(0, "Failed to create channel", null));
				}
			}
		};
		
		service.runCommand(connectionListener);
	}
	
	@Override
	public void join(final ResponseListener<Object> connectionListener) {
		connect(connectionListener);
	}
	
	public MessageReceivedCallback messageReceivedCallback = new MessageReceivedCallback() {
		
		@Override
		public void onMessageReceived(FlingDevice castDevice, String namespace, String message) {
			
		}
	};
	
	public void handleAppClose() {
      	for (URLServiceSubscription<?> subscription: service.getSubscriptions()) {
    		if (subscription.getTarget().equalsIgnoreCase("PlayState")) {
				for (int i = 0; i < subscription.getListeners().size(); i++) {
					@SuppressWarnings("unchecked")
					ResponseListener<Object> listener = (ResponseListener<Object>) subscription.getListeners().get(i);
					Util.postSuccess(listener, PlayStateStatus.Idle);
				}
    		}
    	}

		if (getWebAppSessionListener() != null) { 
			getWebAppSessionListener().onWebAppSessionDisconnect(this);
		}
	}
	
	public void disconnectFromWebApp(LaunchSession launchSession) {
		if (service.getApiClient() == null) 
			return;

		if (mFlingServiceChannel == null) 
			return;

		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
				try {
					Fling.FlingApi.removeMessageReceivedCallbacks(service.getApiClient(), mFlingServiceChannel.getNamespace());
					mFlingServiceChannel = null;
				} catch (IOException e) {
			        Log.e("Connect SDK", "Exception while removing application", e);
				}
			}
		};
		
		service.runCommand(connectionListener);
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
	public void sendMessage(final String message, final ResponseListener<Object> listener) {
		if (message == null) {
			Util.postError(listener, new ServiceCommandError(0, "Cannot send null message", null));
	        return;
	    }

	    if (mFlingServiceChannel == null) {
	    	Util.postError(listener, new ServiceCommandError(0, "Must connect web app first", null));
	        return;
	    }
		Log.d(Util.T, "CastService::sendMessage() | mCastServiceChannel.getNamespace() = " + mFlingServiceChannel.getNamespace());
		
		ConnectionListener connectionListener = new ConnectionListener() {
			
			@Override
			public void onConnected() {
			    Fling.FlingApi.sendMessage(service.getApiClient(), mFlingServiceChannel.getNamespace(), message)
				.setResultCallback(new ResultCallback<Status>() {
				
				@Override
				public void onResult(Status result) {
					if (result.isSuccess()) {
						Log.d("Connect SDK", "Sending message succeeded");
						Util.postSuccess(listener, result);
					}
					else {
						Log.e("Connect SDK", "Sending message failed");
						Util.postError(listener, new ServiceCommandError(result.getStatusCode(), result.toString(), result));
					}
				}
			});
			}
		};
		
		service.runCommand(connectionListener);
	}
	
	@Override
	public void sendMessage(JSONObject message, ResponseListener<Object> listener) {
		sendMessage(message.toString(), listener);
	}
	
	@Override
	public void close(ResponseListener<Object> listener) {
		launchSession.close(listener);
	}
	
	/****************
	 * Media Player *
	 ****************/
	@Override
	public MediaPlayer getMediaPlayer() {
		return this;
	}
	
	@Override
	public CapabilityPriorityLevel getMediaPlayerCapabilityLevel() {
		return CapabilityPriorityLevel.HIGH;
	}
	
	@Override
	public void playMedia( String url, String mimeType, String title, String description, String iconSrc, boolean shouldLoop, MediaPlayer.LaunchListener listener) {
		service.playMedia(url, mimeType, title, description, iconSrc, shouldLoop, listener);
	}
	
	@Override
	public void closeMedia(LaunchSession launchSession, ResponseListener<Object> listener) {
		close(listener);
	}
}
