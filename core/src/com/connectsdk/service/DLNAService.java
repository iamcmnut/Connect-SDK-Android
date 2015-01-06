/*
 * DLNAService
 * Connect SDK
 * 
 * Copyright (c) 2014 LG Electronics.
 * Created by Hyun Kook Khang on 19 Jan 2014
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
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;

import com.connectsdk.core.ImageInfo;
import com.connectsdk.core.MediaInfo;
import com.connectsdk.core.Util;
import com.connectsdk.core.upnp.service.Service;
import com.connectsdk.discovery.DiscoveryManager;
import com.connectsdk.etc.helper.DeviceServiceReachability;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.capability.VolumeControl;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommand;
import com.connectsdk.service.command.ServiceCommandError;
import com.connectsdk.service.command.ServiceSubscription;
import com.connectsdk.service.command.URLServiceSubscription;
import com.connectsdk.service.config.ServiceConfig;
import com.connectsdk.service.config.ServiceDescription;
import com.connectsdk.service.sessions.LaunchSession;
import com.connectsdk.service.sessions.LaunchSession.LaunchSessionType;
import com.connectsdk.service.upnp.DLNAHttpServer;
import com.connectsdk.service.upnp.DLNAMediaInfoParser;

public class DLNAService extends DeviceService implements MediaControl, MediaPlayer, VolumeControl {
	public static final String ID = "DLNA";

	protected static final String SUBSCRIBE = "SUBSCRIBE";
	protected static final String UNSUBSCRIBE = "UNSUBSCRIBE";
	
	public static final String AV_TRANSPORT_URN = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String CONNECTION_MANAGER_URN = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String RENDERING_CONTROL_URN = "urn:schemas-upnp-org:service:RenderingControl:1";

    protected static final String AV_TRANSPORT = "AVTransport";
	protected static final String CONNECTION_MANAGER = "ConnectionManager";
	protected static final String RENDERING_CONTROL = "RenderingControl";
	protected static final String GROUP_RENDERING_CONTROL = "GroupRenderingControl";

	public final static String PLAY_STATE = "playState";
	
    Context context;

    String avTransportURL, renderingControlURL;
	HttpClient httpClient;
	
	DLNAHttpServer httpServer;
	
	Map<String, String> SIDList;
	Timer resubscriptionTimer;
	
	private static int TIMEOUT = 300;

	interface PositionInfoListener {
		public void onGetPositionInfoSuccess(String positionInfoXml);
		public void onGetPositionInfoFailed(ServiceCommandError error);
	}
	
	public DLNAService(ServiceDescription serviceDescription, ServiceConfig serviceConfig) {
		super(serviceDescription, serviceConfig);

		httpClient = new DefaultHttpClient();
		ClientConnectionManager mgr = httpClient.getConnectionManager();
		HttpParams params = httpClient.getParams();
		httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(params, mgr.getSchemeRegistry()), params);
		
		context = DiscoveryManager.getInstance().getContext();

		SIDList = new HashMap<String, String>();

		updateControlURL();
		
		httpServer = new DLNAHttpServer();
	}
	
	public static JSONObject discoveryParameters() {
		JSONObject params = new JSONObject();
		
		try {
			params.put("serviceId", ID);
			params.put("filter", "urn:schemas-upnp-org:device:MediaRenderer:1");
		} catch (JSONException e) {
			e.printStackTrace();
		}

		return params;
	}
	
	@Override
	public void setServiceDescription(ServiceDescription serviceDescription) {
		super.setServiceDescription(serviceDescription);
		
		updateControlURL();
	}
	
	private void updateControlURL() {
		List<Service> serviceList = serviceDescription.getServiceList();

		if (serviceList != null) {
			for (int i = 0; i < serviceList.size(); i++) {
				if (serviceList.get(i).serviceType.contains(AV_TRANSPORT)) {
					avTransportURL = String.format("%s%s", serviceList.get(i).baseURL, serviceList.get(i).controlURL);
				}
				else if ((serviceList.get(i).serviceType.contains(RENDERING_CONTROL)) && !(serviceList.get(i).serviceType.contains(GROUP_RENDERING_CONTROL))) {
					renderingControlURL = String.format("%s%s", serviceList.get(i).baseURL, serviceList.get(i).controlURL);
				}
			}
		}
	}
	
	/******************
    MEDIA PLAYER
    *****************/
	@Override
	public MediaPlayer getMediaPlayer() {
		return this;
	};
	
	@Override
	public CapabilityPriorityLevel getMediaPlayerCapabilityLevel() {
		return CapabilityPriorityLevel.NORMAL;
	}
	
	@Override
	public void getMediaInfo(final MediaInfoListener listener) {
		getPositionInfo(new PositionInfoListener() {
			
			@Override
			public void onGetPositionInfoSuccess(String positionInfoXml) {

				String trackMetaData = parseData(positionInfoXml, "TrackMetaData");
				
				MediaInfo info = DLNAMediaInfoParser.getMediaInfo(trackMetaData);
				
				Util.postSuccess(listener, info);
				
			}
			
			@Override
			public void onGetPositionInfoFailed(ServiceCommandError error) {
				Util.postError(listener, error);
				
			}
		});
	}
	
	@Override
	public ServiceSubscription<MediaInfoListener> subscribeMediaInfo(MediaInfoListener listener) {
		URLServiceSubscription<MediaInfoListener> request = new URLServiceSubscription<MediaInfoListener>(this, "info", null, null);
		request.addListener(listener);
		addSubscription(request);
		return request;	
		
	}
	
	public void displayMedia(String url, String mimeType, String title, String description, String iconSrc, final LaunchListener listener) {
		final String instanceId = "0";
	    String[] mediaElements = mimeType.split("/");
	    String mediaType = mediaElements[0];
	    String mediaFormat = mediaElements[1];

	    if (mediaType == null || mediaType.length() == 0 || mediaFormat == null || mediaFormat.length() == 0) {
	        Util.postError(listener, new ServiceCommandError(0, "You must provide a valid mimeType (audio/*,  video/*, etc)", null));
	        return;
	    }

	    mediaFormat = "mp3".equals(mediaFormat) ? "mpeg" : mediaFormat;
	    String mMimeType = String.format("%s/%s", mediaType, mediaFormat);
		
		ResponseListener<Object> responseListener = new ResponseListener<Object>() {
			
			@Override
			public void onSuccess(Object response) {
				String method = "Play";
				
				Map<String, String> parameters = new HashMap<String, String>();
				parameters.put("Speed", "1");
				
				String payload = getMessageXml(AV_TRANSPORT_URN, method, "0", parameters);

				ResponseListener<Object> playResponseListener = new ResponseListener<Object> () {
					@Override
					public void onSuccess(Object response) {
						LaunchSession launchSession = new LaunchSession();
						launchSession.setService(DLNAService.this);
						launchSession.setSessionType(LaunchSessionType.Media);

						Util.postSuccess(listener, new MediaLaunchObject(launchSession, DLNAService.this));
					}
					
					@Override
					public void onError(ServiceCommandError error) {
						Util.postError(listener, error);
					}
				};
			
				ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(DLNAService.this, method, payload, playResponseListener);
				request.send();
			}
			
			@Override
			public void onError(ServiceCommandError error) {
				Util.postError(listener, error);
			}
		};

		String method = "SetAVTransportURI";
		String metadata = getMetadata(url, mMimeType, title, description, iconSrc);
		
    	Map<String, String> params = new HashMap<String, String>();
    	params.put("CurrentURI", url);
    	params.put("CurrentURIMetaData", metadata);
    	
    	String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, params);
        
		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(DLNAService.this, method, payload, responseListener);
		request.send();
	}
	
	@Override
	public void displayImage(String url, String mimeType, String title, String description, String iconSrc, LaunchListener listener) {
		displayMedia(url, mimeType, title, description, iconSrc, listener);
	}
	
	@Override
	public void displayImage(MediaInfo mediaInfo, LaunchListener listener) {
    	ImageInfo imageInfo = mediaInfo.getImages().get(0);
    	String iconSrc = imageInfo.getUrl();
    	
		displayImage(mediaInfo.getUrl(), mediaInfo.getMimeType(), mediaInfo.getTitle(), mediaInfo.getDescription(), iconSrc, listener);
	}
	
	@Override
	public void playMedia(String url, String mimeType, String title, String description, String iconSrc, boolean shouldLoop, LaunchListener listener) {
		displayMedia(url, mimeType, title, description, iconSrc, listener);
	}
	
	@Override
	public void playMedia(MediaInfo mediaInfo, boolean shouldLoop,
			LaunchListener listener) {
    	ImageInfo imageInfo = mediaInfo.getImages().get(0);
    	String iconSrc = imageInfo.getUrl();
    	
    	playMedia(mediaInfo.getUrl(), mediaInfo.getMimeType(), mediaInfo.getTitle(), mediaInfo.getDescription(), iconSrc, shouldLoop, listener);
	}
	
	@Override
	public void closeMedia(LaunchSession launchSession, ResponseListener<Object> listener) {
		if (launchSession.getService() instanceof DLNAService)
			((DLNAService) launchSession.getService()).stop(listener);
	}
	
	/******************
    MEDIA CONTROL
    *****************/
	@Override
	public MediaControl getMediaControl() {
		return this;
	};
	
	@Override
	public CapabilityPriorityLevel getMediaControlCapabilityLevel() {
		return CapabilityPriorityLevel.NORMAL;
	}

	@Override
	public void play(ResponseListener<Object> listener) {
	  	String method = "Play";
		String instanceId = "0";

		Map<String, String> parameters = new HashMap<String, String>();
		parameters.put("Speed", "1");
		
		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, parameters);

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
		request.send();
	}

	@Override
	public void pause(ResponseListener<Object> listener) {
    	String method = "Pause";
		String instanceId = "0";

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, null);

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
		request.send();
	}

	@Override
	public void stop(ResponseListener<Object> listener) {
    	String method = "Stop";
		String instanceId = "0";

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, null);

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
		request.send();
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
        String method = "Previous";
        String instanceId = "0";

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, null);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
        request.send();
	}

	@Override
	public void next(ResponseListener<Object> listener) {
        String method = "Next";
        String instanceId = "0";

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, null);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
        request.send();
	}
	
	@Override
	public void seek(long position, ResponseListener<Object> listener) {
    	String method = "Seek";
		String instanceId = "0";
		
		long second = (position / 1000) % 60;
		long minute = (position / (1000 * 60)) % 60;
		long hour = (position / (1000 * 60 * 60)) % 24;

		String time = String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second);
		
		Map<String, String> parameters = new HashMap<String, String>();
		parameters.put("Unit", "REL_TIME");
		parameters.put("Target", time);

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, parameters);

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
		request.send();
	}
	
	private void getPositionInfo(final PositionInfoListener listener) {
    	String method = "GetPositionInfo";
		String instanceId = "0";

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, null);
		ResponseListener<Object> responseListener = new ResponseListener<Object>() {
			
			@Override
			public void onSuccess(Object response) {
				if (listener != null) {
					listener.onGetPositionInfoSuccess((String)response);
				}
			}
			
			@Override
			public void onError(ServiceCommandError error) {
				if (listener != null) {
					listener.onGetPositionInfoFailed(error);
				}
			}
		};

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, responseListener);
		request.send();
	}
	
	@Override
	public void getDuration(final DurationListener listener) {
		getPositionInfo(new PositionInfoListener() {
			
			@Override
			public void onGetPositionInfoSuccess(String positionInfoXml) {
				String strDuration = parseData(positionInfoXml, "TrackDuration");
				
				String trackMetaData = parseData(positionInfoXml, "TrackMetaData");
				MediaInfo info = DLNAMediaInfoParser.getMediaInfo(trackMetaData);
				// Check if duration we get not equals 0 or media is image, otherwise wait 1 second and try again
				if ((!strDuration.equals("0:00:00")) || (info.getMimeType().contains("image"))) {
				long milliTimes = convertStrTimeFormatToLong(strDuration) * 1000;
				
				Util.postSuccess(listener, milliTimes);}
				else new Timer().schedule(new TimerTask() {
					
					@Override
					public void run() {
						getDuration(listener);
						
					}
				}, 1000); 
				
			}
			
			@Override
			public void onGetPositionInfoFailed(ServiceCommandError error) {
				Util.postError(listener, error);
			}
		});
	}
	
	@Override
	public void getPosition(final PositionListener listener) {
		getPositionInfo(new PositionInfoListener() {
			
			@Override
			public void onGetPositionInfoSuccess(String positionInfoXml) {
				String strDuration = parseData(positionInfoXml, "RelTime");
				
				long milliTimes = convertStrTimeFormatToLong(strDuration) * 1000;
				
				Util.postSuccess(listener, milliTimes);
			}
			
			@Override
			public void onGetPositionInfoFailed(ServiceCommandError error) {
				Util.postError(listener, error);
			}
		});
	}
	
	protected String getMessageXml(String serviceURN, String method, String instanceId, Map<String, String> params) {
		StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">");

        sb.append("<s:Body>");
        sb.append("<u:" + method + " xmlns:u=\""+ serviceURN + "\">");
        sb.append("<InstanceID>" + instanceId + "</InstanceID>");

        if (params != null) {
    		for (Map.Entry<String, String> entry : params.entrySet()) {
    		    String key = entry.getKey();
    		    String value = entry.getValue();

    		    String str = String.format("<%s>%s</%s>", key, value, key);
    		    sb.append(str);
    		}
        }
        
        sb.append("</u:" + method + ">");
        sb.append("</s:Body>");
        sb.append("</s:Envelope>");

        return sb.toString();
	}

    protected String getMetadata(String mediaURL, String mime, String title, String description, String iconUrl) {
		String id = "1000";
		String parentID = "0";
		String restricted = "0";
		String objectClass = null;
		StringBuilder sb = new StringBuilder();

		sb.append("&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; ");
		sb.append("xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; ");
		sb.append("xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;");

		sb.append("&lt;item id=&quot;" + id + "&quot; parentID=&quot;" + parentID + "&quot; restricted=&quot;" + restricted + "&quot;&gt;");
		sb.append("&lt;dc:title&gt;" + title + "&lt;/dc:title&gt;");
		
		sb.append("&lt;dc:description&gt;" + description + "&lt;/dc:description&gt;");
		
		if (mime.startsWith("image")) {
			objectClass = "object.item.imageItem";
		}
		else if (mime.startsWith("video")) {
			objectClass = "object.item.videoItem";
		}
		else if (mime.startsWith("audio")) {
			objectClass = "object.item.audioItem";
		}
		sb.append("&lt;res protocolInfo=&quot;http-get:*:" + mime + ":DLNA.ORG_OP=01&quot;&gt;" + mediaURL + "&lt;/res&gt;");
		sb.append("&lt;upnp:albumArtURI&gt;" + iconUrl + "&lt;/upnp:albumArtURI&gt;");
		sb.append("&lt;upnp:class&gt;" + objectClass + "&lt;/upnp:class&gt;");

		sb.append("&lt;/item&gt;");
		sb.append("&lt;/DIDL-Lite&gt;");
		
		return sb.toString();
	}
	
	@Override
	public void sendCommand(final ServiceCommand<?> mCommand) {
		Util.runInBackground(new Runnable() {

			@SuppressWarnings("unchecked")
			@Override
			public void run() {
				ServiceCommand<ResponseListener<Object>> command = (ServiceCommand<ResponseListener<Object>>) mCommand;

				String method = command.getTarget();
				String payload = (String)command.getPayload();

				String targetURL = null;
				String serviceURN = null;
				
				if (payload.contains(AV_TRANSPORT_URN)) {
					targetURL = avTransportURL;
					serviceURN = AV_TRANSPORT_URN;
				}
				else if (payload.contains(RENDERING_CONTROL_URN)) {
					targetURL = renderingControlURL;
					serviceURN = RENDERING_CONTROL_URN;
				}
				
				HttpPost post = new HttpPost(targetURL);
				post.setHeader("Content-Type", "text/xml; charset=utf-8");
				post.setHeader("SOAPAction", String.format("\"%s#%s\"", serviceURN, method));

				try {
					post.setEntity(new StringEntity(payload));
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				}

				HttpResponse response = null;
				
				try {
					response = httpClient.execute(post);

					int code = response.getStatusLine().getStatusCode();
					
					if (code == 200) {
						HttpEntity entity = response.getEntity();
						String message = EntityUtils.toString(entity, "UTF-8");

						Util.postSuccess(command.getResponseListener(), message);
					} 
					else {
						Util.postError(command.getResponseListener(), ServiceCommandError.getError(code));
					}

					response.getEntity().consumeContent();
				} catch (ClientProtocolException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}
	
	@Override
	protected void updateCapabilities() {
		List<String> capabilities = new ArrayList<String>();
		
		capabilities.add(Display_Image);
		capabilities.add(Display_Video);
		capabilities.add(Display_Audio);
		capabilities.add(Close);
		
		capabilities.add(MetaData_Title);
		capabilities.add(MetaData_MimeType);
		capabilities.add(MediaInfo_Get);
		capabilities.add(MediaInfo_Subscribe);

		capabilities.add(Play);
		capabilities.add(Pause);
		capabilities.add(Stop);
		capabilities.add(Seek);
		capabilities.add(Position);
		capabilities.add(Duration);
		capabilities.add(PlayState);
		capabilities.add(PlayState_Subscribe);
		
		capabilities.add(Volume_Set);
		capabilities.add(Volume_Get);
		capabilities.add(Volume_Up_Down);
		capabilities.add(Volume_Subscribe);
		capabilities.add(Mute_Get);
		capabilities.add(Mute_Set);
		capabilities.add(Mute_Subscribe);
		
		setCapabilities(capabilities);
	}
	
	@Override
	public LaunchSession decodeLaunchSession(String type, JSONObject sessionObj) throws JSONException {
		if (type == "dlna") {
			LaunchSession launchSession = LaunchSession.launchSessionFromJSONObject(sessionObj);
			launchSession.setService(this);

			return launchSession;
		}
		return null;
	}
	
	private String parseData(String response, String key) {
		String startTag = "<" + key + ">";
		String endTag = "</" + key + ">";
		
		int start = response.indexOf(startTag);
		int end = response.indexOf(endTag);
		
		String data = response.substring(start + startTag.length(), end);
		
		return data;
	}
	
	private long convertStrTimeFormatToLong(String strTime) {
		String[] tokens = strTime.split(":");
		long time = 0;
		
		for (int i = 0; i < tokens.length; i++) {
			time *= 60;
			time += Integer.parseInt(tokens[i]);
		}
		
		return time;
	}

	@Override
	public void getPlayState(final PlayStateListener listener) {
    	String method = "GetTransportInfo";
		String instanceId = "0";

		String payload = getMessageXml(AV_TRANSPORT_URN, method, instanceId, null);
		
		ResponseListener<Object> responseListener = new ResponseListener<Object>() {
			
			@Override
			public void onSuccess(Object response) {
				String transportState = parseData((String)response, "CurrentTransportState");
				PlayStateStatus status = PlayStateStatus.convertTransportStateToPlayStateStatus(transportState);
				
				Util.postSuccess(listener, status);
			}
			
			@Override
			public void onError(ServiceCommandError error) {
				Util.postError(listener, error);
			}
		};

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, responseListener);
		request.send();
	}
	
	@Override
	public ServiceSubscription<PlayStateListener> subscribePlayState(PlayStateListener listener) {
		URLServiceSubscription<PlayStateListener> request = new URLServiceSubscription<MediaControl.PlayStateListener>(this, PLAY_STATE, null, null);
		request.addListener(listener);
		addSubscription(request);
		return request;
	}
	
	private void addSubscription(URLServiceSubscription<?> subscription) {
		if (httpServer.isRunning() == false) {
			Util.runInBackground(new Runnable() {
				
				@Override
				public void run() {
					httpServer.start();
				}
			});
			subscribeServices();
		}
		
		httpServer.getSubscriptions().add(subscription);
	}
	
	@Override
	public void unsubscribe(URLServiceSubscription<?> subscription) {
		httpServer.getSubscriptions().remove(subscription);

		if (httpServer.getSubscriptions().isEmpty()) {
			unsubscribeServices();
			httpServer.stop();
		}
	}
	
	@Override
	public boolean isConnectable() {
		return true;
	}
	
	@Override
	public boolean isConnected() {
		return connected;
	}
	
	@Override
	public void connect() {
	//  TODO:  Fix this for roku.  Right now it is using the InetAddress reachable function.  Need to use an HTTP Method.
//		mServiceReachability = DeviceServiceReachability.getReachability(serviceDescription.getIpAddress(), this);
//		mServiceReachability.start();
		
		connected = true;
		
		reportConnected(true);
	}
	
	@Override
	public void disconnect() {
		connected = false;
		
		if (mServiceReachability != null)
			mServiceReachability.stop();
		
		Util.runOnUI(new Runnable() {
			
			@Override
			public void run() {
				if (listener != null)
					listener.onDisconnect(DLNAService.this, null);
			}
		});
	}
	
	@Override
	public void onLoseReachability(DeviceServiceReachability reachability) {
		if (connected) {
			disconnect();
		} else {
			mServiceReachability.stop();
		}
	}
	
	public void subscribeServices() {
		Util.runInBackground(new Runnable() {

			@Override
			public void run() {
	            String myIpAddress = null;
				try {
					myIpAddress = Util.getIpAddress(context).getHostAddress();
				} catch (UnknownHostException e) {
					e.printStackTrace();
				}
	            
				HttpHost host = new HttpHost(serviceDescription.getIpAddress(), serviceDescription.getPort());
				List<Service> serviceList = serviceDescription.getServiceList();

				if (serviceList != null) {
					for (int i = 0; i < serviceList.size(); i++) {
						BasicHttpRequest request = new BasicHttpRequest(SUBSCRIBE, serviceList.get(i).eventSubURL);
						
						request.setHeader("CALLBACK", "<http://" + myIpAddress + ":" + httpServer.getPort() + serviceList.get(i).eventSubURL + ">");
						request.setHeader("NT", "upnp:event");
						request.setHeader("TIMEOUT", "Second-" + TIMEOUT);
						request.setHeader("Connection", "close");
						request.setHeader("Content-length", "0");
						request.setHeader("USER-AGENT", "Android UPnp/1.1 ConnectSDK");
						
						HttpResponse response = null;
						try {
							response = httpClient.execute(host, request);
							
							int code = response.getStatusLine().getStatusCode();

							if (code == 200) { 
					            SIDList.put(serviceList.get(i).serviceType, response.getFirstHeader("SID").getValue());
							}
							response.getEntity().consumeContent();
						} catch (ClientProtocolException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		});
		
		resubscribeServices();
	}
	
	public void resubscribeServices() {
		resubscriptionTimer = new Timer();
		resubscriptionTimer.scheduleAtFixedRate(new TimerTask() {
			
			@Override
			public void run() {
				Util.runInBackground(new Runnable() {

					@Override
					public void run() {
						HttpHost host = new HttpHost(serviceDescription.getIpAddress(), serviceDescription.getPort());
						List<Service> serviceList = serviceDescription.getServiceList();

						if (serviceList != null) {
							for (int i = 0; i < serviceList.size(); i++) {
								String eventSubURL = serviceList.get(i).eventSubURL;
								String SID = SIDList.get(serviceList.get(i).serviceType);
								
								BasicHttpRequest request = new BasicHttpRequest(SUBSCRIBE, eventSubURL);
								
								request.setHeader("TIMEOUT", "Second-" + TIMEOUT);
								request.setHeader("SID", SID);
								
								HttpResponse response = null;
								
								try {
									response = httpClient.execute(host, request);
									
									int code = response.getStatusLine().getStatusCode();

									if (code == 200) { 
									}
									response.getEntity().consumeContent();
								} catch (ClientProtocolException e) {
									e.printStackTrace();
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						}
					}
				});				
			}
		}, TIMEOUT/2*1000, TIMEOUT/2*1000);
	}
	
	public void unsubscribeServices() {
		if (resubscriptionTimer != null)
			resubscriptionTimer.cancel();

		Util.runInBackground(new Runnable() {

			@Override
			public void run() {
				final List<Service> serviceList = serviceDescription.getServiceList();

				if (serviceList != null) {
					for (int i = 0; i < serviceList.size(); i++) {
						BasicHttpRequest request = new BasicHttpRequest(UNSUBSCRIBE, serviceList.get(i).eventSubURL);
						
						String sid = SIDList.get(serviceList.get(i).serviceType);
						request.setHeader("SID", sid);
						HttpResponse response = null;

						try {
							HttpHost host = new HttpHost(serviceDescription.getIpAddress(), serviceDescription.getPort());
							
							response = httpClient.execute(host, request);
							
							int code = response.getStatusLine().getStatusCode();

							if (code == 200) { 
					            SIDList.remove(serviceList.get(i).serviceType);
							}
							response.getEntity().consumeContent();
						} catch (ClientProtocolException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		});
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
				if (volume >= 1.0) {
					Util.postSuccess(listener, null);
				}
				else {
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
				}
				else {
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
	public void setVolume(float volume, ResponseListener<Object> listener) {
		String method = "SetVolume";
		String instanceId = "0";
		String channel = "Master";
		String value = String.valueOf((int)(volume*100));
		
    	Map<String, String> params = new HashMap<String, String>();
   		params.put("Channel", channel);
    	params.put("DesiredVolume", value);
    	
		String payload = getMessageXml(RENDERING_CONTROL_URN, method, instanceId, params);

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
		request.send();
	}

	@Override
	public void getVolume(final VolumeListener listener) {
		String method = "GetVolume";
		String instanceId = "0";
		String channel = "Master";

    	Map<String, String> params = new HashMap<String, String>();
   		params.put("Channel", channel);
    	
		String payload = getMessageXml(RENDERING_CONTROL_URN, method, instanceId, params);

		ResponseListener<Object> responseListener = new ResponseListener<Object>() {

			@Override
			public void onSuccess(Object response) {
				String currentVolume = parseData((String) response, "CurrentVolume");
				int iVolume = Integer.parseInt(currentVolume);
				float fVolume = (float) (iVolume / 100.0);

				Util.postSuccess(listener, fVolume);
			}

			@Override
			public void onError(ServiceCommandError error) {
				Util.postError(listener, error);
			}
		};

		ServiceCommand<VolumeListener> request = new ServiceCommand<VolumeListener>(this, method, payload, responseListener);
		request.send();
	}

	@Override
	public void setMute(boolean isMute, ResponseListener<Object> listener) {
		String method = "SetMute";
		String instanceId = "0";
		String channel = "Master";
		int muteStatus = (isMute) ? 1 : 0;

    	Map<String, String> params = new HashMap<String, String>();
   		params.put("Channel", channel);
   		params.put("DesiredMute", String.valueOf(muteStatus));
    	
		String payload = getMessageXml(RENDERING_CONTROL_URN, method, instanceId, params);

		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, listener);
		request.send();
	}
	
	@Override
	public void getMute(final MuteListener listener) {
		String method = "GetMute";
		String instanceId = "0";
		String channel = "Master";
		
    	Map<String, String> params = new HashMap<String, String>();
   		params.put("Channel", channel);
    	
		String payload = getMessageXml(RENDERING_CONTROL_URN, method, instanceId, params);

		ResponseListener<Object> responseListener = new ResponseListener<Object>() {

			@Override
			public void onSuccess(Object response) {
				String currentMute = parseData((String) response, "CurrentMute");
				boolean isMute = Boolean.parseBoolean(currentMute);

				Util.postSuccess(listener, isMute);
			}

			@Override
			public void onError(ServiceCommandError error) {
				Util.postError(listener, error);
			}
		};
		
		ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, method, payload, responseListener);
		request.send();
	}

	@Override
	public ServiceSubscription<VolumeListener> subscribeVolume(VolumeListener listener) {
		URLServiceSubscription<VolumeListener> request = new URLServiceSubscription<VolumeListener>(this, "volume", null, null);
		request.addListener(listener);
		addSubscription(request);
		return request;	
	}

	@Override
	public ServiceSubscription<MuteListener> subscribeMute(MuteListener listener) {
		URLServiceSubscription<MuteListener> request = new URLServiceSubscription<MuteListener>(this, "mute", null, null);
		request.addListener(listener);
		addSubscription(request);
		return request;
	}

}
