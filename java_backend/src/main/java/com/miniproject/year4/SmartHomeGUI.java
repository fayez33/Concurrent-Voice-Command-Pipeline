package com.miniproject.year4;

import javax.swing.*;
import java.awt.*;

public class SmartHomeGUI {
    private JFrame frame;
    private JLabel statusLabel;

    public SmartHomeGUI() {
        // 1. Initialize the Window
        frame = new JFrame("Mini_Project_Year_4: Voice Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 300);
        frame.setLayout(new BorderLayout());

        // 2. Initialize the Text Label
        statusLabel = new JLabel("Listening...", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 48));
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(40, 42, 54)); // Dark Gray
        statusLabel.setForeground(Color.WHITE);

        frame.add(statusLabel, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null); // Center on screen
        frame.setVisible(true);
    }

    /**
     * Called by the backend whenever a valid command is recognized.
     * Uses invokeLater to ensure thread safety when updating the UI from background threads.
     */
    public void flashCommand(String command) {
        SwingUtilities.invokeLater(() -> {
            // Flash Green and show the command
            statusLabel.setText(command.toUpperCase() + " TRIGGERED!");
            statusLabel.setBackground(new Color(46, 204, 113)); // Emerald Green
            statusLabel.setForeground(Color.BLACK);

            // Set a timer to reset the screen back to normal after 1.5 seconds
            Timer timer = new Timer(1500, e -> {
                statusLabel.setText("Listening...");
                statusLabel.setBackground(new Color(40, 42, 54));
                statusLabel.setForeground(Color.WHITE);
            });
            timer.setRepeats(false); // Only run the reset once
            timer.start();
        });
    }
}