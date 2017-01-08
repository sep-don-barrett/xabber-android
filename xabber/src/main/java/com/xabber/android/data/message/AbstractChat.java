/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.message;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.xabber.android.data.Application;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.connection.StanzaSender;
import com.xabber.android.data.database.realm.MessageItem;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.BaseEntity;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.extension.blocking.PrivateMucChatBlockingManager;
import com.xabber.android.data.extension.carbons.CarbonManager;
import com.xabber.android.data.extension.cs.ChatStateManager;
import com.xabber.android.data.extension.file.FileManager;
import com.xabber.android.data.extension.mam.SyncInfo;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.chat.ChatManager;
import com.xabber.android.data.notification.NotificationManager;

import org.greenrobot.eventbus.EventBus;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Message.Type;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Resourcepart;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmResults;
import io.realm.Sort;

/**
 * Chat instance.
 *
 * @author alexander.ivanov
 */
public abstract class AbstractChat extends BaseEntity implements RealmChangeListener<RealmResults<MessageItem>> {

    /**
     * Number of messages from history to be shown for context purpose.
     */
    public static final int PRELOADED_MESSAGES = 50;

    /**
     * Whether chat is open and should be displayed as active chat.
     */
    protected boolean active;
    /**
     * Whether changes in status should be record.
     */
    private boolean trackStatus;
    /**
     * Whether user never received notifications from this chat.
     */
    private boolean firstNotification;

    private Date creationTime = new Date();
    /**
     * Current thread id.
     */
    private String threadId;

    private boolean isPrivateMucChat;
    private boolean isPrivateMucChatAccepted;

    private boolean isRemotePreviousHistoryCompletelyLoaded = false;

    private Date lastSyncedTime;
    private RealmResults<MessageItem> messageItems;
    private Realm realm;
    private RealmResults<SyncInfo> syncInfo;
    private RealmResults<MessageItem> messagesWithNotEmptyText;
    private MessageItem lastNotEmptyTextMessage;

