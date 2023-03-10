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

import com.google.gson.JsonObject;
import org.kurento.client.IceCandidate;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * User session.
 */
public class UserSession
{
    private static final Logger log = LoggerFactory.getLogger(UserSession.class);

    private final String name;
    private final WebSocketSession session;

    private String sdpOffer;
    private String callingTo;
    private String callingFrom;
    private WebRtcEndpoint webRtcEndpoint;
    private final List<IceCandidate> candidateList = new ArrayList<>();

    public UserSession(final WebSocketSession session, final String name)
    {
        this.session = session;
        this.name = name;
    }

    public WebSocketSession getSession()
    {
        return session;
    }

    public String getName()
    {
        return name;
    }

    public String getSdpOffer()
    {
        return sdpOffer;
    }

    public void setSdpOffer(final String sdpOffer)
    {
        this.sdpOffer = sdpOffer;
    }

    public String getCallingTo()
    {
        return callingTo;
    }

    public void setCallingTo(final String callingTo)
    {
        this.callingTo = callingTo;
    }

    public String getCallingFrom()
    {
        return callingFrom;
    }

    public void setCallingFrom(final String callingFrom)
    {
        this.callingFrom = callingFrom;
    }

    public void sendMessage(final JsonObject message) throws IOException
    {
        log.debug("Sending message to user '{}': {}", name, message);
        session.sendMessage(new TextMessage(message.toString()));
    }

    public String getSessionId()
    {
        return session.getId();
    }

    public void setWebRtcEndpoint(final WebRtcEndpoint webRtcEndpoint)
    {
        this.webRtcEndpoint = webRtcEndpoint;

        for (final IceCandidate e : candidateList)
        {
            this.webRtcEndpoint.addIceCandidate(e);
        }
        this.candidateList.clear();
    }

    public void addCandidate(final IceCandidate candidate)
    {
        if (this.webRtcEndpoint != null)
        {
            this.webRtcEndpoint.addIceCandidate(candidate);
        }
        else
        {
            candidateList.add(candidate);
        }
    }

    public void clear()
    {
        this.webRtcEndpoint = null;
        this.candidateList.clear();
    }
}
