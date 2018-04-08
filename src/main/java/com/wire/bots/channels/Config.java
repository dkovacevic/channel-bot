//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.channels;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wire.bots.channels.model.Channel;
import com.wire.bots.sdk.Configuration;

import java.util.HashMap;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Config extends Configuration {
    public HashMap<String, Channel> channels = new HashMap<>();
    public String host;
    public int batch = 100;
    public int threads = 8;
    public DB postgres = new DB();

    public HashMap<String, Channel> getChannels() {
        return channels;
    }

    public String getHost() {
        return host;
    }

    public int getBatch() {
        return batch;
    }

    public int getThreads() {
        return threads;
    }

    public DB getPostgres() {
        return postgres;
    }
}
