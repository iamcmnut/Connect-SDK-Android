/*
 * CastServiceDescription
 * Connect SDK
 * 
 * Copyright (c) 2014 LG Electronics.
 * Created by Hyun Kook Khang on 20 Feb 2014
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

package com.connectsdk.service.config;

import tv.matchstick.flint.FlintDevice;

public class FlintServiceDescription extends ServiceDescription {
    FlintDevice flintDevice;

    public FlintServiceDescription(String serviceFilter, String UUID, String ipAddress, FlintDevice flintDevice) {
        super(serviceFilter, UUID, ipAddress);
        this.flintDevice = flintDevice;
    }

    public FlintDevice getFlintDevice() {
        return flintDevice;
    }

    public void setFlintDevice(FlintDevice flintDevice) {
        this.flintDevice = flintDevice;
    }

}
