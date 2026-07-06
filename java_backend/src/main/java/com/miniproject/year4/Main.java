package com.miniproject.year4;

import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public class Main {
    // Volatile ensures that when the main thread changes this to false,
    // the background microphone thread sees the change instantly.
    private static volatile boolean isListening = true;

    public static void main(String[] args) {

        System.out.println("Starting Smart Home Backend");

        // Boot up the network handler
        VoiceCommandHandler handler = new VoiceCommandHandler("localhost", 50051);
        java.util.Scanner scanner = new java.util.Scanner(System.in);

        // Choose what mode to use
        System.out.println("\n--- SELECT OPERATION MODE ---");
        System.out.println("1: Continuous Live Microphone");
        System.out.println("2: Network Stress Test (150 Simultaneous Requests)");
        System.out.println("3: CPU Parallel Benchmark (Topic B Requirement)");
        System.out.println("4: Trickle Stress Test (AIMD Demonstration)");
        System.out.print("Enter choice (1, 2, 3 or 4): ");

        String choice = scanner.nextLine();

        if ("2".equals(choice)) {
            // Mode 2: stress set
            System.out.println("\n Initializing Network Stress Test...");
            byte[] dummyAudio = generateDummyAudio(16000);
            runStressTest(handler, dummyAudio);

            try { Thread.sleep(5000); } catch (InterruptedException e) { e.printStackTrace(); }

        } else if ("3".equals(choice)) {
            // Mode 3: Parallel time vs continuous time
            runCpuBenchmark();

        } else if ("4".equals(choice)) {
            // Mode 4: Trickle Stress Test to observe AIMD pool sizing
            System.out.println("\n⚙️ Initializing Trickle Stress Test...");
            byte[] dummyAudio = generateDummyAudio(16000);
            runTrickleStressTest(handler, dummyAudio);

            // Sleep longer to give the 7.5 second trickle time to finish
            try { Thread.sleep(15000); } catch (InterruptedException e) { e.printStackTrace(); }

            // NEW: Print the CSV data for the graph
            System.out.println("\n📊 --- CSV DATA FOR GRAPH --- 📊");
            System.out.println("Change_Sequence,Pool_Size");
            // Add the baseline starting point
            System.out.println("0,5");
            for (String record : handler.getPoolSizeHistory()) {
                System.out.println(record);
            }
            System.out.println("--------------------------------\n");

        } else {
            // Mode 1: Continuous Stream
            System.out.println("\n Booting Visual Dashboard...");

            //Boot GUI
            SmartHomeGUI dashboard = new SmartHomeGUI();
            handler.setGui(dashboard);

            startContinuousStream(handler);
            // Teminate program if enter is typed to the CLI
            System.out.println("\n[Press ENTER in this console at any time to shut down the server]");
            scanner.nextLine();

            isListening = false;
            System.out.println("\nInitiating graceful shutdown...");

            try { Thread.sleep(2000); } catch (InterruptedException e) { e.printStackTrace(); }
        }

        // Final Cleanup
        System.out.println(" Shutting down backend network connections...");
        try {
            handler.shutdown();
            System.out.println("System successfully terminated.");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        scanner.close();
        System.exit(0);
    }

    // generate static noise for stress test(floats between -1.0 and 1.0)
    private static byte[] generateDummyAudio(int numSamples) {
        // Allocate 4 bytes per float
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(numSamples * 4);

        // Match Python's default machine byte order
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.util.Random rand = new java.util.Random();

        for (int i = 0; i < numSamples; i++) {
            // Generate a valid audio float
            float validFloat = (rand.nextFloat() * 2.0f) - 1.0f;
            buffer.putFloat(validFloat);
        }

        return buffer.array();
    }

    private static void runStressTest(VoiceCommandHandler handler, byte[] audioPayload) {
        System.out.println("\nStarting Stress Test: 150 simultaneous requests");

        for (int i = 1; i <= 150; i++) {
            String clientId = "stress_mic_" + String.format("%02d", i);
            handler.submitAudioForProcessing(audioPayload, clientId);
        }
    }

    // Test for AIMD dynamic pool size
    private static void runTrickleStressTest(VoiceCommandHandler handler, byte[] audioPayload) {
        System.out.println("\n INITIATING AIMD DEMO: 300 TRICKLE REQUESTS ");

        for (int i = 1; i <= 300; i++) {
            String clientId = "trickle_mic_" + String.format("%03d", i);
            handler.submitAudioForProcessing(audioPayload, clientId);

            // add delays to the requests so the 2-second AIMD loop has time to monitor and react
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    // CPU-Bound Benchmark, parallel vs sequential requests
    private static void runCpuBenchmark() {
        System.out.println("\n===========================================");
        System.out.println(" CPU-BOUND PARALLEL BENCHMARK ");
        System.out.println("===========================================");

        int totalAudioChunks = 5000; // Simulate processing 5000 seconds of audio

        // 1. Sequential Test
        System.out.println("Running Sequential Math Processing...");
        long startSeq = System.currentTimeMillis();
        for (int i = 0; i < totalAudioChunks; i++) {
            generateDummyAudio(16000);
        }
        long timeSeq = System.currentTimeMillis() - startSeq;
        System.out.println("Sequential Time: " + timeSeq + " ms");

        // 2. Parallel Test
        System.out.println("\nRunning Parallel Math Processing...");
        long startPar = System.currentTimeMillis();
        java.util.stream.IntStream.range(0, totalAudioChunks)
                .parallel() // use all CPU cores
                .forEach(i -> generateDummyAudio(16000));
        long timePar = System.currentTimeMillis() - startPar;
        System.out.println(" Parallel Time  : " + timePar + " ms");

        System.out.println("\n Speedup Factor : " + String.format("%.2fx", (double)timeSeq / timePar));
        System.out.println("===========================================\n");
    }

    private static void startContinuousStream(VoiceCommandHandler handler) {
        //create a new thread for the mic
        Thread micThread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("Microphone not supported");
                    return;
                }

                //claims access to the microphone of the laptop
                TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(format);
                microphone.start();

                System.out.println("\n Microphone Activated");
                System.out.println("Say 'On', 'Off', 'Yes', or 'No'.");

                // The full 1-second window we send to Python
                byte[] slidingWindow = new byte[32000];// 16 bits * 16KHz
                int halfSecondBytes = 16000;

                // Pre-fill the first half of the window before the loop starts
                // so our very first network request is a full 1.0 second of audio
                microphone.read(slidingWindow, 0, halfSecondBytes);

                while (isListening) {
                    // 1. Read the next 0.5 seconds into the back half of the window
                    //In order to not lose any words on the split between the first and second window, we use a sliding window
                    //to read time (0 -> 1) and then (0.5 -> 1.5) and so on in order to always catch the spoken word
                    int bytesRead = microphone.read(slidingWindow, halfSecondBytes, halfSecondBytes);

                    if (bytesRead > 0) {
                        // 2. Convert the full 1.0 second window into floats for the CNN
                        ByteBuffer floatBuffer = ByteBuffer.allocate(16000 * 4); // float is 4 bytes
                        floatBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        // The loop is here because each point of sound (16 bits) was split
                        // into 2 bytes (for the list). so the loop goes over the bytes two by two
                        // it shifts the second byte to the left by 8 to get the original 16-bit integer
                        for (int i = 0; i < slidingWindow.length; i += 2) {
                            short pcmSample = (short) ((slidingWindow[i] & 0xFF) | (slidingWindow[i + 1] << 8));
                            floatBuffer.putFloat(pcmSample / 32768.0f);//normalization
                        }

                        // 3. Fire the full overlapping window to Python
                        handler.submitAudioForProcessing(floatBuffer.array(), "continuous_mic");


                        // Move the back half (newest audio) to the front half (oldest audio)
                        //go to the array sliding window starting from byte 16000(halfSecondBytes) and copy
                        //16000(halfSecondBytes) into the array sliding window starting at 0
                        System.arraycopy(slidingWindow, halfSecondBytes, slidingWindow, 0, halfSecondBytes);
                    }
                }

                microphone.stop();
                microphone.close();
                System.out.println(" Microphone hardware disconnected.");

            } catch (Exception e) {
                System.err.println("Microphone Error: " + e.getMessage());
            }
        });

        micThread.start();

    }
}