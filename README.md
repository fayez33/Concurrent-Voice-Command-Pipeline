# Concurrent Voice-Command ML Pipeline

**Lebanese University - Faculty of Engineering** **4th Year Computer and Communications Engineering Mini-Project**

---

## Project Overview

This project is a concurrent, fault-tolerant machine learning pipeline that processes live microphone audio to trigger smart home commands. It bridges a multithreaded Java client with a Python deep learning server via a strict **gRPC** protocol.

Rather than relying on basic threading and pre-built APIs, this architecture demonstrates low-level computer engineering principles, including custom acoustic machine learning, active backpressure, and dynamic TCP-style congestion control to protect the downstream ML model from crashing under heavy load.

## Core Features

- **Custom Convolutional Neural Network (CNN):** A CNN trained from scratch on 19,000 audio files. The pipeline mathematically slices raw audio into 1-second chunks and extracts 40x32 MFCC matrices using Librosa.
- **Continuous Audio Streaming:** The Java backend listens to a live microphone feed using a sliding-window byte array to ensure no spoken words are dropped across chunk boundaries.
- **Algorithmic Congestion Control (AIMD):** Features a custom Additive Increase, Multiplicative Decrease (AIMD) thread pool controller. A background watcher monitors the Python server's exact latency, slashing the pool size if the network chokes, and scaling up when stable.
- **Active Backpressure:** Implements bounded thread queues with explicit rejection handlers to prevent Java Virtual Machine (JVM) crashes during extreme network spikes.
- **Deterministic Chaos Testing:** Includes built-in stress testing suites to benchmark sequential vs. parallel CPU processing and artificially simulate network bottlenecks to prove the AIMD failure recovery path.

---

## System Performance & Benchmark Results
To prove the effectiveness of our concurrent architecture, the pipeline was subjected to rigorous stress testing and failure injection.

### CPU-Bound Processing Benchmark
We evaluated the mathematical pre-processing of 5,000 audio chunks to compare standard sequential execution against multi-core Java Parallel Streams.

* Sequential Processing Time: 799 ms

* Parallel Processing Time: 132 ms

* Speedup Factor: 6.05x

### Resilience & Failure Injection (Stress Test)
The system was flooded with extreme traffic while an artificial 900ms latency was injected into the Python server to simulate a severe network bottleneck.

* Completed Requests: 1,301 (Successfully processed under the 1000ms gRPC deadline)

* Safely Rejected: 153 (Active backpressure intervened; the bounded queue hit its 100-request limit and dropped traffic to prevent a JVM OutOfMemory crash)

* Failed/Timeouts: 46 (Requests that crossed the hard 1000ms gRPC timeout limit)

* Latency (milliseconds):

- * p50 (Median): 669 ms

- * p95: 968 ms

- * p99: 1010 ms

* Estimated Throughput: 18.10 req/sec

Architecture Tradeoff: The 153 rejected requests represent intentional data loss. We explicitly designed the system to drop valid voice commands during extreme network overloads rather than lock up the Python ML engine or crash the Java client.

---

## Installation & Setup

### Prerequisites

To ensure successful compilation and execution, the host machine must have:

- **Python 3.8 to 3.13**
- **Java 11 or higher**
- **Gradle** (used for building the Java project and protobuf stubs)
- **Microphone Access:** The OS must have a working microphone, and Java must be granted privacy permissions to access it.
- **Open Port:** Port `50051` must be free for local gRPC communication.

### 1. Python AI Server Setup

Navigate to the `python_ai_server` directory and install the required dependencies:

```bash
cd python_ai_server
pip install -r requirements.txt
```

### 2. Java Backend Setup

Navigate to the java_backend directory. Build the project to generate the gRPC protobuf stubs and compile the classes:

```bash
cd java_backend
gradle build
```

## Usage Instructions

Because this is a distributed system, you must boot the downstream AI server before starting the Java client.

### Step 1: Start the Python Server

Run the gRPC server to load the CNN model into memory and open the listening port (50051).

```bash
python server.py
```

Wait for the console to print "Server started on port 50051".

### Step 2: Start the Java Backend

Open a new terminal, navigate to the java_backend folder, and run the Main.java file. You will be greeted with a CLI menu offering 4 distinct operation modes:

```text
--- SELECT OPERATION MODE ---
1: Continuous Live Microphone
2: Network Stress Test (150 Simultaneous Requests)
3: CPU Parallel Benchmark
4: Trickle Stress Test (AIMD Demonstration)
```

### Operation Modes Explained

- Mode 1 (Live Demo): Boots the visual SmartHomeGUI and opens your laptop microphone. Say "On", "Off", "Yes", or "No" to see the system process your voice and trigger the UI in real-time.

- Mode 2 (Queue Stress): Blasts the system with 150 simultaneous audio requests to demonstrate the bounded queue and active backpressure rejection handlers.

- Mode 3 (Benchmark): A CPU-bound math test proving the speedup factor of parallel streaming vs. sequential execution.

- Mode 4 (AIMD Congestion Test): Trickles requests to the server while a small code script artificially bottlenecks the Python server. This mode generates data proving the Java watcher successfully slashed and recovered the thread pool size to prevent a crash.
