// TerminalIRC.java
// Simple terminal-based IRC client for Java (no GUI).
// Basic commands: /server, /nick, /join, /part, /msg, /quit, /topic, /names, /list, /whois, /raw
// Compile: javac TerminalIRC.java
// Run:     java TerminalIRC

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    // --- Color helpers ---
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";

    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    private static final String GRAY = "\u001B[90m";

    private Socket socket;
    private BufferedWriter out;
    private BufferedReader in;
    private final Scanner stdin = new Scanner(System.in);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Thread readerThread;
    private String currentTarget = null; // current channel or nick for plain messages
    private String nick = "JavaUser";
    private String user = "javairc";
    private String realname = "Terminal IRC Client";
    private int lastServerPort = 6667;
    private String lastServerHost = null;

    public static void main(String[] args) {
        Main client = new Main();
        client.run();
    }

    private void run() {
        printBanner();
        // simple REPL
        while (true) {
            System.out.print("> ");
            String line;
            try {
                if (!stdin.hasNextLine()) break;
                line = stdin.nextLine().trim();
            } catch (NoSuchElementException e) {
                break;
            }

            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                handleCommand(line);
            } else {
                // plain message: send to current target if set, else warn
                if (!connected.get()) {
                    printlnErr("Not connected. Use /server host [port] to connect.");
                    continue;
                }
                if (currentTarget == null) {
                    printlnErr("No current target. Join a channel (/join #chan) or /msg nick targetMessage");
                    continue;
                }
                sendPrivmsg(currentTarget, line);
            }
        }

        safeClose();
        println("Bye.");
    }

    private void printBanner() {
        System.out.println("Terminal IRC Client (simple) — commands: /server, /nick, /join, /part, /msg, /quit, /topic, /names, /list, /whois, /raw");
        System.out.println("Type /help for more info.");
    }

    private void handleCommand(String line) {
        String[] parts = tokenize(line);
        String cmd = parts[0].toLowerCase(Locale.ROOT);

        try {
            switch (cmd) {
                case "/help":
                    showHelp();
                    break;
                case "/server":
                    if (parts.length < 2) {
                        printlnErr("Usage: /server host [port]");
                    } else {
                        String host = parts[1];
                        int port = (parts.length >= 3) ? Integer.parseInt(parts[2]) : 6667;
                        connect(host, port);
                    }
                    break;
                case "/nick":
                    if (parts.length < 2) {
                        printlnErr("Usage: /nick <newnick>");
                    } else {
                        setNick(parts[1]);
                    }
                    break;
                case "/join":
                    if (!ensureConnected()) break;
                    if (parts.length < 2) {
                        printlnErr("Usage: /join #channel");
                    } else {
                        sendRaw("JOIN " + parts[1]);
                        currentTarget = parts[1];
                    }
                    break;
                case "/part":
                    if (!ensureConnected()) break;
                    if (parts.length < 2) {
                        printlnErr("Usage: /part #channel [reason]");
                    } else {
                        String reason = joinRange(parts, 2);
                        if (reason.isEmpty()) sendRaw("PART " + parts[1]);
                        else sendRaw("PART " + parts[1] + " :" + reason);
                        if (parts[1].equalsIgnoreCase(currentTarget)) currentTarget = null;
                    }
                    break;
                case "/msg":
                    if (!ensureConnected()) break;
                    if (parts.length < 3) {
                        printlnErr("Usage: /msg <nick|#channel> <message...>");
                    } else {
                        String target = parts[1];
                        String msg = joinRange(parts, 2);
                        sendPrivmsg(target, msg);
                        currentTarget = target;
                    }
                    break;
                case "/quit":
                    if (!connected.get()) {
                        println("Not connected.");
                        safeClose(); // ensure closed
                        return;
                    }
                    String quitMsg = (parts.length >= 2) ? joinRange(parts, 1) : "Quit";
                    sendRaw("QUIT :" + quitMsg);
                    safeClose();
                    break;
                case "/topic":
                    if (!ensureConnected()) break;
                    if (parts.length < 3) {
                        printlnErr("Usage: /topic <#channel> <topic...>");
                    } else {
                        String chan = parts[1];
                        String topic = joinRange(parts, 2);
                        sendRaw("TOPIC " + chan + " :" + topic);
                    }
                    break;
                case "/names":
                    if (!ensureConnected()) break;
                    if (parts.length < 2) {
                        printlnErr("Usage: /names #channel");
                    } else {
                        sendRaw("NAMES " + parts[1]);
                    }
                    break;
                case "/list":
                    if (!ensureConnected()) break;
                    sendRaw("LIST");
                    break;
                case "/whois":
                    if (!ensureConnected()) break;
                    if (parts.length < 2) {
                        printlnErr("Usage: /whois <nick>");
                    } else {
                        sendRaw("WHOIS " + parts[1]);
                    }
                    break;
                case "/raw":
                    if (!ensureConnected()) break;
                    if (parts.length < 2) {
                        printlnErr("Usage: /raw <raw IRC line...>");
                    } else {
                        String raw = line.substring(5); // preserves spacing
                        sendRaw(raw);
                    }
                    break;
                case "/current":
                    println("Current target: " + (currentTarget == null ? "(none)" : currentTarget));
                    break;
                case "/serverinfo":
                    if (connected.get()) {
                        println("Connected to " + lastServerHost + ":" + lastServerPort + " as " + nick);
                    } else {
                        println("Not connected.");
                    }
                    break;
                default:
                    printlnErr("Unknown command: " + cmd + ". Type /help for commands.");
            }
        } catch (NumberFormatException nfe) {
            printlnErr("Invalid number format.");
        }
    }

    private void showHelp() {
        println("Commands:");
        println(" /server host [port]      - Connect to an IRC server (default port 6667)");
        println(" /nick <nick>             - Change your nickname");
        println(" /join #channel           - Join a channel");
        println(" /part #channel [reason]  - Part a channel");
        println(" /msg <target> <message>  - Send a PRIVMSG to a target (nick or #channel)");
        println(" /topic #channel <topic>  - Set channel topic");
        println(" /names #channel          - Request NAMES for a channel");
        println(" /list                    - Request channel list");
        println(" /whois <nick>            - WHOIS a nick");
        println(" /raw <raw line...>       - Send a raw IRC protocol line");
        println(" /current                 - Show current message target");
        println(" /serverinfo              - Show connected server info");
        println(" /quit [message]          - Quit and disconnect");
        println("Plain text lines (not starting with /) send PRIVMSG to the current target.");
    }

    private boolean ensureConnected() {
        if (!connected.get()) {
            printlnErr("Not connected. Use /server to connect first.");
            return false;
        }
        return true;
    }

    private void connect(String host, int port) {
        if (connected.get()) {
            printlnErr("Already connected. Please /quit first if you want to connect elsewhere.");
            return;
        }

        try {
            println("Connecting to " + host + ":" + port + " ...");
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10000);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            connected.set(true);
            lastServerHost = host;
            lastServerPort = port;

            // Send initial registration
            sendRaw("NICK " + nick);
            sendRaw("USER " + user + " 0 * :" + realname);

            startReaderThread();
            println("Connected. Registered as " + nick + ". Use /join to enter channels.");
        } catch (IOException e) {
            printlnErr("Connection failed: " + e.getMessage());
            safeClose();
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while (connected.get() && (line = in.readLine()) != null) {
                    handleServerLine(line);
                }
            } catch (IOException e) {
                if (connected.get()) {
                    printlnErr("Connection lost: " + e.getMessage());
                }
            } finally {
                safeClose();
            }
        }, "IRC-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static String colorNick(String nick) {
        // Assign color based on hash of nick
        int h = Math.abs(nick.hashCode());
        switch (h % 6) {
            case 0: return CYAN + nick + RESET;
            case 1: return GREEN + nick + RESET;
            case 2: return MAGENTA + nick + RESET;
            case 3: return YELLOW + nick + RESET;
            case 4: return BLUE + nick + RESET;
            default: return WHITE + nick + RESET;
        }
    }

    private synchronized void handleServerLine(String raw) {
        if (raw.startsWith("PING ")) {
            String token = raw.substring(5);
            sendRaw("PONG " + token);
            println(GRAY + "[server] PING -> PONG " + token + RESET);
            return;
        }

        String prefix = null;
        String command;
        String[] params;

        // --- Parse raw line ---
        String line = raw;
        if (line.startsWith(":")) {
            int space = line.indexOf(' ');
            prefix = line.substring(1, space);
            line = line.substring(space + 1);
        }
        int space = line.indexOf(' ');
        if (space == -1) {
            command = line;
            params = new String[0];
        } else {
            command = line.substring(0, space);
            line = line.substring(space + 1);
            List<String> p = new ArrayList<>();
            while (!line.isEmpty()) {
                if (line.startsWith(":")) {
                    p.add(line.substring(1));
                    break;
                }
                int sp = line.indexOf(' ');
                if (sp == -1) {
                    p.add(line);
                    break;
                } else {
                    p.add(line.substring(0, sp));
                    line = line.substring(sp + 1);
                }
            }
            params = p.toArray(new String[0]);
        }

        // --- Make pretty output ---
        String nick = (prefix != null && prefix.contains("!")) ? prefix.substring(0, prefix.indexOf("!")) : prefix;
        String coloredNick = (nick != null ? colorNick(nick) : null);

        switch (command) {
            case "PRIVMSG":
                if (params.length >= 2) {
                    String target = params[0];
                    String msg = params[1];
                    if (msg.startsWith("\u0001") && msg.endsWith("\u0001")) {
                        // CTCP
                        String ctcp = msg.substring(1, msg.length() - 1);
                        if (ctcp.startsWith("ACTION ")) {
                            println(timestamp() + " * " + coloredNick + " " + ctcp.substring(7));
                        } else {
                            println(MAGENTA + "[CTCP from " + nick + "] " + ctcp + RESET);
                        }
                    } else {
                        String targetColor = target.startsWith("#") ? BLUE + target + RESET : target;
                        // Normal incoming message -> GREEN
                        println(timestamp() + " " + GREEN + "<" + coloredNick + "@" + targetColor + "> " + msg + RESET);
                    }
                }
                break;
            case "JOIN":
                if (params.length >= 1) {
                    println(GREEN + "*** " + coloredNick + " joined " + params[0] + RESET);
                }
                break;
            case "PART":
                if (params.length >= 1) {
                    String reason = (params.length > 1 ? " (" + params[1] + ")" : "");
                    println(RED + "*** " + coloredNick + " left " + params[0] + reason + RESET);
                }
                break;
            case "QUIT":
                String reason = (params.length > 0 ? " (" + params[0] + ")" : "");
                println(RED + "*** " + coloredNick + " quit" + reason + RESET);
                break;
            case "NICK":
                if (params.length >= 1) {
                    println(YELLOW + "*** " + coloredNick + " is now known as " + params[0] + RESET);
                }
                break;
            case "NOTICE":
                if (params.length >= 2) {
                    println(GRAY + "-" + (nick != null ? nick : "server") + "- " + params[1] + RESET);
                }
                break;
            default:
                // Server numerics or unhandled commands
                println(GRAY + "[server] " + raw + RESET);
        }
    }



    private void sendRaw(String data) {
        if (!connected.get()) {
            printlnErr("Not connected. Can't send: " + data);
            return;
        }
        try {
            out.write(data + "\r\n");
            out.flush();
            // echo locally in a friendly way for commands we send
            // (avoid echoing raw PRIVMSG content twice when user typed it)
            if (!data.startsWith("PONG") && !data.startsWith("PASS")) {
                printlnSent("> " + data);
            }
        } catch (IOException e) {
            printlnErr("Failed to send: " + e.getMessage());
            safeClose();
        }
    }

    private void setNick(String newNick) {
        nick = newNick;
        if (connected.get()) {
            sendRaw("NICK " + nick);
        } else {
            println("Nick set to " + nick + ". It will be used when connecting.");
        }
    }

    private void sendPrivmsg(String target, String message) {
        sendRaw("PRIVMSG " + target + " :" + message);
        // Show it locally as if it was incoming, but not green
        println(timestamp() + " <" + colorNick(nick) + "> " + message);
    }


    private void safeClose() {
        if (!connected.getAndSet(false)) return; // already closed
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {}
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {}
        println("Disconnected.");
    }

    private static String[] tokenize(String line) {
        // simple split by spaces but keep colon-leading rest for messages is handled elsewhere
        // We'll split by spaces preserving tokens.
        return line.split(" ");
    }

    private static String joinRange(String[] parts, int start) {
        if (start >= parts.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    // Print helpers (synchronized to avoid interleaving with reader thread)
    private static synchronized void println(String s) {
        System.out.println(s);
    }
    private static synchronized void printlnErr(String s) {
        System.err.println(s);
    }
    private static synchronized void printlnSent(String s) {
        System.out.println(s);
    }

    private static String timestamp() {
        java.time.LocalTime now = java.time.LocalTime.now();
        return GRAY + "[" + now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) + "]" + RESET;
    }
}
