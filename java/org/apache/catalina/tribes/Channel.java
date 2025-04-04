/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.tribes;

import java.io.Serializable;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.catalina.tribes.group.interceptors.MessageDispatchInterceptor;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * A channel is a representation of a group of nodes all participating in some sort of communication with each other.
 * <p>
 * The channel is the main API class for Tribes, this is essentially the only class that an application needs to be
 * aware of. Through the channel the application can:
 * <ul>
 * <li>send messages</li>
 * <li>receive message (by registering a <code>ChannelListener</code></li>
 * <li>get all members of the group <code>getMembers()</code></li>
 * <li>receive notifications of members added and members disappeared by registering a
 * <code>MembershipListener</code></li>
 * </ul>
 * The channel has 5 major components:
 * <ul>
 * <li>Data receiver, with a built-in thread pool to receive messages from other peers</li>
 * <li>Data sender, an implementation for sending data using NIO or java.io</li>
 * <li>Membership listener,listens for membership broadcasts</li>
 * <li>Membership broadcaster, broadcasts membership pings.</li>
 * <li>Channel interceptors, the ability to manipulate messages as they are sent or arrive</li>
 * </ul>
 * The channel layout is:
 *
 * <pre>
 * <code>
 *  ChannelListener_1..ChannelListener_N MembershipListener_1..MembershipListener_N [Application Layer]
 *            \          \                  /                   /
 *             \          \                /                   /
 *              \          \              /                   /
 *               \          \            /                   /
 *                \          \          /                   /
 *                 \          \        /                   /
 *                  ---------------------------------------
 *                                  |
 *                                  |
 *                               Channel
 *                                  |
 *                         ChannelInterceptor_1
 *                                  |                                               [Channel stack]
 *                         ChannelInterceptor_N
 *                                  |
 *                             Coordinator (implements MessageListener,MembershipListener,ChannelInterceptor)
 *                          --------------------
 *                         /        |           \
 *                        /         |            \
 *                       /          |             \
 *                      /           |              \
 *                     /            |               \
 *           MembershipService ChannelSender ChannelReceiver                        [IO layer]
 * </code>
 * </pre>
 *
 * @see org.apache.catalina.tribes.group.GroupChannel example usage
 */
public interface Channel {

    /**
     * Start and stop sequences can be controlled by these constants. This allows you to start separate components of
     * the channel.
     * <p>
     * DEFAULT - starts or stops all components in the channel
     *
     * @see #start(int)
     * @see #stop(int)
     */
    int DEFAULT = 15;

    /**
     * Start and stop sequences can be controlled by these constants. This allows you to start separate components of
     * the channel.
     * <p>
     * SND_RX_SEQ - starts or stops the data receiver. Start means opening a server socket in case of a TCP
     * implementation
     *
     * @see #start(int)
     * @see #stop(int)
     */
    int SND_RX_SEQ = 1;

    /**
     * Start and stop sequences can be controlled by these constants. This allows you to start separate components of
     * the channel.
     * <p>
     * SND_TX_SEQ - starts or stops the data sender. This should not open any sockets, as sockets are opened on demand
     * when a message is being sent
     *
     * @see #start(int)
     * @see #stop(int)
     */
    int SND_TX_SEQ = 2;

    /**
     * Start and stop sequences can be controlled by these constants. This allows you to start separate components of
     * the channel.
     * <p>
     * MBR_RX_SEQ - starts or stops the membership listener. In a multicast implementation this will open a datagram
     * socket and join a group and listen for membership messages members joining
     *
     * @see #start(int)
     * @see #stop(int)
     */
    int MBR_RX_SEQ = 4;

    /**
     * Start and stop sequences can be controlled by these constants. This allows you to start separate components of
     * the channel.
     * <p>
     * MBR_TX_SEQ - starts or stops the membership broadcaster. In a multicast implementation this will open a datagram
     * socket and join a group and broadcast the local member information
     *
     * @see #start(int)
     * @see #stop(int)
     */
    int MBR_TX_SEQ = 8;

    /**
     * Send options, when a message is sent, it can have an option flag to trigger certain behavior. Most flags are used
     * to trigger channel interceptors as the message passes through the channel stack.
     * <p>
     * However, there are five default flags that every channel implementation must implement.
     * <p>
     * SEND_OPTIONS_BYTE_MESSAGE - The message is a pure byte message and no marshaling or unmarshalling will be
     * performed.
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_BYTE_MESSAGE = 0x0001;

    /**
     * Send options, when a message is sent, it can have an option flag to trigger certain behavior. Most flags are used
     * to trigger channel interceptors as the message passes through the channel stack.
     * <p>
     * However, there are five default flags that every channel implementation must implement
     * <p>
     * SEND_OPTIONS_USE_ACK - Message is sent and an ACK is received when the message has been received by the
     * recipient. If no ack is received, the message is not considered successful.
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_USE_ACK = 0x0002;

    /**
     * Send options, when a message is sent, it can have an option flag to trigger certain behavior. Most flags are used
     * to trigger channel interceptors as the message passes through the channel stack.
     * <p>
     * However, there are five default flags that every channel implementation must implement
     * <p>
     * SEND_OPTIONS_SYNCHRONIZED_ACK - Message is sent and an ACK is received when the message has been received and
     * processed by the recipient. If no ack is received, the message is not considered successful
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_SYNCHRONIZED_ACK = 0x0004;

    /**
     * Send options, when a message is sent, it can have an option flag to trigger certain behavior. Most flags are used
     * to trigger channel interceptors as the message passes through the channel stack.
     * <p>
     * However, there are five default flags that every channel implementation must implement
     * <p>
     * SEND_OPTIONS_ASYNCHRONOUS - Message will be placed on a queue and sent by a separate thread. If the queue is
     * full, behaviour depends on {@link MessageDispatchInterceptor#isAlwaysSend()}
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_ASYNCHRONOUS = 0x0008;

    /**
     * Send options, when a message is sent, it can have an option flag to trigger certain behavior. Most flags are used
     * to trigger channel interceptors as the message passes through the channel stack.
     * <p>
     * However, there are five default flags that every channel implementation must implement
     * <p>
     * SEND_OPTIONS_SECURE - Message is sent over an encrypted channel
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_SECURE = 0x0010;

    /**
     * Send options. When a message is sent with this flag on the system sends the message using UDP instead of TCP.
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_UDP = 0x0020;

    /**
     * Send options. When a message is sent with this flag on the system sends a UDP message on the Multicast address
     * instead of UDP or TCP to individual addresses.
     *
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_MULTICAST = 0x0040;

    /**
     * Send options, when a message is sent, it can have an option flag to trigger certain behavior. Most flags are used
     * to trigger channel interceptors as the message passes through the channel stack.
     * <p>
     * However, there are five default flags that every channel implementation must implement
     * <p>
     * SEND_OPTIONS_DEFAULT - the default sending options, just a helper variable. The default is
     * <code>SEND_OPTIONS_USE_ACK</code>
     *
     * @see #SEND_OPTIONS_USE_ACK
     * @see #send(Member[], Serializable , int)
     * @see #send(Member[], Serializable, int, ErrorHandler)
     */
    int SEND_OPTIONS_DEFAULT = SEND_OPTIONS_USE_ACK;


    /**
     * Adds an interceptor to the stack for message processing. Interceptors are ordered in the way they are added.
     *
     * <pre>
     * <code>
     * channel.addInterceptor(A);
     * channel.addInterceptor(C);
     * channel.addInterceptor(B);
     * </code>
     * </pre>
     *
     * Will result in an interceptor stack like this: <code>A -&gt; C -&gt; B</code>
     * <p>
     * The complete stack will look like this: <code>Channel -&gt; A -&gt; C -&gt; B -&gt; ChannelCoordinator</code>
     *
     * @param interceptor ChannelInterceptorBase
     */
    void addInterceptor(ChannelInterceptor interceptor);

    /**
     * Starts up the channel. This can be called multiple times for individual services to start The svc parameter can
     * be the logical or value of any constants.
     *
     * @param svc one of:
     *                <ul>
     *                <li>DEFAULT - will start all services</li>
     *                <li>MBR_RX_SEQ - starts the membership receiver</li>
     *                <li>MBR_TX_SEQ - starts the membership broadcaster</li>
     *                <li>SND_TX_SEQ - starts the replication transmitter</li>
     *                <li>SND_RX_SEQ - starts the replication receiver</li>
     *                </ul>
     *                <b>Note:</b> In order for the membership broadcaster to transmit the correct information, it has
     *                to be started after the replication receiver.
     *
     * @throws ChannelException if a startup error occurs or the service is already started or an error occurs.
     */
    void start(int svc) throws ChannelException;

    /**
     * Shuts down the channel. This can be called multiple times for individual services to shut down.
     * The svc parameter can be the logical or value of any constants
     *
     * @param svc one of:
     *                <ul>
     *                <li>DEFAULT - will shut down all services</li>
     *                <li>MBR_RX_SEQ - stops the membership receiver</li>
     *                <li>MBR_TX_SEQ - stops the membership broadcaster</li>
     *                <li>SND_TX_SEQ - stops the replication transmitter</li>
     *                <li>SND_RX_SEQ - stops the replication receiver</li>
     *                </ul>
     *
     * @throws ChannelException if a startup error occurs or the service is already stopped or an error occurs.
     */
    void stop(int svc) throws ChannelException;

    /**
     * Send a message to one or more members in the cluster
     *
     * @param destination Member[] - the destinations, cannot be null or zero length, the reason for that is that a
     *                        membership change can occur and at that time the application is uncertain what group the
     *                        message actually got sent to.
     * @param msg         Serializable - the message to send, has to be serializable, or a <code>ByteMessage</code> to
     *                        send a pure byte array
     * @param options     int - sender options, see class documentation for each interceptor that is configured in order
     *                        to trigger interceptors
     *
     * @return a unique Id that identifies the message that is sent
     *
     * @throws ChannelException if a serialization error happens.
     *
     * @see ByteMessage
     * @see #SEND_OPTIONS_USE_ACK
     * @see #SEND_OPTIONS_ASYNCHRONOUS
     * @see #SEND_OPTIONS_SYNCHRONIZED_ACK
     */
    UniqueId send(Member[] destination, Serializable msg, int options) throws ChannelException;

    /**
     * Send a message to one or more members in the cluster
     *
     * @param destination Member[] - the destinations, null or zero length means all
     * @param msg         ClusterMessage - the message to send
     * @param options     int - sender options, see class documentation
     * @param handler     ErrorHandler - handle errors through a callback, rather than throw it
     *
     * @return a unique Id that identifies the message that is sent
     *
     * @exception ChannelException - if a serialization error happens.
     */
    UniqueId send(Member[] destination, Serializable msg, int options, ErrorHandler handler) throws ChannelException;

    /**
     * Sends a heart beat through the interceptor stacks. Use this method to alert interceptors and other components to
     * clean up garbage, timed out messages etc.
     * <p>
     * If your application has a background thread, then you can save one thread, by configuring your channel to not use
     * an internal heartbeat thread and invoking this method.
     *
     * @see #setHeartbeat(boolean)
     */
    void heartbeat();

    /**
     * Enables or disables internal heartbeat.
     *
     * @param enable boolean - default value is implementation specific
     *
     * @see #heartbeat()
     */
    void setHeartbeat(boolean enable);

    /**
     * Add a membership listener, will get notified when a new member joins, leaves or crashes.
     * <p>
     * If the membership listener implements the Heartbeat interface the <code>heartbeat()</code> method will be invoked
     * when the heartbeat runs on the channel
     *
     * @param listener MembershipListener
     *
     * @see MembershipListener
     */
    void addMembershipListener(MembershipListener listener);

    /**
     * Add a channel listener, this is a callback object when messages are received.
     * <p>
     * If the channel listener implements the Heartbeat interface the <code>heartbeat()</code> method will be invoked
     * when the heartbeat runs on the channel
     *
     * @param listener ChannelListener
     *
     * @see ChannelListener
     * @see Heartbeat
     */
    void addChannelListener(ChannelListener listener);

    /**
     * Remove a membership listener, listeners are removed based on {@link Object#hashCode()} and
     * {@link Object#equals(Object)}.
     *
     * @param listener MembershipListener
     *
     * @see MembershipListener
     */
    void removeMembershipListener(MembershipListener listener);

    /**
     * Remove a channel listener, listeners are removed based on {@link Object#hashCode()} and
     * {@link Object#equals(Object)}.
     *
     * @param listener ChannelListener
     *
     * @see ChannelListener
     */
    void removeChannelListener(ChannelListener listener);

    /**
     * Returns true if there are any members in the group. This call is the same as
     * <code>getMembers().length &gt; 0</code>
     *
     * @return boolean - true if there are any members automatically discovered
     */
    boolean hasMembers();

    /**
     * Get all current group members.
     *
     * @return all members or empty array, never null
     */
    Member[] getMembers();

    /**
     * Return the member that represents this node. This is also the data that gets broadcast through the membership
     * broadcaster component
     *
     * @param incAlive - optimization, true if you want it to calculate alive time since the membership service started.
     *
     * @return Member
     */
    Member getLocalMember(boolean incAlive);

    /**
     * Returns the member from the membership service with complete and recent data. Some implementations might
     * serialize and send membership information along with a message, and instead of sending complete membership
     * details, only send the primary identifier for the member but not the payload or other information. When such
     * message is received the application can retrieve the cached member through this call. In most cases, this is not
     * necessary.
     *
     * @param mbr Member
     *
     * @return Member
     */
    Member getMember(Member mbr);

    /**
     * Return the name of this channel.
     *
     * @return channel name
     */
    String getName();

    /**
     * Set the name of this channel
     *
     * @param name The new channel name
     */
    void setName(String name);

    /**
     * Return executor that can be used for utility tasks.
     *
     * @return the executor
     */
    ScheduledExecutorService getUtilityExecutor();

    /**
     * Set the executor that can be used for utility tasks.
     *
     * @param utilityExecutor the executor
     */
    void setUtilityExecutor(ScheduledExecutorService utilityExecutor);

    /**
     * Translates the name of an option to its integer value. Valid option names are "asynchronous" (alias "async"),
     * "byte_message" (alias "byte"), "multicast", "secure", "synchronized_ack" (alias "sync"), "udp", "use_ack"
     *
     * @param opt The name of the option
     *
     * @return the int value of the passed option name
     */
    static int getSendOptionValue(String opt) {

        switch (opt) {

            case "asynchronous":
            case "async":
                return SEND_OPTIONS_ASYNCHRONOUS;

            case "byte_message":
            case "byte":
                return SEND_OPTIONS_BYTE_MESSAGE;

            case "multicast":
                return SEND_OPTIONS_MULTICAST;

            case "secure":
                return SEND_OPTIONS_SECURE;

            case "synchronized_ack":
            case "sync":
                return SEND_OPTIONS_SYNCHRONIZED_ACK;

            case "udp":
                return SEND_OPTIONS_UDP;

            case "use_ack":
                return SEND_OPTIONS_USE_ACK;
        }

        throw new IllegalArgumentException(String.format("[%s] is not a valid option", opt));
    }

    /**
     * Translates a comma separated list of option names to their bitwise-ORd value
     *
     * @param input A comma separated list of options, e.g. "async, multicast"
     *
     * @return a bitwise ORd value of the passed option names
     */
    static int parseSendOptions(String input) {

        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException nfe) {
            final Log log = LogFactory.getLog(Channel.class);
            log.trace(String.format("Failed to parse [%s] as integer, channelSendOptions possibly set by name(s)",
                    input));
        }

        String[] options = input.split("\\s*,\\s*");

        int result = 0;
        for (String opt : options) {
            result |= getSendOptionValue(opt);
        }

        return result;
    }

    /**
     * Translates an integer value of SendOptions to its human-friendly comma separated value list for use in JMX and
     * such.
     *
     * @param input the int value of SendOptions
     *
     * @return the human-friendly string representation in a reverse order (i.e. the last option will be shown first)
     */
    static String getSendOptionsAsString(int input) {

        // allOptionNames must be in order of the bits of the available options
        final String[] allOptionNames =
                new String[] { "byte", "use_ack", "sync", "async", "secure", "udp", "multicast" };

        StringJoiner names = new StringJoiner(", ");
        for (int bit = allOptionNames.length - 1; bit >= 0; bit--) {

            // if the bit is set then add the name to the result
            if (((1 << bit) & input) > 0) {
                names.add(allOptionNames[bit]);
            }
        }

        return names.toString();
    }

}
