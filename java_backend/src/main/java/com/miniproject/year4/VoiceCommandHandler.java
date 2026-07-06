package com.miniproject.year4;

import com.miniproject.year4.grpc.AudioProcessorGrpc;
import com.miniproject.year4.grpc.AudioRequest;
import com.miniproject.year4.grpc.PredictionResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class VoiceCommandHandler {

    private final ManagedChannel channel; //the connection to python
    private final AudioProcessorGrpc.AudioProcessorBlockingStub blockingStub; //the object used to call python functons
    private final ThreadPoolExecutor threadPool; //for multithreading
    private SmartHomeGUI gui;

    // For adaptive thread pool size, Use additive increase multiplicative decrease
    private final ScheduledExecutorService adaptiveController;
    private final ConcurrentLinkedQueue<Long> recentLatencies = new ConcurrentLinkedQueue<>();
    private int currentPoolSize = 5; // Start small
    private final int MAX_POOL_SIZE = 15; //  ceiling to protect the ML model
    private final int MIN_POOL_SIZE = 2;  //  floor to ensure progress

    // used to count successful failed and rejected requests
    private final AtomicInteger completedWork = new AtomicInteger(0);
    private final AtomicInteger failedWork = new AtomicInteger(0);
    private final AtomicInteger rejectedWork = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>(); //to record latencies, this class is used instead of a normal list because it is thread safe and doen not allow a certain thread to overwrite another

    // --- TEMPORARY GRAPH DATA ---
    private final java.util.List<String> poolSizeHistory = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int changeSequence = 0;

    public VoiceCommandHandler(String pythonServerHost, int pythonServerPort) { //constructor
        //python connection
        this.channel = ManagedChannelBuilder.forAddress(pythonServerHost, pythonServerPort)
                .usePlaintext()// do not encrypt
                .build();// open connection
        this.blockingStub = AudioProcessorGrpc.newBlockingStub(channel);

        // This is a queue of a hundred requests. if this queue is full, new requests will be rejected
        ArrayBlockingQueue<Runnable> boundedQueue = new ArrayBlockingQueue<>(100);

        // If the queue hits 100, this handler drops the new request and logs it, preventing a JVM crash.
        RejectedExecutionHandler rejectionHandler = (runnable, executor) -> {
            rejectedWork.incrementAndGet();
            System.err.println("[OVERLOAD] System full! Request rejected.");
        };

        // Use the dynamic currentPoolSize instead of hardcoded 10
        this.threadPool = new ThreadPoolExecutor(
                currentPoolSize, currentPoolSize,
                0L, TimeUnit.MILLISECONDS,
                boundedQueue,
                rejectionHandler
        );

        // Start the Adaptive Controller to check latency every 2 seconds
        this.adaptiveController = Executors.newSingleThreadScheduledExecutor();//new thread pool containing one thread, to observe network and manage pool size
        this.adaptiveController.scheduleAtFixedRate(this::adjustPoolSize, 2, 2, TimeUnit.SECONDS);//check every 2 seconds

        System.out.println("Java Backend Initialized. Adaptive AIMD Pool size (2 to 15).");
    }

    public void setGui(SmartHomeGUI gui) {
        this.gui = gui;
    }

    public void submitAudioForProcessing(byte[] rawAudio, String clientId) {
        // Use execute() to hand the raw audio to one of the background workers
        threadPool.execute(() -> {
            long startTime = System.currentTimeMillis(); // start stopwatch
            try {
                System.out.println("[" + clientId + "] Sending audio to Python (Queue depth: " + threadPool.getQueue().size() + ")");

                AudioRequest request = AudioRequest.newBuilder()//open a template based on the proto file
                        .setAudioData(ByteString.copyFrom(rawAudio))
                        .setTimestamp(System.currentTimeMillis())
                        .setClientId(clientId)
                        .build();

                // Network request with 1 second timeout
                PredictionResponse response = blockingStub
                        .withDeadlineAfter(1000, TimeUnit.MILLISECONDS)
                        .predictCommand(request);//predict method in python

                if (response.getSuccess()) {// getSuccess is boolean flag
                    executeDeviceLogic(clientId, response.getCommand(), response.getConfidence());
                    completedWork.incrementAndGet();
                } else {
                    System.err.println(" [" + clientId + "] Python AI reported an internal error.");
                    failedWork.incrementAndGet();
                }

            } catch (Exception e) {
                System.err.println(" [" + clientId + "] Network Timeout or Failure: " + e.getMessage());
                failedWork.incrementAndGet();
            } finally {
            // Record the total processing time for this specific request
            long latency = System.currentTimeMillis() - startTime;
            latencies.add(latency); // Global metrics for scores
            recentLatencies.add(latency); // Local metrics for dynamic sizing of thread pool
        }
        });
    }

    private void executeDeviceLogic(String clientId, String command, float confidence) {
        if ("unknown".equals(command)) {
            System.out.println("[" + clientId + "] Noise ignored. (Confidence was: " + (confidence * 100) + "%)");
            return;
        }

        System.out.println("[" + clientId + "] Executing Command. (Confidence was: " + (confidence * 100) + "%)");

        if (this.gui != null) {
            gui.flashCommand(command); // show on gui if command triggered
        }
    }

    public void shutdown() throws InterruptedException {
        adaptiveController.shutdown(); // Shut down the AIMD watcher
        threadPool.shutdown();
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);

        printMetrics();
    }

    //  Metrics and Observability
    private void printMetrics() {
        System.out.println("\n===========================================");
        System.out.println(" SYSTEM METRICS & CONCURRENCY SCORECARD ");
        System.out.println("===========================================");
        //display successful, rejected and failed work
        System.out.println("Completed Requests : " + completedWork.get());
        System.out.println("Failed Requests    : " + failedWork.get());
        System.out.println("Rejected Requests  : " + rejectedWork.get());

        List<Long> latList = new ArrayList<>(latencies);// copy latencies Queue to a list for easier manipulation
        if (!latList.isEmpty()) {
            Collections.sort(latList);
            long p50 = latList.get((int) (latList.size() * 0.50));//median 50% of the sorted list
            long p95 = latList.get((int) (latList.size() * 0.95));
            long p99 = latList.get((int) (latList.size() * 0.99));// these other two are the tail latencies, showing worst case scenario
            long max = latList.get(latList.size() - 1);//slowest request

            // Calculate a rough throughput (completed tasks / total parallel time)
            // total time of all requests = sum of time of all requests / number of cores (total time of all requests)
            long totalTimeMs = latList.stream().mapToLong(Long::longValue).sum() / Math.max(1, threadPool.getCorePoolSize());
            double throughput = latList.size() / (Math.max(1, totalTimeMs) / 1000.0);// convert milliseconds to seconds

            System.out.println("\n Latency (milliseconds):");
            System.out.println("  - p50 (Median)      : " + p50 + " ms");
            System.out.println("  - p95               : " + p95 + " ms");
            System.out.println("  - p99               : " + p99 + " ms");
            System.out.println("  - Max Latency       : " + max + " ms");
            System.out.println(" Estimated Throughput    : " + String.format("%.2f", throughput) + " req/sec");
        }
        System.out.println("===========================================\n");
    }
    private void adjustPoolSize() {
        if (recentLatencies.isEmpty()) return;

        // Calculate average latency of the recent batch
        long sum = 0;
        int count = recentLatencies.size();
        for (Long lat : recentLatencies) sum += lat;
        long avgLatency = sum / count;
        recentLatencies.clear(); // Reset for the next 2-second window

        boolean changed = false;

        // Multiplicative Decrease: if average latency is above 850, python is choking, divide pool size by 2
        if (avgLatency > 850 && currentPoolSize > MIN_POOL_SIZE) {
            currentPoolSize = Math.max(MIN_POOL_SIZE, currentPoolSize / 2);
            changed = true;
            System.out.println("[AIMD] Latency spike (" + avgLatency + "ms). Reducing pool size down to " + currentPoolSize);
        }
        // Additive Increase: avg latency below 800, python can handle more, add to the pool size
        else if (avgLatency < 800 && currentPoolSize < MAX_POOL_SIZE) {
            currentPoolSize++;
            changed = true;
            System.out.println("[AIMD] Network stable (" + avgLatency + "ms). Scaling pool size up to " + currentPoolSize);
        }

        // Apply the dynamic boundary to the live ThreadPool safely
        if (changed) {
            try {
                // BUG FIX: Order of operations matters to prevent IllegalArgumentException!
                if (currentPoolSize > threadPool.getMaximumPoolSize()) {
                    // Scaling UP: Increase Max first
                    threadPool.setMaximumPoolSize(currentPoolSize);
                    threadPool.setCorePoolSize(currentPoolSize);
                } else {
                    // Scaling DOWN: Decrease Core first
                    threadPool.setCorePoolSize(currentPoolSize);
                    threadPool.setMaximumPoolSize(currentPoolSize);
                }

                // Record the change for the graph
                changeSequence++;
                poolSizeHistory.add(changeSequence + "," + currentPoolSize);
            } catch (Exception e) {
                System.err.println("[AIMD ERROR] Watcher thread caught exception: " + e.getMessage());
            }

        } else {
            System.out.println("[AIMD WATCHER] Latency: " + avgLatency + "ms. Pool size remains steady at " + currentPoolSize);
        }
    }
    // NEW: Getter for the graph data
    public java.util.List<String> getPoolSizeHistory() {
        return poolSizeHistory;
    }
}

