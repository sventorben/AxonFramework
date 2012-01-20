/*
 * Copyright (c) 2010-2011. Axon Framework
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

package org.axonframework.commandhandling.distributed.jgroups;

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.callbacks.NoOpCallback;
import org.axonframework.commandhandling.distributed.CommandBusConnector;
import org.axonframework.commandhandling.distributed.ConsistentHash;
import org.axonframework.commandhandling.distributed.RemoteCommandHandlingException;
import org.axonframework.common.Assert;
import org.axonframework.serializer.Serializer;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.View;
import org.jgroups.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A CommandBusConnector that uses JGroups to discover and connect to other JGroupsConnectors in the network. Depending
 * on the configuration of the {@link JChannel channel} that was provided, this implementation allows for a dynamic
 * discovery and addition of new members. When members disconnect, their portion of the processing is divided over the
 * remaining members.
 * <p/>
 * This connector uses a consistent hashing algorithm to route commands. This ensures that commands with the same
 * routing key will be sent to the same member, regardless of the sending member of that message.
 * <p/>
 * Members join the CommandBus using a load factor (see {@link #connect(int)}). This load factor defines the number
 * of sections on the consistent hash ring a node will receive. The more nodes on the ring, the bigger the relative
 * load a member receives. Using a higher number of hashes will also result in a more evenly distribution of load over
 * the different members.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class JGroupsConnector implements CommandBusConnector {

    private static final Logger logger = LoggerFactory.getLogger(JGroupsConnector.class);

    private final JChannel channel;
    private volatile ConsistentHash consistentHash = ConsistentHash.emptyRing();
    private final String clusterName;
    private final CommandBus localSegment;
    private final Serializer serializer;
    private final JoinCondition joinedCondition = new JoinCondition();
    private final ConcurrentMap<String, MemberAwareCommandCallback> callbacks = new ConcurrentHashMap<String, MemberAwareCommandCallback>();

    /**
     * Initializes the Connector using given resources. The <code>channel</code> is used to connect this connector to
     * the other members. The <code>clusterName</code> is the name of the cluster the channel will be connected to. For
     * local dispatching of commands, the given <code>localSegment</code> is used. When messages are remotely
     * dispatched, the given <code>serializer</code> is used to serialize and deserialize the messages.
     * <p/>
     * Note that Connectors on different members need to have the same <code>channel</code> configuration,
     * <code>clusterName</code> and <code>serializer</code> configuration in order to successfully set up a distributed
     * cluster.
     *
     * @param channel      The channel (configured, but not connected) used to discover and connect with the other
     *                     members
     * @param clusterName  The name of the cluster to connect to
     * @param localSegment The command bus on which messages with this member as destination are dispatched on
     * @param serializer   The serialized used to serialize messages before sending them to other members.
     */
    public JGroupsConnector(JChannel channel, String clusterName, CommandBus localSegment, Serializer serializer) {
        this.channel = channel;
        this.clusterName = clusterName;
        this.localSegment = localSegment;
        this.serializer = serializer;
    }

    /**
     * Connects this member to the cluster using the given <code>loadFactor</code>. The <code>loadFactor</code> defines
     * the (approximate) relative load that this member will receive.
     * <p/>
     * A good default value is 100, which will give this member 100 nodes on the distributed hash ring. Giving all
     * members (proportionally) lower values will result in a less evenly distributed hash.
     *
     * @param loadFactor The load factor for this node.
     * @throws Exception when an error occurs while connecting
     */
    public void connect(int loadFactor) throws Exception {
        Assert.isTrue(loadFactor >= 0, "Load Factor must be a positive integer value.");
        try {
            channel.setReceiver(new MessageReceiver());
            if (!channel.isConnected()) {
                channel.connect(clusterName, null, 10000);
            }
            channel.send(new Message(null, new JoinMessage(loadFactor))
                                 .setFlag(Message.Flag.RSVP));
        } catch (Exception e) {
            joinedCondition.markJoined(false);
            channel.disconnect();
            throw e;
        }
    }

    /**
     * this method blocks until this member has successfully joined the other members, until the thread is
     * interrupted, or when joining has failed.
     *
     * @return <code>true</code> if the member successfully joined, otherwise <code>false</code>.
     *
     * @throws InterruptedException when the thread is interrupted while joining
     */
    public boolean awaitJoined() throws InterruptedException {
        joinedCondition.await();
        return joinedCondition.isJoined();
    }

    /**
     * this method blocks until this member has successfully joined the other members, until the thread is
     * interrupted, when the given number of milliseconds have passed, or when joining has failed.
     *
     * @param timeout  The amount of time to wait for the connection to complete
     * @param timeUnit The time unit of the timeout
     * @return <code>true</code> if the member successfully joined, otherwise <code>false</code>.
     *
     * @throws InterruptedException when the thread is interrupted while joining
     */
    public boolean awaitJoined(long timeout, TimeUnit timeUnit) throws InterruptedException {
        joinedCondition.await(timeout, timeUnit);
        return joinedCondition.isJoined();
    }

    @Override
    public <R> void send(String routingKey, CommandMessage<?> commandMessage, CommandCallback<R> callback)
            throws Exception {
        Address dest = getAddress(consistentHash.getNodeName(routingKey));
        callbacks.put(commandMessage.getIdentifier(), new MemberAwareCommandCallback<R>(dest, callback));
        channel.send(dest, new DispatchMessage(commandMessage, serializer, true));
    }

    @Override
    public void send(String routingKey, CommandMessage<?> commandMessage) throws Exception {
        Address dest = getAddress(consistentHash.getNodeName(routingKey));
        channel.send(dest, new DispatchMessage(commandMessage, serializer, false));
    }

    @Override
    public CommandBus getLocalSegment() {
        return localSegment;
    }

    private Address getAddress(String nodeName) {
        for (Address member : channel.getView()) {
            if (channel.getName(member).equals(nodeName)) {
                return member;
            }
        }
        throw new IllegalArgumentException("Given node doesn't seem to be a member of the DistributedCommandBus");
    }

    private class MessageReceiver extends ReceiverAdapter {

        @Override
        public void getState(OutputStream ostream) throws Exception {
            Util.objectToStream(consistentHash, new DataOutputStream(ostream));
        }

        @Override
        public void setState(InputStream istream) throws Exception {
            consistentHash = (ConsistentHash) Util.objectFromStream(new DataInputStream(istream));
        }

        @Override
        public void viewAccepted(View view) {
            ConsistentHash newHash = consistentHash.withExclusively(getMemberNames(view));
            if (!consistentHash.equals(newHash)) {
                int messagesLost = 0;
                // check whether the members with outstanding callbacks are all alive
                for (Map.Entry<String, MemberAwareCommandCallback> entry : callbacks.entrySet()) {
                    if (!entry.getValue().isMemberLive(view)) {
                        MemberAwareCommandCallback callback = callbacks.remove(entry.getKey());
                        if (callback != null) {
                            messagesLost++;
                            callback.onFailure(new RemoteCommandHandlingException(
                                    "The connection with the destination was lost before the result was reported."));
                        }
                    }
                }
                consistentHash = newHash;
                logger.info("Membership has changed. Rebuilt consistent hash ring.");
                logger.debug("New distributed hash: {}", consistentHash.toString());
                if (messagesLost > 0 && logger.isWarnEnabled()) {
                    logger.warn(
                            "A member was disconnected while waiting for a reply. {} messages are lost without reply.",
                            messagesLost);
                }
            }
        }

        @Override
        public void suspect(Address mbr) {
            if (logger.isWarnEnabled()) {
                logger.warn("Suspect member: {}.", channel.getName(mbr));
            }
        }

        @Override
        public void receive(Message msg) {
            Object message = msg.getObject();
            if (message instanceof JoinMessage) {
                processJoinMessage(msg, (JoinMessage) message);
            } else if (message instanceof DispatchMessage) {
                processDispatchMessage(msg, (DispatchMessage) message);
            } else if (message instanceof ReplyMessage) {
                processReplyMessage((ReplyMessage) message);
            }
        }

        private void processDispatchMessage(final Message msg, final DispatchMessage message) {
            final CommandMessage commandMessage = message.getCommandMessage(serializer);
            CommandCallback<Object> callback;
            if (message.isExpectReply()) {
                callback = new ReplyingCallback(msg, commandMessage);
            } else {
                callback = NoOpCallback.INSTANCE;
            }
            localSegment.dispatch(commandMessage, callback);
        }

        private void processJoinMessage(Message msg, JoinMessage joinMessage) {
            consistentHash = consistentHash.withAdditionalNode(channel.getName(msg.getSrc()),
                                                               joinMessage.getLoadFactor());
            if (logger.isInfoEnabled()) {
                logger.info("{} joined with load factor: {}", msg.getSrc(), joinMessage.getLoadFactor());
            }

            if (msg.getSrc().equals(channel.getAddress())) {
                joinedCondition.markJoined(true);
                logger.info("Local segment successfully joined the distributed command bus");
            }
        }

        @SuppressWarnings("unchecked")
        private void processReplyMessage(ReplyMessage replyMessage) {
            MemberAwareCommandCallback callback = callbacks.remove(replyMessage.getCommandIdentifier());
            if (callback != null) {
                if (replyMessage.isSuccess()) {
                    callback.onSuccess(replyMessage.getReturnValue(serializer));
                } else {
                    callback.onFailure(replyMessage.getError(serializer));
                }
            }
        }

        private class ReplyingCallback implements CommandCallback<Object> {

            private final Message msg;
            private final CommandMessage commandMessage;

            public ReplyingCallback(Message msg, CommandMessage commandMessage) {
                this.msg = msg;
                this.commandMessage = commandMessage;
            }

            @Override
            public void onSuccess(Object result) {
                try {
                    channel.send(msg.getSrc(), new ReplyMessage(commandMessage.getIdentifier(),
                                                                result,
                                                                null, serializer));
                } catch (Exception e) {
                    logger.error("Unable to send reply to command [type: {}, id: {}]. ",
                                 new Object[]{commandMessage.getPayloadType().getSimpleName(),
                                         commandMessage.getIdentifier(),
                                         e});
                }
            }

            @Override
            public void onFailure(Throwable cause) {
                try {
                    channel.send(msg.getSrc(), new ReplyMessage(commandMessage.getIdentifier(),
                                                                null,
                                                                cause, serializer));
                } catch (Exception e) {
                    logger.error("Unable to send reply:", e);
                }
            }
        }
    }

    private List<String> getMemberNames(View view) {
        List<String> memberNames = new ArrayList<String>(view.size());
        for (Address member : view.getMembers()) {
            memberNames.add(channel.getName(member));
        }
        return memberNames;
    }

    private static final class JoinCondition {

        private final CountDownLatch joinCountDown = new CountDownLatch(1);
        private volatile boolean success;

        public void await() throws InterruptedException {
            joinCountDown.await();
        }

        public void await(long timeout, TimeUnit timeUnit) throws InterruptedException {
            joinCountDown.await(timeout, timeUnit);
        }

        private void markJoined(boolean joinSucceeded) {
            this.success = joinSucceeded;
            joinCountDown.countDown();
        }

        public boolean isJoined() {
            return success;
        }
    }

    private static class MemberAwareCommandCallback<R> implements CommandCallback<R> {

        private final Address dest;
        private final CommandCallback<R> callback;

        public MemberAwareCommandCallback(Address dest, CommandCallback<R> callback) {
            this.dest = dest;
            this.callback = callback;
        }

        public boolean isMemberLive(View currentView) {
            return currentView.containsMember(dest);
        }

        @Override
        public void onSuccess(R result) {
            callback.onSuccess(result);
        }

        @Override
        public void onFailure(Throwable cause) {
            callback.onFailure(cause);
        }
    }
}