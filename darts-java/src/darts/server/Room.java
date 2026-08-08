package darts.server;

import darts.common.Message;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages room membership and non-blocking message broadcasting for connected clients.
 * All session mutations occur on the Selector thread.
 */
public class Room {
    private final String name;
    private final Set<ClientSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> mutedUsernames = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public Room(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Adds a client session to this room.
     */
    public void join(ClientSession session) {
        sessions.add(session);
    }

    /**
     * Removes a client session from this room.
     */
    public void leave(ClientSession session) {
        sessions.remove(session);
    }

    /**
     * Broadcasts a message to all active sessions in this room.
     */
    public void broadcast(Message message) {
        for (ClientSession session : sessions) {
            session.send(message);
        }
    }

    /**
     * Broadcasts a message to all active sessions except the specified sender.
     */
    public void broadcast(Message message, ClientSession sender) {
        for (ClientSession session : sessions) {
            if (session != sender) {
                session.send(message);
            }
        }
    }

    /**
     * Mutes a user in this room, preventing them from sending messages.
     */
    public void muteUser(String username) {
        if (username != null) {
            mutedUsernames.add(username);
        }
    }

    /**
     * Unmutes a user in this room, restoring their ability to send messages.
     */
    public void unmuteUser(String username) {
        if (username != null) {
            mutedUsernames.remove(username);
        }
    }

    /**
     * Checks whether a user is muted in this room.
     */
    public boolean isMuted(String username) {
        return username != null && mutedUsernames.contains(username);
    }

    /**
     * Returns a comma-separated list of usernames currently in this room.
     */
    public String getOnlineUsernames() {
        return sessions.stream()
                .map(ClientSession::getUsername)
                .filter(name -> name != null && !name.isBlank())
                .sorted()
                .collect(Collectors.joining(","));
    }

    public Set<ClientSession> getSessions() {
        return Collections.unmodifiableSet(sessions);
    }
}
