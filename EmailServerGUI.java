package dev.mike;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;

public class EmailServerGUI {

    private final JFrame frame;
    private final JButton startButton, stopButton, checkInboxButton, generateEmailButton;
    private final JLabel statusLabel, ipLabel, portLabel;
    public static JTextArea logArea = null;
    private final EmailServer emailServer;
    private final int PORT = 8080;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EmailServerGUI::new);
    }

    public EmailServerGUI() {
        emailServer = new EmailServer(); // Create an instance of EmailServer

        frame = new JFrame("Email Server Control");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 300);
        frame.setLayout(new BorderLayout());

        // Status panel
        JPanel statusPanel = new JPanel(new GridLayout(3, 1));
        statusLabel = new JLabel("Server Status: Stopped");
        ipLabel = new JLabel("IP Address: Not Running");
        portLabel = new JLabel("Port: " + PORT);

        statusPanel.add(statusLabel);
        statusPanel.add(ipLabel);
        statusPanel.add(portLabel);

        // Button panel
        JPanel buttonPanel = new JPanel();
        startButton = new JButton("Start Server");
        stopButton = new JButton("Stop Server");
        checkInboxButton = new JButton("Check Inbox");
        generateEmailButton = new JButton("Generate Email");

        stopButton.setEnabled(false); // Initially disable the stop button

        startButton.addActionListener(this::startServer);
        stopButton.addActionListener(this::stopServer);
        checkInboxButton.addActionListener(e -> executeCurlCommand("http://localhost:8080/check-inbox"));
        generateEmailButton.addActionListener(e -> executeCurlCommand("http://localhost:8080/generate-email"));

        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(checkInboxButton);
        buttonPanel.add(generateEmailButton);

        // Log area
        logArea = new JTextArea(7, 40);
        logArea.setEditable(false);
        JScrollPane logScrollPane = new JScrollPane(logArea);

        // Add panels to frame
        frame.add(statusPanel, BorderLayout.NORTH);
        frame.add(buttonPanel, BorderLayout.CENTER);
        frame.add(logScrollPane, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    private void startServer(ActionEvent e) {
        if (emailServer.isRunning()) return;
        try {
            emailServer.startServer(PORT);
            statusLabel.setText("Server Status: Running");
            ipLabel.setText("IP Address: " + InetAddress.getLocalHost().getHostAddress());
            log("Server running on http://localhost:" + PORT);
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Error starting server: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            log("Error: " + ex.getMessage());
        }
    }

    private void stopServer(ActionEvent e) {
        if (!emailServer.isRunning()) return;
        emailServer.stopServer();
        statusLabel.setText("Server Status: Stopped");
        ipLabel.setText("IP Address: Not Running");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        log("Server stopped.");
    }

    private void executeCurlCommand(String url) {
        new Thread(() -> {
            try {
                log("Executing: POST " + url);
                ProcessBuilder builder = new ProcessBuilder("curl", "-s", "-X", "POST", url);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line).append("\n");
                    }
                }
                process.waitFor();
                if (response.toString().trim().isEmpty()) {
                    log("No response received. Please start the server before continuing.");
                } else {
                    log(response.toString().trim());
                }
                log("Command execution completed.");
            } catch (IOException | InterruptedException ex) {
                log("Error executing command: " + ex.getMessage());
            }
        }).start();
    }

    public static void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }
}
