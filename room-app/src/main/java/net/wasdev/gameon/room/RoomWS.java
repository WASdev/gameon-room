/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.room;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.eclipse.microprofile.opentracing.Traced;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.eclipse.microprofile.metrics.annotation.Counted;

import net.wasdev.gameon.room.engine.Room;

/**
 * WebSocket endpoint for player's interacting with the room
 */
public class RoomWS extends Endpoint {

    private final Room room;
    private final LifecycleManager.SessionRoomResponseProcessor srrp;
    private Map<Session, MessageHandler.Whole<String>> handlersBySession = new ConcurrentHashMap<Session, MessageHandler.Whole<String>>();

    public RoomWS(Room room, LifecycleManager.SessionRoomResponseProcessor srrp) {
        this.room = room;
        this.srrp = srrp;
    }

    private static class SessionMessageHandler implements MessageHandler.Whole<String> {
        private final Session session;
        private final RoomWS owner;

        public SessionMessageHandler(Session session, RoomWS owner) {
            this.session = session;
            this.owner = owner;
        }

        @Override
        public void onMessage(String message) {
            try {
                owner.receiveMessage(message, session);
            } catch (IOException io) {
                Log.log(Level.SEVERE, this, "IO Exception sending message to session", io);
            }
        }
    }

    @Override
    @Traced
    @Timed(name = "websocket_onOpen_timer",
        reusable = true,
        tags = "label=websocket")
     @Counted(name = "websocket_onOpen_count",
         monotonic = true,
         reusable = true,
         tags = "label=websocket")
     @Metered(name = "websocket_onOpen_meter",
         reusable = true,
         tags = "label=websocket")
    public void onOpen(final Session session, EndpointConfig ec) {
        Log.log(Level.FINE,this, "onOpen called against room " + this.room.getRoomId());

        //send ack
        try{
            JsonObjectBuilder ack = Json.createObjectBuilder();
            JsonArrayBuilder versions = Json.createArrayBuilder();
            versions.add(1);
            ack.add("version", versions.build());
            String msg = "ack," + ack.build().toString();
            Log.log(Level.FINE, this, "ROOM(ack): sending to session {0} messsage {1}", session.getId(), msg);
            session.getBasicRemote().sendText(msg);
        }catch(IOException io){
            Log.log(Level.WARNING, this, "Error sending ack",io);
        }

        //session debug.
        debugDumpSessionInfo();

        // (lifecycle) Called when the connection is opened
        srrp.addSession(session);

        //add handler if needed, or use existing one.
        MessageHandler.Whole<String> handlerForSession = new SessionMessageHandler(session, this);
        MessageHandler.Whole<String> fromMap = handlersBySession.get(session);
        MessageHandler.Whole<String> chosen = fromMap != null ? fromMap : handlerForSession;
        handlersBySession.put(session, chosen);

        session.addMessageHandler(String.class, chosen);

        //session debug.
        Log.log(Level.FINE,this, "after opOpen room " + this.room.getRoomId());
        debugDumpSessionInfo();
    }

    private void debugDumpSessionInfo() {
        if (srrp.getSessions().size() == 0) {
            Log.log(Level.FINE,this, " No sessions known.");
        }
        for (Session s : srrp.getSessions()) {
            Log.log(Level.FINE,this, " Session: " + s.getId());
            Log.log(Level.FINE,this, "   handlers: " + s.getMessageHandlers().size());
            int mhc = 0;
            for (MessageHandler m : s.getMessageHandlers()) {
                if (m instanceof SessionMessageHandler) {
                    SessionMessageHandler smh = (SessionMessageHandler) m;
                    Log.log(Level.FINE,this, "    [" + mhc + "] SessionMessageHandler for session " + smh.session.getId()
                            + " linked to room " + smh.owner.room.getRoomId());
                } else {
                    Log.log(Level.FINE,this, "    [" + mhc + "] unknown handler");
                }
                mhc++;
            }
        }
    }

