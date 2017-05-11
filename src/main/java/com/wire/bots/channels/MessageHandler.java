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

import com.waz.model.Messages;
import com.wire.bots.channels.model.Channel;
import com.wire.bots.sdk.ClientRepo;
import com.wire.bots.sdk.Logger;
import com.wire.bots.sdk.MessageHandlerBase;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.assets.Picture;
import com.wire.bots.sdk.models.AssetKey;
import com.wire.bots.sdk.models.ImageMessage;
import com.wire.bots.sdk.models.TextMessage;
import com.wire.bots.sdk.server.model.Member;
import com.wire.bots.sdk.server.model.NewBot;
import com.wire.bots.sdk.server.model.User;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

class MessageHandler extends MessageHandlerBase {
    protected final Broadcaster broadcaster;

    MessageHandler(ClientRepo repo) {
        broadcaster = new Broadcaster(repo);
    }

    @Override
    public boolean onNewBot(NewBot newBot) {
        try {
            String botId = newBot.id;
            Channel channel = getChannel(botId);
            User origin = newBot.origin;

            ArrayList<String> whitelist = Service.storage.getWhitelist(channel.name, Storage.State.WHITE);
            if (!whitelist.isEmpty() && !whitelist.contains(origin.handle)) {
                Logger.warning("Rejecting NewBot. Not White Listed: %s", origin.handle);
                return false;
            }

            ArrayList<String> blacklist = Service.storage.getWhitelist(channel.name, Storage.State.BLACK);
            if (!blacklist.isEmpty() && blacklist.contains(origin.handle)) {
                Logger.warning("Rejecting NewBot. Black Listed: %s", origin.handle);
                return false;
            }

            for (Member member : newBot.conversation.members) {
                if (member.service != null) {
                    Logger.warning("Rejecting NewBot. Provider: %s service: %s",
                            member.service.provider,
                            member.service.id);
                    return false; // we don't want to be in a conv if other bots are there.
                }
            }

            int id = Service.storage.getLastBroadcast(channel.name);
            Service.storage.updateBot(botId, "Last", ++id);

            if (!channel.muted) {
                broadcaster.sendToAdminConv(channel.admin, "New subscriber just joined");
            }
        } catch (Exception e) {
            Logger.error(e.getLocalizedMessage());
            return false;
        }
        return true;
    }

    @Override
    public void onNewConversation(WireClient client) {
        try {
            String botId = client.getId();
            Channel channel = getChannel(botId);
            if (botId.equals(channel.admin)) {
                String msg = String.format("This is Admin Conversation for the Channel: **%s**." +
                                "\nYou should rename this conversation to something like: `Admin %s`." +
                                "\nUse this conversation to broadcast. Don't leave or delete it!" +
                                "\nOthers can subscribe to this channel by clicking on: wire.com/b/%s" +
                                "\n`Type: /help`",
                        channel.name,
                        channel.name,
                        channel.name);
                client.sendText(msg);
                return;
            }

            if (channel.introPic != null) {
                Picture picture = new Picture(channel.introPic);
                AssetKey assetKey = client.uploadAsset(picture);
                picture.setAssetKey(assetKey.key);
                picture.setAssetToken(assetKey.token);
                client.sendPicture(picture);
            }

            String label = channel.introText != null ? channel.introText : String.format("This is **%s** channel", channel.name);
            client.sendText(label + "\n`Type: /help`");
        } catch (Exception e) {
            Logger.error(e.getMessage());
        }
    }

    @Override
    public void onText(WireClient client, TextMessage msg) {
        try {
            String botId = client.getId();
            Channel channel = getChannel(botId);

            if (botId.equals(channel.admin)) {
                if (!Commander.processAdminCmd(channel.name, msg.getText(), client)) {
                    broadcaster.broadcast(channel, msg);
                }
            } else {
                if (!processSubscriberCmd(msg.getText(), client)) {
                    if (!channel.muted)
                        broadcaster.sendToAdminConv(channel.admin, msg.getText());

                    Service.storage.insertMessage(botId, channel.name);
                }
            }
        } catch (Exception e) {
            Logger.error(e.getLocalizedMessage());
        }
    }

    @Override
    public void onImage(WireClient client, ImageMessage msg) {
        try {
            String botId = client.getId();
            Channel channel = getChannel(botId);

            if (botId.equals(channel.admin)) {
                byte[] bytes = client.downloadAsset(msg.getAssetKey(), msg.getAssetToken(), msg.getSha256(), msg.getOtrKey());
                broadcaster.broadcast(channel.name, msg, bytes);
            } else {
                if (!channel.muted)
                    broadcaster.sendToAdminConv(channel.admin, msg);

                Service.storage.insertMessage(botId, channel.name);
            }
        } catch (Exception e) {
            Logger.error(e.getLocalizedMessage());
        }
    }

    @Override
    public void onBotRemoved(String botId) {
        try {
            Service.storage.removeSubscriber(botId);
            Logger.info("Removed Subscriber: %s", botId);
        } catch (SQLException e) {
            Logger.error(e.getMessage());
        }
    }

    @Override
    public void onMemberLeave(WireClient client, ArrayList<String> userIds) {
        try {
            String botId = client.getId();

            // Delete the channel if there are no more members
            List<Member> members = client.getConversation().members;
            if (members.isEmpty()) {
                Service.storage.removeSubscriber(botId);
                Channel channel = getChannel(botId);
                if (!channel.muted)
                    broadcaster.sendToAdminConv(channel.admin, "Subscriber left");
            }
        } catch (Exception e) {
            Logger.error(e.getMessage());
        }
    }

    @Override
    public void onEvent(WireClient client, String userId, Messages.GenericMessage msg) {
        String botId = client.getId();
        try {
            Channel channel = getChannel(botId);

            if (msg.hasReaction()) {
                if (!channel.muted)
                    broadcaster.sendToAdminConv(channel.admin, "Got one like!");
            }

            if (msg.hasDeleted() && botId.equals(channel.admin)) {
                broadcaster.revokeBroadcast(channel.name, msg.getDeleted().getMessageId());
            }
        } catch (Exception e) {
            Logger.error(e.getMessage());
        }
    }

    private boolean processSubscriberCmd(String cmd, WireClient client) throws Exception {
        switch (cmd) {
            case "/help": {
                String h = "List of available commands:\n" +
                        "`/prev`   Show 5 previous posts\n" +
                        "`/mute`   Mute all new posts\n" +
                        "`/unmute` Resume posts in this channel";
                client.sendText(h);
                return true;
            }
            case "/prev": {
                broadcaster.followBack(client, 5);
                return true;
            }
            case "/mute": {
                Service.storage.updateBot(client.getId(), "Muted", 1);
                client.sendText("You won't receive posts here anymore. Type: `/unmute` to resume");
                return true;
            }
            case "/unmute": {
                Service.storage.updateBot(client.getId(), "Muted", 0);
                client.sendText("Posts resumed");
                return true;
            }
            default: {
                if (cmd.startsWith("/")) {
                    client.sendText("Unknown command: `" + cmd + "`");
                    return true;
                }
                return false;
            }
        }
    }

    protected Channel getChannel(String botId) throws SQLException {
        String channelName = Service.storage.getChannelName(botId);
        return Service.storage.getChannel(channelName);
    }
}
