package io.kodokojo.entrypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.kodokojo.Launcher;
import io.kodokojo.entrypoint.dto.WebSocketMessage;
import io.kodokojo.entrypoint.dto.WebSocketMessageGsonAdapter;
import io.kodokojo.model.ProjectConfiguration;
import io.kodokojo.model.User;
import io.kodokojo.service.*;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket
public class WebSocketEntrypoint implements BrickStateMsgListener{

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketEntrypoint.class);

    public static final long USER_VALIDATION_TIMEOUT = 10000;

    private final Map<Session, Long> sessions;

    private final Map<String, UserSession> userConnectedSession;

    private final UserManager userManager;

    private final ProjectStore projectStore;

    private final ThreadLocal<Gson> localGson = new ThreadLocal<Gson>() {
        @Override
        protected Gson initialValue() {
            GsonBuilder builder = new GsonBuilder();
            builder.registerTypeAdapter(WebSocketMessage.class, new WebSocketMessageGsonAdapter());
            return builder.create();
        }
    };

    //  WebSocket is built by Spark but we are not able to get the instance :/ .
    //  See : https://github.com/perwendel/spark/pull/383
    public WebSocketEntrypoint() {
        super();
        sessions = new ConcurrentHashMap<>();
        userConnectedSession = new ConcurrentHashMap<>();
        userManager = Launcher.INJECTOR.getInstance(UserManager.class);
        projectStore = Launcher.INJECTOR.getInstance(ProjectStore.class);
        BrickStateMsgDispatcher msgDispatcher = Launcher.INJECTOR.getInstance(BrickStateMsgDispatcher.class);
        msgDispatcher.addListener(this);

    }

    @OnWebSocketConnect
    public void connected(Session session) {
        sessions.put(session, System.currentTimeMillis());
    }

    @OnWebSocketMessage
    public void message(Session session, String message) throws IOException {

        LOGGER.debug("Receive following message: {}", message);
        UserSession userSession = sessionIsValidated(session);
        if (userSession == null) {
            Long connectDate = sessions.get(session);
            long delta = (connectDate + USER_VALIDATION_TIMEOUT) - System.currentTimeMillis();
            if (delta < 0) {
                session.close(); // To late to connect.
            } else {
                Gson gson = localGson.get();
                WebSocketMessage webSocketMessage = gson.fromJson(message, WebSocketMessage.class);
                JsonObject data = null;
                if ("user".equals(webSocketMessage.getEntity())
                        && "authentication".equals(webSocketMessage.getAction())
                        && webSocketMessage.getData().has("authorization")) {
                    data = webSocketMessage.getData();
                    String encodedAutorization = data.getAsJsonPrimitive("authorization").getAsString();
                    if (encodedAutorization.startsWith("Basic ")) {
                        String encodedCredentials = encodedAutorization.substring("Basic ".length());
                        String decoded = new String(Base64.getDecoder().decode(encodedCredentials));
                        String[] credentials = decoded.split(":");
                        if (credentials.length != 2) {
                            sessions.remove(session);
                            session.close(400, "Authorization value in data mal formatted");
                        } else {
                            User user = userManager.getUserByUsername(credentials[0]);
                            if (user == null) {
                                sessions.remove(session);
                                session.close(401, "Invalid credentials for user '" + credentials[0]+"'.");
                            } else {
                                if (user.getPassword().equals(credentials[1])) {
                                    userConnectedSession.put(user.getIdentifier(), new UserSession(session, user));
                                    sessions.remove(session);
                                    JsonObject dataValidate = new JsonObject();
                                    dataValidate.addProperty("message", "success");
                                    dataValidate.addProperty("identifier", user.getIdentifier());
                                    WebSocketMessage response = new WebSocketMessage("user", "authentication", dataValidate);
                                    String responseStr = gson.toJson(response);
                                    session.getRemote().sendString(responseStr);
                                    if (LOGGER.isDebugEnabled()) {
                                        LOGGER.debug("Send following message to user {} : {}", user.getUsername(), responseStr);
                                    }
                                } else {
                                    sessions.remove(session);
                                    session.close(401, "Invalid credentials.");
                                }
                            }
                        }
                    } else {
                        sessions.remove(session);
                        session.close(400, "Authentication value in data attribute mal formatted : " + data.toString());
                    }
                } else {
                    sessions.remove(session);
                    session.close();
                }
            }
        } else {
            userSession.setLastActivityDate(System.currentTimeMillis());
            LOGGER.debug("Receive following message from user '{}'.", userSession.getUser().getName());
        }
    }

    @OnWebSocketClose
    public void closed(Session session, int statusCode, String reason) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Connection closed for reason '{}' with status code {}.", reason, statusCode);
        }
        sessions.remove(session);
        String identifier = null;
        Iterator<Map.Entry<String, UserSession>> iterator = userConnectedSession.entrySet().iterator();
        while (iterator.hasNext() && identifier == null) {
            Map.Entry<String, UserSession> sessionEntry = iterator.next();
            if (sessionEntry.getValue().getSession().equals(session)) {
                identifier = sessionEntry.getKey();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Connection closed attach to user {}.", sessionEntry.getValue().getUser().getUsername());
                }
            }
        }
        if (identifier != null) {
            userConnectedSession.remove(identifier);
        }

    }

    @Override
    public void receive(BrickStateMsg brickStateMsg) {
        if (brickStateMsg == null) {
            throw new IllegalArgumentException("brickStateMsg must be defined.");
        }
        WebSocketMessage message = convertToWebSocketMessage(brickStateMsg);
        String projectConfigurationIdentifier = brickStateMsg.getProjectConfigurationIdentifier();
        ProjectConfiguration projectConfiguration = projectStore.getProjectConfigurationById(projectConfigurationIdentifier);
        UserSession ownerSession = userConnectedSession.get(projectConfiguration.getOwner().getIdentifier());
        if (ownerSession != null) {
            sendMessageToUser(message, ownerSession);
            projectConfiguration.getUsers().forEach(user -> {
                UserSession session = userConnectedSession.get(user.getIdentifier());
                if (session != null && !ownerSession.getUser().getIdentifier().equals(user.getIdentifier())) {
                    sendMessageToUser(message, session);
                }
            });
        }
    }

    private void sendMessageToUser(WebSocketMessage message, UserSession userSession) {
        Gson gson = localGson.get();
        String json = gson.toJson(message);
        try {
            userSession.getSession().getRemote().sendString(json);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Following message sent to user {} : {}", userSession.getUser().getUsername(), json);
            }
        } catch (IOException e) {
            LOGGER.error("Unable to notify user {}.", userSession.getUser().getUsername());
        }
    }

    private WebSocketMessage convertToWebSocketMessage(BrickStateMsg brickStateMsg) {
        JsonObject data = new JsonObject();
        data.addProperty("projectConfiguration", brickStateMsg.getProjectConfigurationIdentifier());
        data.addProperty("brickType", brickStateMsg.getBrickType());
        data.addProperty("brickName", brickStateMsg.getBrickName());
        data.addProperty("state", brickStateMsg.getState().name());
        return new WebSocketMessage("brick", "updateState", data);
    }

    private UserSession sessionIsValidated(Session session) {
        assert session != null : "session must be defined";
        UserSession res = null;
        Iterator<UserSession> iterator = userConnectedSession.values().iterator();
        while (res == null && iterator.hasNext()) {
            UserSession current = iterator.next();
            if (current.getSession().equals(session)) {
                res = current;
            }
        }
        return res;
    }

    private class UserSession {

        private final Session session;

        private final User user;

        private long lastActivityDate;

        public UserSession(Session session, User user) {
            this.session = session;
            this.user = user;
            this.lastActivityDate = System.currentTimeMillis();
        }

        public Session getSession() {
            return session;
        }

        public User getUser() {
            return user;
        }

        public long getLastActivityDate() {
            return lastActivityDate;
        }

        public void setLastActivityDate(long lastActivityDate) {
            if (this.lastActivityDate < lastActivityDate)
                this.lastActivityDate = lastActivityDate;
        }
    }

}
