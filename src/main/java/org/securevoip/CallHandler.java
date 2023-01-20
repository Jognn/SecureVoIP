/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.securevoip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol handler for 1 to 1 video call communication.
 */
public class CallHandler extends TextWebSocketHandler
{
    private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
    private static final Gson gson = new GsonBuilder().create();

    private final ConcurrentHashMap<String, CallMediaPipeline> pipelines = new ConcurrentHashMap<>();

    @Autowired
    private KurentoClient kurento;

    @Autowired
    private UserRegistry registry;

    @Override
    public void handleTextMessage(final WebSocketSession session, final TextMessage message) throws Exception
    {
        final JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        final UserSession user = registry.getBySession(session);

        if (user != null)
        {
            log.debug("Incoming message from user '{}': {}", user.getName(), jsonMessage);
        }
        else
        {
            log.debug("Incoming message from new user: {}", jsonMessage);
        }

        switch (jsonMessage.get("id").getAsString())
        {
            case "register":
                try
                {
                    register(session, jsonMessage);
                }
                catch (final Throwable t)
                {
                    handleErrorResponse(t, session, "registerResponse");
                }
                break;
            case "call":
                try
                {
                    call(user, jsonMessage);
                }
                catch (final Throwable t)
                {
                    handleErrorResponse(t, session, "callResponse");
                }
                break;
            case "incomingCallResponse":
                incomingCallResponse(user, jsonMessage);
                break;
            case "onIceCandidate":
            {
                final JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();
                if (user != null)
                {
                    final IceCandidate cand =
                            new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                                    .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
                    user.addCandidate(cand);
                }
                break;
            }
            case "stop":
                stop(session);
                break;
            default:
                break;
        }
    }

    private void handleErrorResponse(final Throwable throwable, final WebSocketSession session, final String responseId)
            throws IOException
    {
        stop(session);
        log.error(throwable.getMessage(), throwable);
        final JsonObject response = new JsonObject();
        response.addProperty("id", responseId);
        response.addProperty("response", "rejected");
        response.addProperty("message", throwable.getMessage());
        session.sendMessage(new TextMessage(response.toString()));
    }

    private void register(final WebSocketSession session, final JsonObject jsonMessage) throws IOException
    {
        final String name = jsonMessage.getAsJsonPrimitive("name").getAsString();

        final UserSession caller = new UserSession(session, name);
        String responseMsg = "accepted";
        if (name.isEmpty())
        {
            responseMsg = "rejected: empty user name";
        }
        else if (registry.exists(name))
        {
            responseMsg = "rejected: user '" + name + "' already registered";
        }
        else
        {
            registry.register(caller);
        }

        final JsonObject response = new JsonObject();
        response.addProperty("id", "registerResponse");
        response.addProperty("response", responseMsg);
        caller.sendMessage(response);
    }

    private EventListener<IceCandidateFoundEvent> createFoundIceCandidateEventListenerForUser(final UserSession user)
    {
        return event -> {
            final JsonObject response = new JsonObject();
            response.addProperty("id", "iceCandidate");
            response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
            try
            {
                synchronized (user.getSession())
                {
                    user.getSession().sendMessage(new TextMessage(response.toString()));
                }
            }
            catch (final IOException e)
            {
                log.debug(e.getMessage());
            }
        };
    }

