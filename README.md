# DARTS-Java — Distributed Asynchronous Real-time Talk System

[![Java](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Interface](https://img.shields.io/badge/Interface-Terminal-orange.svg)]()

**DARTS-Java** is a high-performance, terminal-native, multi-user chat system built from scratch in Java. Driven by Java NIO non-blocking I/O (`Selector`), it features persistent storage, TLS wire encryption, End-to-End (E2E) encrypted private messaging, multi-room channels, presence tracking, administrative controls, and an ANSI-colored console interface.

Designed with zero external build tool dependencies (no Maven/Gradle required), DARTS-Java compiles cleanly using the standard Java Development Kit (`javac`) and runs out-of-the-box on LANs or over the public internet.

---

## Key Features

- **Non-Blocking NIO Architecture**: Driven by a single-threaded Java NIO `Selector` event loop handling hundreds of concurrent connections without thread-per-client overhead. Asynchronous worker thread pool manages database transactions and PBKDF2 hashing off the main event loop.
- **Robust Security & Cryptography**:
  - **TLS Transport Encryption**: Encrypted client-server communication using Java `SSLEngine`.
  - **Password Security**: Salted PBKDF2 with HMAC-SHA256 password hashing (100,000+ iterations) and rate-limiting against brute-force attempts.
  - **End-to-End (E2E) Encrypted Direct Messages**: Optional client-side RSA/AES key exchange for confidential private messaging (`/pm`).
- **Persistent Data Storage**: File-backed embedded H2 database storing user profiles, password salts/hashes, channel metadata, and message history replay (last 50 messages delivered automatically upon joining a room).
- **Rich Terminal User Interface (TUI)**:
  - ANSI color-coded user handle highlighting.
  - Interactive command parser with line-editing and command history navigation.
  - Automatic reconnection handling and 30-second ping/pong heartbeat idle detection.
- **Admin Moderation & Channel Management**: Real-time room creation (`/create-room`), channel switching (`/join`, `/leave`), user presence notifications, and admin moderation tools (`/kick`, `/mute`, `/unmute`).

---

## Directory & Package Structure

```
DARTSJAVA/
├── README.md                     # Project documentation index (this file)
├── docs/                         # Detailed architecture & technical specs
│   ├── 01_PROJECT_CHARTER.md     # Vision, scope, and non-goals
│   ├── 02_ARCHITECTURE.md        # System design, thread model & data flows
│   ├── 03_PROTOCOL.md            # Framing & wire envelope specification
│   ├── 04_DATABASE_SCHEMA.md     # H2 database tables & JDBC rules
│   ├── 05_SECURITY.md            # Threat model, TLS, & password hashing
│   ├── 06_CODING_STANDARDS.md    # Style guide & PR review checklists
│   ├── 07_ROADMAP_AND_TASKS.md   # Project phases & task breakdown
│   └── 08_CONTRIBUTING.md        # Branching model & definition of done
└── darts-java/
    ├── lib/                      # External dependencies (embedded H2 driver JAR)
    │   └── h2-2.2.224.jar
    ├── scripts/                  # Cross-platform build & launch scripts
    │   ├── compile.sh / .bat / .ps1
    │   ├── generate-keystore.sh / .bat
    │   ├── run-server.sh / .bat / .ps1
    │   └── run-client.sh / .bat / .ps1
    └── src/                      # Source code
        └── darts/
            ├── client/           # Client application & TUI
            │   ├── Client.java           # Entry point
            │   ├── ConsoleUI.java        # Terminal interface & ANSI rendering
            │   └── ServerConnection.java # Socket I/O & reconnect logic
            ├── common/           # Shared protocol & crypto utilities
            │   ├── Message.java          # Envelope representation & JSON parser
            │   ├── Protocol.java         # Frame packing (4-byte length header)
            │   ├── CryptoUtils.java      # AES/RSA/PBKDF2 & TLS context helpers
            │   └── *Test.java            # Unit test suites
            └── server/           # Server implementation
                ├── Server.java           # Main entry point & Selector event loop
                ├── ClientSession.java    # Non-blocking socket state & buffers
                ├── AuthManager.java      # Credential verification & rate-limiting
                ├── Database.java         # H2 JDBC data access layer
                ├── Room.java             # Channel state & broadcast routing
                ├── MakeAdmin.java        # CLI tool to promote admin users
                └── *IntegrationTest.java # Integration test suites
```

---

## Quick Start Guide

### Prerequisites

- **Java Development Kit (JDK)**: Version 17 or higher (`javac` and `java` must be on your `PATH`).

---

### Step 1: Generate KeyStore (Optional / Security)

To enable TLS encryption, generate a PKCS12 keystore using the bundled script:

**Linux / macOS:**
```bash
cd darts-java
./scripts/generate-keystore.sh
```

**Windows (PowerShell / CMD):**
```cmd
cd darts-java
.\scripts\generate-keystore.bat
```

*(If omitted, the server can generate or fall back to default TLS configuration).*

---

### Step 2: Compile the Codebase

Compile all packages using the platform script (compiles `src/` to `out/` with `lib/h2-2.2.224.jar` on the classpath):

**Linux / macOS:**
```bash
./scripts/compile.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\compile.ps1
```

**Windows (CMD):**
```cmd
.\scripts\compile.bat
```

---

### Step 3: Run the Server

Start the DARTS server instance (defaults to port `8888` if unspecified):

**Linux / macOS:**
```bash
./scripts/run-server.sh 8888
```

**Windows (PowerShell):**
```powershell
.\scripts\run-server.ps1 8888
```

**Windows (CMD):**
```cmd
.\scripts\run-server.bat 8888
```

---

### Step 4: Run a Client

Open one or more terminal windows to connect to the running server:

**Linux / macOS:**
```bash
./scripts/run-client.sh localhost 8888 myusername
```

**Windows (PowerShell):**
```powershell
.\scripts\run-client.ps1 localhost 8888 myusername
```

---

## Terminal Command Reference

Once connected to DARTS, interact using slash commands:

### Authentication & Account
| Command | Usage | Description |
|---|---|---|
| `/register` | `/register <username> <password>` | Create a new user account |
| `/login` | `/login <username> <password>` | Authenticate into an existing account |

### Messaging & Channels
| Command | Usage | Description |
|---|---|---|
| *(Text)* | `Hello world!` | Send message to current room |
| `/all` | `/all <message>` | Broadcast message to all online users across rooms |
| `/pm` | `/pm <username> <message>` | Send a private (optionally E2E encrypted) direct message |
| `/join` | `/join <room_name>` | Join/switch to a room (defaults to `general`) |
| `/leave` | `/leave` | Leave current room and return to `general` |
| `/create-room` | `/create-room <room_name>` | Create a new public chat channel |

### Discovery & Utility
| Command | Usage | Description |
|---|---|---|
| `/rooms` | `/rooms` | List all active rooms on the server |
| `/users` | `/users` | List all currently connected users |
| `/help` | `/help` | Display available terminal commands |
| `/quit` | `/quit` | Safely disconnect from the server |

### Moderation (Admin Only)
| Command | Usage | Description |
|---|---|---|
| `/kick` | `/kick <username>` | Disconnect a target user from the server |
| `/mute` | `/mute <username>` | Restrict a user from sending room/broadcast messages |
| `/unmute` | `/unmute <username>` | Restore sending privileges to a muted user |

*To grant admin privileges to a user, run the utility:*
```bash
java -cp "out:lib/*" darts.server.MakeAdmin <username>
```

---

## Protocol Overview

DARTS uses a binary length-prefixed JSON framing protocol:

```
[4 bytes: Big-Endian payload length (Int)] [UTF-8 JSON Envelope]
```

### Envelope Structure Example

```json
{
  "type": "MSG_ROOM_MSG",
  "from": "alice",
  "to": null,
  "room": "general",
  "body": "Hello everyone!",
  "timestamp": 1737590400000
}
```

For full wire specifications, see [`docs/03_PROTOCOL.md`](docs/03_PROTOCOL.md).

---

## Comprehensive Technical Documentation

Explore the `docs/` directory for detailed design documentation:

1. **[01_PROJECT_CHARTER.md](docs/01_PROJECT_CHARTER.md)** — Project vision, goals, and non-goals.
2. **[02_ARCHITECTURE.md](docs/02_ARCHITECTURE.md)** — Non-blocking NIO Selector design, thread interaction models, and message flows.
3. **[03_PROTOCOL.md](docs/03_PROTOCOL.md)** — Wire protocol envelope, message type enumeration, and session lifecycles.
4. **[04_DATABASE_SCHEMA.md](docs/04_DATABASE_SCHEMA.md)** — H2 database schema (`users`, `rooms`, `messages`, `audit_log`) and JDBC guidelines.
5. **[05_SECURITY.md](docs/05_SECURITY.md)** — Threat model, SSLEngine TLS handshake, PBKDF2 password hashing, and E2E DM encryption.
6. **[06_CODING_STANDARDS.md](docs/06_CODING_STANDARDS.md)** — Code style, package isolation rules, error handling, and PR checklists.
7. **[07_ROADMAP_AND_TASKS.md](docs/07_ROADMAP_AND_TASKS.md)** — Development phases, feature breakdown, and exit criteria.
8. **[08_CONTRIBUTING.md](docs/08_CONTRIBUTING.md)** — Git workflow, branching conventions, and definition of done.

---

## Project Team

- **Sadat Shaharier Sahaf**
- **Tajrian Quazi**
- **Abidur Rahman Dipto**
- **Ahnaf Mushfiq Nafees**
- **Md. Abdur Rahim**

---

## License

This project is licensed under the MIT License — see the repository for details.
