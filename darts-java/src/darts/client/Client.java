package darts.client;

import darts.common.Message;
import darts.common.Protocol;
import java.io.IOException;

/**
 * Client main entry point connecting to DARTS Server and driving terminal UI.
 */
public class Client {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String username = null;

        if (args.length > 0) {
            host = args[0];
        }
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number specified, defaulting to " + DEFAULT_PORT);
            }
        }
        if (args.length > 2) {
            username = args[2];
        }

        System.out.println("Connecting to DARTS server at " + host + ":" + port + "...");
        ServerConnection connection = new ServerConnection(host, port);
        ConsoleUI ui = new ConsoleUI(connection);

        if (username != null) {
            ui.setUsername(username);
        }

        connection.setMessageHandler(ui::onMessageReceived);
        connection.setErrorHandler(ui::onError);
        connection.setReconnectAttemptHandler(ui::onReconnectAttempt);
        connection.setReconnectSuccessHandler(ui::onReconnectSuccess);
        connection.setReconnectFailedHandler(ui::onReconnectFailed);

        try {
            connection.connect();
            System.out.println("Connected to DARTS server.");

            if (username != null) {
                connection.send(new Message(Protocol.MSG_LOGIN, username, null, "general", ""));
            }

            ui.startInputLoop();
        } catch (IOException e) {
            System.err.println("Failed to connect to DARTS server: " + e.getMessage());
            System.exit(1);
        }
    }
}