    private void call(final UserSession caller, final JsonObject jsonMessage) throws IOException
    {
        final String to = jsonMessage.get("to").getAsString();
        final String from = jsonMessage.get("from").getAsString();
        final boolean isVideoCall = jsonMessage.get("isVideoCall").getAsBoolean();
        final JsonObject response = new JsonObject();

        if (registry.exists(to))
        {
            caller.setSdpOffer(jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
            caller.setCallingTo(to);

            response.addProperty("id", "incomingCall");
            response.addProperty("from", from);
            response.addProperty("isVideoCall", isVideoCall);

            final UserSession callee = registry.getByName(to);
            callee.sendMessage(response);
            callee.setCallingFrom(from);
        }
        else
        {
            response.addProperty("id", "callResponse");
            response.addProperty("response", "rejected: user '" + to + "' is not registered");

            caller.sendMessage(response);
        }
    }

    private void incomingCallResponse(final UserSession callee, final JsonObject jsonMessage)
            throws IOException
    {
        final String callResponse = jsonMessage.get("callResponse").getAsString();
        final String from = jsonMessage.get("from").getAsString();
        final UserSession caller = registry.getByName(from);
        final String to = caller.getCallingTo();

        if ("accept".equals(callResponse))
        {
            log.debug("Accepted call from '{}' to '{}'", from, to);

            CallMediaPipeline pipeline = null;
            try
            {
                pipeline = new CallMediaPipeline(kurento);
                pipelines.put(caller.getSessionId(), pipeline);
                pipelines.put(callee.getSessionId(), pipeline);

                callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEp());
                pipeline.getCalleeWebRtcEp().addIceCandidateFoundListener(
                        createFoundIceCandidateEventListenerForUser(callee));

                caller.setWebRtcEndpoint(pipeline.getCallerWebRtcEp());
                pipeline.getCallerWebRtcEp().addIceCandidateFoundListener(
                        createFoundIceCandidateEventListenerForUser(caller));

                final String calleeSdpOffer = jsonMessage.get("sdpOffer").getAsString();
                final String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
                final JsonObject startCommunication = new JsonObject();
                startCommunication.addProperty("id", "startCommunication");
                startCommunication.addProperty("sdpAnswer", calleeSdpAnswer);

                synchronized (callee)
                {
                    callee.sendMessage(startCommunication);
                }

                pipeline.getCalleeWebRtcEp().gatherCandidates();

                final String callerSdpOffer = registry.getByName(from).getSdpOffer();
                final String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);
                final JsonObject response = new JsonObject();
                response.addProperty("id", "callResponse");
                response.addProperty("response", "accepted");
                response.addProperty("sdpAnswer", callerSdpAnswer);

                synchronized (caller)
                {
                    caller.sendMessage(response);
                }

                pipeline.getCallerWebRtcEp().gatherCandidates();

            }
            catch (final Throwable t)
            {
                log.error(t.getMessage(), t);

                if (pipeline != null)
                {
                    pipeline.release();
                }

                pipelines.remove(caller.getSessionId());
                pipelines.remove(callee.getSessionId());

                JsonObject response = new JsonObject();
                response.addProperty("id", "callResponse");
                response.addProperty("response", "rejected");
                caller.sendMessage(response);

                response = new JsonObject();
                response.addProperty("id", "stopCommunication");
                callee.sendMessage(response);
            }

        }
        else
        {
            final JsonObject response = new JsonObject();
            response.addProperty("id", "callResponse");
            response.addProperty("response", "rejected");
            caller.sendMessage(response);
        }
    }

    public void stop(final WebSocketSession session) throws IOException
    {
        final String sessionId = session.getId();
        if (pipelines.containsKey(sessionId))
        {
            pipelines.get(sessionId).release();
            final CallMediaPipeline pipeline = pipelines.remove(sessionId);
            pipeline.release();

            // Both users can stop the communication. A 'stopCommunication'
            // message will be sent to the other peer.
            final UserSession stopperUser = registry.getBySession(session);
            if (stopperUser != null)
            {
                final UserSession stoppedUser =
                        (stopperUser.getCallingFrom() != null) ? registry.getByName(stopperUser
                                .getCallingFrom()) : stopperUser.getCallingTo() != null ? registry
                                .getByName(stopperUser.getCallingTo()) : null;

                if (stoppedUser != null)
                {
                    final JsonObject message = new JsonObject();
                    message.addProperty("id", "stopCommunication");
                    stoppedUser.sendMessage(message);
                    stoppedUser.clear();
                }
                stopperUser.clear();
            }

        }
    }

    @Override
    public void afterConnectionClosed(final WebSocketSession session, final CloseStatus status) throws Exception
    {
        stop(session);
        registry.removeBySession(session);
    }

}
