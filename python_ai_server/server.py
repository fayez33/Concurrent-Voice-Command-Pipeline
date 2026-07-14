import grpc
from concurrent import futures
import numpy as np
import librosa
import tensorflow as tf
import time
import random

# Import the generated gRPC files
import keyword_spotter_pb2
import keyword_spotter_pb2_grpc

# 1. Boot Sequence: Load the Heavy Model Once into RAM
print("Booting AI Engine...")
model = tf.keras.models.load_model('speech_commands_1.keras')
CLASSES = ['on', 'off', 'yes', 'no', 'unknown']
print("Model loaded successfully. Ready for inference.")

# 2. Define the Network Service
class AudioProcessorServicer(keyword_spotter_pb2_grpc.AudioProcessorServicer):

    def PredictCommand(self, request, context):
        try:
            # Step A: Unpack the Protobuf network payload
            # We use np.frombuffer to instantly convert the raw network bytes back into a float array
            audio_data = np.frombuffer(request.audio_data, dtype=np.float32)

            # ---------------------------------------------------------
            # Artificial Delay: Intermittent Network Bottleneck (AIMD Demo)
            # ---------------------------------------------------------
            # Only inject the bottleneck 35% of the time so the pool can recover.
            # This pushes the processing time over the 850ms threshold, 
            # forcing the Java watcher to slash the pool size down.
            # Uncomment the following if you want to test the AIMD feature:

            #if random.random() < 0.35:
            #    time.sleep(random.uniform(0.8, 1.1))
            
            # ---------------------------------------------------------
            
            # Step B: Digital Signal Processing (MFCC)
            sample_rate = 16000
            mfcc = librosa.feature.mfcc(
                y=audio_data, sr=sample_rate, n_mfcc=40)

            max_pad_len = 32
            if mfcc.shape[1] < max_pad_len:
                pad_width = max_pad_len - mfcc.shape[1]
                mfcc = np.pad(mfcc, pad_width=(
                    (0, 0), (0, pad_width)), mode='constant')
            else:
                mfcc = mfcc[:, :max_pad_len]

            mfcc_input = mfcc[np.newaxis, ..., np.newaxis]

            # Step C: Neural Network Inference
            predictions = model.predict(mfcc_input, verbose=0)
            predicted_index = np.argmax(predictions[0])
            # Convert to standard Python float for gRPC
            confidence = float(predictions[0][predicted_index])

            # Step D: The Confidence Threshold Gate to reduce false positives
            if confidence >= 0.90:
                final_command = CLASSES[predicted_index]
            else:
                final_command = "unknown"

            print(
                f"[{request.client_id}] Heard: {final_command.upper()} (Confidence: {confidence*100:.1f}%)")

            # Step E: Pack and return the Protobuf response
            return keyword_spotter_pb2.PredictionResponse(
                command=final_command,
                confidence=confidence,
                success=True
            )

        except Exception as e:
            print(f"Server Error during processing: {e}")
            # If the CNN crashes, gracefully tell Java it failed so Java can fallback
            return keyword_spotter_pb2.PredictionResponse(
                command="error",
                confidence=0.0,
                success=False
            )

# 3. Start the Server
def serve():
    # Use a basic ThreadPool for Python's network connections
    # (The heavy queuing and dropping will be handled by Java)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=5))
    keyword_spotter_pb2_grpc.add_AudioProcessorServicer_to_server(
        AudioProcessorServicer(), server)

    # Bind to port 50051 on the local machine
    server.add_insecure_port('[::]:50051')
    server.start()
    print("gRPC Server is listening on port 50051...")
    server.wait_for_termination()


if __name__ == '__main__':
    serve()