    protected AbstractChat(@NonNull final AccountJid account, @NonNull final UserJid user, boolean isPrivateMucChat) {
        super(account, isPrivateMucChat ? user : user.getBareUserJid());
        threadId = StringUtils.randomString(12);
        active = false;
        trackStatus = false;
        firstNotification = true;
        this.isPrivateMucChat = isPrivateMucChat;
        isPrivateMucChatAccepted = false;
        updateCreationTime();
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getMessages();
            }
        });
    }

    public boolean isRemotePreviousHistoryCompletelyLoaded() {
        return isRemotePreviousHistoryCompletelyLoaded;
    }

    public void setRemotePreviousHistoryCompletelyLoaded(boolean remotePreviousHistoryCompletelyLoaded) {
        isRemotePreviousHistoryCompletelyLoaded = remotePreviousHistoryCompletelyLoaded;
    }

    public Date getLastSyncedTime() {
        return lastSyncedTime;
    }

    public void setLastSyncedTime(Date lastSyncedTime) {
        this.lastSyncedTime = lastSyncedTime;
    }


    public boolean isActive() {
        if (isPrivateMucChat && !isPrivateMucChatAccepted) {
            return false;
        }

        return active;
    }

    public void openChat() {
        if (!active) {
            updateCreationTime();
        }

        active = true;
        trackStatus = true;
    }

    void closeChat() {
        active = false;
        firstNotification = true;

        if (realm != null && !realm.isClosed()) {
            realm.close();
        }
    }

    private String getAccountString() {
        return account.toString();
    }

    private String getUserString() {
        return user.toString();
    }

    public RealmResults<MessageItem> getMessages() {
        if (realm == null || realm.isClosed()) {
            realm = Realm.getDefaultInstance();
        }

        if (messageItems == null) {
            LogManager.i(this, "getMessages new messages query");
            messageItems = realm.where(MessageItem.class)
                    .equalTo(MessageItem.Fields.ACCOUNT, getAccountString())
                    .equalTo(MessageItem.Fields.USER, getUserString())
                    .findAllSortedAsync(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING);
            messageItems.addChangeListener(this);
        }

        return messageItems;
    }

    public RealmResults<SyncInfo> getSyncInfo() {
        if (realm == null || realm.isClosed()) {
            realm = Realm.getDefaultInstance();
        }

        if (syncInfo == null) {
            syncInfo = realm.where(SyncInfo.class)
                    .equalTo(SyncInfo.FIELD_ACCOUNT, getAccountString())
                    .equalTo(SyncInfo.FIELD_USER, getUserString())
                    .findAllAsync();
        }

        return syncInfo;
    }

    boolean isStatusTrackingEnabled() {
        return trackStatus;
    }

    /**
     * @return Target address for sending message.
     */
    @NonNull
    public abstract Jid getTo();

    /**
     * @return Message type to be assigned.
     */
    public abstract Type getType();

    /**
     * @return Whether user never received notifications from this chat. And
     * mark as received.
     */
    public boolean getFirstNotification() {
        boolean result = firstNotification;
        firstNotification = false;
        return result;
    }

    /**
     * @return Whether user should be notified about incoming messages in chat.
     */
    protected boolean notifyAboutMessage() {
        return SettingsManager.eventsMessage() != SettingsManager.EventsMessage.none;
    }

    abstract protected MessageItem createNewMessageItem(String text);

    /**
     * Creates new action.
     * @param resource can be <code>null</code>.
     * @param text     can be <code>null</code>.
     */
    public void newAction(Resourcepart resource, String text, ChatAction action) {
        createAndSaveNewMessage(resource, text, action, null, true, false, false, false, null);
    }

    /**
     * Creates new message.
     * <p/>
     * Any parameter can be <code>null</code> (except boolean values).
     *
     * @param resource       Contact's resource or nick in conference.
     * @param text           message.
     * @param action         Informational message.
     * @param delayTimestamp Time when incoming message was sent or outgoing was created.
     * @param incoming       Incoming message.
     * @param notify         Notify user about this message when appropriated.
     * @param unencrypted    Whether not encrypted message in OTR chat was received.
     * @param offline        Whether message was received from server side offline storage.
     * @return
     */
    protected void createAndSaveNewMessage(Resourcepart resource, String text,
                                           final ChatAction action, final Date delayTimestamp, final boolean incoming,
                                           boolean notify, final boolean unencrypted, final boolean offline, final String stanzaId) {
        final MessageItem messageItem = createMessageItem(resource, text, action, delayTimestamp,
                incoming, notify, unencrypted, offline, stanzaId);
        saveMessageItem(messageItem);
        EventBus.getDefault().post(new NewMessageEvent());
    }

    public void saveMessageItem(final MessageItem messageItem) {
        Realm realm = Realm.getDefaultInstance();

        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                realm.copyToRealm(messageItem);
            }
        });

        realm.close();
    }

    protected MessageItem createMessageItem(Resourcepart resource, String text, ChatAction action,
                                            Date delayTimestamp, boolean incoming, boolean notify,
                                            boolean unencrypted, boolean offline, String stanzaId) {
        final boolean visible = MessageManager.getInstance().isVisibleChat(this);
        boolean read = incoming ? visible : true;
        boolean send = incoming;
        if (action == null && text == null) {
            throw new IllegalArgumentException();
        }
        if (text == null) {
            text = "";
        }
        if (action != null) {
            read = true;
            send = true;
        }

        final Date timestamp = new Date();

        if (text.trim().isEmpty()) {
            notify = false;
        }

        if (notify || !incoming) {
            openChat();
        }
        if (!incoming) {
            notify = false;
        }

        if (isPrivateMucChat) {
            if (!isPrivateMucChatAccepted
                    || PrivateMucChatBlockingManager.getInstance().getBlockedContacts(account).contains(user)) {
                notify = false;
            }
        }

        MessageItem messageItem = new MessageItem();

        messageItem.setAccount(account);
        messageItem.setUser(user);

        if (resource == null) {
            messageItem.setResource(Resourcepart.EMPTY);
        }
        if (action != null) {
            messageItem.setAction(action.toString());
        }
        messageItem.setText(text);
        messageItem.setTimestamp(timestamp.getTime());
        if (delayTimestamp != null) {
            messageItem.setDelayTimestamp(delayTimestamp.getTime());
        }
        messageItem.setIncoming(incoming);
        messageItem.setRead(read);
        messageItem.setSent(send);
        messageItem.setUnencrypted(unencrypted);
        messageItem.setOffline(offline);
        messageItem.setStanzaId(stanzaId);
        FileManager.processFileMessage(messageItem, true);

        if (notify && notifyAboutMessage()) {
            if (visible) {
                if (ChatManager.getInstance().isNotifyVisible(account, user)) {
                    NotificationManager.getInstance().onMessageNotification(messageItem);
                }
            } else {
                NotificationManager.getInstance().onMessageNotification(messageItem);
            }
        }

        return messageItem;
    }

    String newFileMessage(final File file) {
        Realm realm = Realm.getDefaultInstance();

        final String messageId = UUID.randomUUID().toString();

        realm.executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MessageItem messageItem = new MessageItem(messageId);
                messageItem.setAccount(account);
                messageItem.setUser(user);
                messageItem.setText(file.getName());
                messageItem.setFilePath(file.getPath());
                messageItem.setTimestamp(System.currentTimeMillis());
                messageItem.setRead(true);
                messageItem.setSent(true);
                messageItem.setError(false);
                messageItem.setIncoming(false);
                realm.copyToRealm(messageItem);
            }
        });


        realm.close();

        return messageId;
    }

    /**
     * @return Whether chat accepts packets from specified user.
     */
    private boolean accept(UserJid jid) {
        return this.user.equals(jid);
    }

    @Nullable
    public synchronized MessageItem getLastMessage() {
        return lastNotEmptyTextMessage;
    }

    private void updateLastMessage() {
        if (messagesWithNotEmptyText == null) {
            if (messageItems.isValid() && messageItems.isLoaded() && !messageItems.isEmpty()) {
                LogManager.i(this, "getLastMessage quert last message");
                messagesWithNotEmptyText = messageItems.where()
                        .not().isEmpty(MessageItem.Fields.TEXT)
                        .findAllSortedAsync(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING);
                messagesWithNotEmptyText.addChangeListener(this);
            }
        } else {
            if (messagesWithNotEmptyText.isValid() && messagesWithNotEmptyText.isLoaded() &&
                    !messagesWithNotEmptyText.isEmpty()) {
                synchronized (this) {
                    lastNotEmptyTextMessage = realm.copyFromRealm(messagesWithNotEmptyText.last());
                }
            }
        }
    }

    /**
     * @return Time of last message in chat. Can be <code>null</code>.
     */
    public Date getLastTime() {
        MessageItem lastMessage = getLastMessage();
        if (lastMessage != null) {
            return new Date(lastMessage.getTimestamp());
        } else {
            return null;
        }
    }

    /**
     * @return New message packet to be sent.
     */
    public Message createMessagePacket(String body) {
        Message message = new Message();
        message.setTo(getTo());
        message.setType(getType());
        message.setBody(body);
        message.setThread(threadId);
        return message;
    }

    /**
     * Prepare text to be send.
     *
     * @return <code>null</code> if text shouldn't be send.
     */
    protected String prepareText(String text) {
        return text;
    }


    public void sendMessages() {
        final Realm realm = Realm.getDefaultInstance();

        realm.executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                RealmResults<MessageItem> messagesToSend = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.ACCOUNT, account.toString())
                        .equalTo(MessageItem.Fields.USER, user.toString())
                        .equalTo(MessageItem.Fields.SENT, false)
                        .findAllSorted(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING);
                if (messagesToSend.isEmpty()) {
                    return;
                }

                List<MessageItem> messageItemList = new ArrayList<>(messagesToSend);

                for (final MessageItem messageItem : messageItemList) {
                    if (!sendMessage(messageItem)) {
                        break;
                    }
                }
            }
        });
        realm.close();
    }

    private boolean sendMessage(MessageItem messageItem) {
        String text = prepareText(messageItem.getText());
        Long timestamp = messageItem.getTimestamp();

        Date currentTime = new Date(System.currentTimeMillis());
        Date delayTimestamp = null;

        if (timestamp != null) {
            if (currentTime.getTime() - timestamp > 60000) {
                delayTimestamp = currentTime;
            }
        }

        Message message = null;
        if (text != null) {
            message = createMessagePacket(text);
        }

        if (message != null) {
            ChatStateManager.getInstance().updateOutgoingMessage(AbstractChat.this, message);
            CarbonManager.getInstance().updateOutgoingMessage(AbstractChat.this, message);
            if (delayTimestamp != null) {
                message.addExtension(new DelayInformation(delayTimestamp));
            }

            final String messageId = messageItem.getUniqueId();
            try {
                StanzaSender.sendStanza(account, message, new StanzaListener() {
                    @Override
                    public void processStanza(Stanza packet) throws SmackException.NotConnectedException {
                        Realm localRealm = Realm.getDefaultInstance();
                        localRealm.executeTransaction(new Realm.Transaction() {
                                @Override
                                public void execute(Realm realm) {
                                    MessageItem acknowledgedMessage = realm
                                            .where(MessageItem.class)
                                            .equalTo(MessageItem.Fields.UNIQUE_ID, messageId)
                                            .findFirst();

                                    if (acknowledgedMessage != null) {
                                        acknowledgedMessage.setAcknowledged(true);
                                    }
                                }
                            });
                        localRealm.close();
                    }
                });
            } catch (NetworkException e) {
                return false;
            }
        }

        if (message == null) {
            messageItem.setError(true);
        } else {
            messageItem.setStanzaId(message.getStanzaId());
        }

        if (delayTimestamp != null) {
            messageItem.setDelayTimestamp(delayTimestamp.getTime());
        }
        if (messageItem.getTimestamp() == null) {
            messageItem.setTimestamp(currentTime.getTime());
        }
        messageItem.setSent(true);
        return true;
    }

    public String getThreadId() {
        return threadId;
    }

    /**
     * Update thread id with new value.
     *
     * @param threadId <code>null</code> if current value shouldn't be changed.
     */
    protected void updateThreadId(String threadId) {
        if (threadId == null) {
            return;
        }
        this.threadId = threadId;
    }

    /**
     * Processes incoming packet.
     *
     * @param userJid
     * @param packet
     * @return Whether packet was directed to this chat.
     */
    protected boolean onPacket(UserJid userJid, Stanza packet) {
        return accept(userJid);
    }

    /**
     * Connection complete.f
     */
    protected void onComplete() {
    }

    /**
     * Disconnection occured.
     */
    protected void onDisconnect() {
    }

    public Date getCreationTime() {
        return creationTime;
    }

    private void updateCreationTime() {
        creationTime.setTime(System.currentTimeMillis());
    }

    public void setIsPrivateMucChatAccepted(boolean isPrivateMucChatAccepted) {
        this.isPrivateMucChatAccepted = isPrivateMucChatAccepted;
    }

    boolean isPrivateMucChat() {
        return isPrivateMucChat;
    }

    boolean isPrivateMucChatAccepted() {
        return isPrivateMucChatAccepted;
    }

    @Override
    public void onChange(RealmResults<MessageItem> messageItems) {
        updateLastMessage();
    }
}