package darts.server;

import darts.client.ServerConnection;
import darts.common.Message;
import darts.common.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for Phase 4: multi-room chat, presence, and room/user listing.
 */
public class Phase4IntegrationTest {
    private static final int TEST_PORT = 9998;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Phase 4 Integration Test...");

        Server server = new Server(TEST_PORT);
        Thread serverThread = new Thread(server::start, "Phase4TestServer");
        serverThread.start();
        Thread.sleep(1500);

        try {
            testRoomJoinAndIsolation();
            testRoomListAndUserList();
            testPresenceOnJoin();
            System.out.println("[SUCCESS] Phase 4 Integration Tests passed clean!");
        } finally {
            server.stop();
            serverThread.join(2000);
        }
    }

    private static void testRoomJoinAndIsolation() throws Exception {
        System.out.println("Testing multi-room join and message isolation...");

        List<Message> aliceInGeneral = Collections.synchronizedList(new ArrayList<>());
        List<Message> bobInRandom = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        ServerConnection alice = new ServerConnection("localhost", TEST_PORT);
        alice.setMessageHandler(msg -> {
            if (Protocol.MSG_ROOM_MSG.equals(msg.getType())) {
                aliceInGeneral.add(msg);
            }
        });
        alice.connect();
        alice.send(new Message(Protocol.MSG_LOGIN, "Alice", null, "general", ""));

        ServerConnection bob = new ServerConnection("localhost", TEST_PORT);
        bob.setMessageHandler(msg -> {
            if (Protocol.MSG_ROOM_MSG.equals(msg.getType())) {
                bobInRandom.add(msg);
            }
            if (Protocol.MSG_ROOM_MSG.equals(msg.getType()) && msg.getBody().contains("secret")) {
                latch.countDown();
            }
        });
        bob.connect();
        bob.send(new Message(Protocol.MSG_LOGIN, "Bob", null, "general", ""));

        Thread.sleep(300);

        bob.send(new Message(Protocol.MSG_JOIN_ROOM, "Bob", null, "random", ""));
        Thread.sleep(300);

        alice.send(new Message(Protocol.MSG_ROOM_MSG, "Alice", null, "general", "Hello general only"));
        bob.send(new Message(Protocol.MSG_ROOM_MSG, "Bob", null, "random", "secret room message"));

        boolean bobGotSecret = latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);

        alice.disconnect();
        bob.disconnect();

        if (!bobGotSecret) {
            throw new RuntimeException("Bob did not receive his room message");
        }
        for (Message msg : aliceInGeneral) {
            if (msg.getBody().contains("secret")) {
                throw new RuntimeException("Alice received message from Bob's private room");
            }
        }
        for (Message msg : bobInRandom) {
            if (msg.getBody().contains("Hello general only")) {
                throw new RuntimeException("Bob received message from general room after leaving");
            }
        }
        System.out.println("  -> Room isolation verified.");
    }

    private static void testRoomListAndUserList() throws Exception {
        System.out.println("Testing MSG_ROOM_LIST and MSG_USER_LIST...");

        CountDownLatch roomListLatch = new CountDownLatch(1);
        CountDownLatch userListLatch = new CountDownLatch(1);

        ServerConnection client = new ServerConnection("localhost", TEST_PORT);
        client.setMessageHandler(msg -> {
            if (Protocol.MSG_ROOM_LIST.equals(msg.getType())) {
                if (msg.getBody().contains("general") && msg.getBody().contains("random")) {
                    roomListLatch.countDown();
                }
            }
            if (Protocol.MSG_USER_LIST.equals(msg.getType())) {
                if (msg.getBody().contains("Lister")) {
                    userListLatch.countDown();
                }
            }
        });
        client.connect();
        client.send(new Message(Protocol.MSG_LOGIN, "Lister", null, "general", ""));

        Thread.sleep(300);
        client.send(new Message(Protocol.MSG_CREATE_ROOM, "Lister", null, "general", "random"));
        Thread.sleep(500);
        client.send(new Message(Protocol.MSG_JOIN_ROOM, "Lister", null, "random", ""));
        Thread.sleep(300);
        client.send(new Message(Protocol.MSG_ROOM_LIST, "Lister", null, null, ""));
        client.send(new Message(Protocol.MSG_USER_LIST, "Lister", null, "random", ""));

        boolean roomsOk = roomListLatch.await(5, TimeUnit.SECONDS);
        boolean usersOk = userListLatch.await(5, TimeUnit.SECONDS);
        client.disconnect();

        if (!roomsOk) {
            throw new RuntimeException("MSG_ROOM_LIST did not return expected rooms");
        }
        if (!usersOk) {
            throw new RuntimeException("MSG_USER_LIST did not include current user");
        }
        System.out.println("  -> Room and user list responses verified.");
    }

    private static void testPresenceOnJoin() throws Exception {
        System.out.println("Testing MSG_PRESENCE on room join...");

        CountDownLatch presenceLatch = new CountDownLatch(1);

        ServerConnection watcher = new ServerConnection("localhost", TEST_PORT);
        watcher.setMessageHandler(msg -> {
            if (Protocol.MSG_PRESENCE.equals(msg.getType())
                    && msg.getBody() != null
                    && msg.getBody().contains("NewUser")
                    && msg.getBody().contains("joined")) {
                presenceLatch.countDown();
            }
        });
        watcher.connect();
        watcher.send(new Message(Protocol.MSG_LOGIN, "Watcher", null, "general", ""));

        Thread.sleep(300);

        ServerConnection joiner = new ServerConnection("localhost", TEST_PORT);
        joiner.connect();
        joiner.send(new Message(Protocol.MSG_LOGIN, "NewUser", null, "general", ""));

        boolean presenceOk = presenceLatch.await(5, TimeUnit.SECONDS);

        joiner.disconnect();
        watcher.disconnect();

        if (!presenceOk) {
            throw new RuntimeException("MSG_PRESENCE not broadcast on join");
        }
        System.out.println("  -> Presence broadcast verified.");
    }
}