    @Override
    @Traced
    @Timed(name = "websocket_onClose_timer",
        reusable = true,
        tags = "label=websocket")
     @Counted(name = "websocket_onClose_count",
         monotonic = true,
         reusable = true,
         tags = "label=websocket")
     @Metered(name = "websocket_onClose_meter",
         reusable = true,
         tags = "label=websocket")
    public void onClose(Session session, CloseReason reason) {
        // (lifecycle) Called when the connection is closed, treat this as the
        // player has left the room
        srrp.removeSession(session);
        MessageHandler handler = handlersBySession.remove(session);
        if (handler != null) {
            session.removeMessageHandler(handler);
        }

        Log.log(Level.FINE,this, "onClose called against room " + this.room.getRoomId());
        for (Session s : srrp.getSessions()) {
            Log.log(Level.FINE,this, " Session: " + s.getId());
            Log.log(Level.FINE,this, "   handlers: " + s.getMessageHandlers().size());
            int mhc = 0;
            for (MessageHandler m : s.getMessageHandlers()) {
                if (m instanceof SessionMessageHandler) {
                    SessionMessageHandler smh = (SessionMessageHandler) m;
                    Log.log(Level.FINE,this, "    [" + mhc + "] SessionMessageHandler for session " + smh.session.getId()
                            + " linked to room " + smh.owner.room.getRoomId());
                } else {
                    Log.log(Level.FINE,this, "    [" + mhc + "] unknown handler");
                }
                mhc++;
            }
        }
    }

    @Traced
    @Timed(name = "receiveMessage_timer",
        reusable = true,
        tags = "label=websocket")
     @Counted(name = "receiveMessage_count",
         monotonic = true,
         reusable = true,
         tags = "label=websocket")
     @Metered(name = "receiveMessage_meter",
         reusable = true,
         tags = "label=websocket")
    public void receiveMessage(String message, Session session) throws IOException {
        Log.log(Level.FINE, this, "ROOMX: [{0}:{1}] sess[{2}:{3}] : {4}", this.hashCode(),this.room.getRoomId(),session.hashCode(),session.getId(),message);
        String[] contents = Message.splitRouting(message);
        if (contents[0].equals("roomHello")) {
            addNewPlayer(session, contents[2]);
            return;
        }
        if (contents[0].equals("room")) {
            processCommand(contents[2]);
            return;
        }
        if (contents[0].equals("roomGoodbye")) {
            removePlayer(session, contents[2]);
            return;
        }
        Log.log(Level.SEVERE, this, "Unknown Message Type {0} for room {1} message {2}", contents[0], room.getRoomId(),message);
    }

    // process a command
    private void processCommand(String json) throws IOException {
        Log.log(Level.FINE,this, "Command received from the user, " + this);
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();

        String content = Message.getValue(msg.get("content"));
        String userid = Message.getValue(msg.get(Constants.USERID));

        if (content.startsWith("/")) {
            room.command(userid, content.substring(1));
        } else {
            String username = Message.getValue(msg.get(Constants.USERNAME));
            if(username==null){
                Log.log(Level.WARNING, this, "Recieved chat msg with missing username : {0}", json);
                username = userid;
            }
            // everything else is chat.
            srrp.chatEvent(username, content);
        }
    }

    // add a new player to the room
    @Traced
    @Timed(name = "addNewPlayer_timer",
        reusable = true,
        tags = "label=websocket")
     @Counted(name = "addNewPlayer_count",
         monotonic = true,
         reusable = true,
         tags = "label=websocket")
     @Metered(name = "addNewPlayer_meter",
         reusable = true,
         tags = "label=websocket")
    private void addNewPlayer(Session session, String json) throws IOException {

        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = Message.getValue(msg.get(Constants.USERNAME));
        String userid = Message.getValue(msg.get(Constants.USERID));

        Log.log(Level.INFO, this, "*** Adding player {0} from room {1} via session {2}", userid,room.getRoomId(),session.getId());

        room.addUserToRoom(userid, username);
        room.command(userid, "look");
    }

    @Traced
    @Timed(name = "removePlayer_timer",
        reusable = true,
        tags = "label=websocket")
     @Counted(name = "removePlayer_count",
         monotonic = true,
         reusable = true,
         tags = "label=websocket")
     @Metered(name = "removePlayer_meter",
         reusable = true,
         tags = "label=websocket")
    private void removePlayer(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String userid = Message.getValue(msg.get(Constants.USERID));
        Log.log(Level.INFO, this, "*** Removing player {0} from room {1} via session {2}", userid,room.getRoomId(),session.getId());
        room.removeUserFromRoom(userid);
    }

    @Override
    @Traced
    @Timed(name = "websocket_onError_timer",
        reusable = true,
        tags = "label=websocket")
     @Counted(name = "websocket_onError_count",
         monotonic = true,
         reusable = true,
         tags = "label=websocket")
     @Metered(name = "websocket_onError_meter",
         reusable = true,
         tags = "label=websocket")
    public void onError(Session session, Throwable thr) {
        // (lifecycle) Called if/when an error occurs and the connection is
        // disrupted
        Log.log(Level.WARNING,this,"onError called on WebSocket",thr);
    }

}
