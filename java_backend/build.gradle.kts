import com.google.protobuf.gradle.*

plugins {
    java
    id("com.google.protobuf") version "0.9.4"
}

group = "com.miniproject.year4"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val grpcVersion = "1.62.2"
val protobufVersion = "3.25.3"

dependencies {
    // gRPC Network and Protobuf Serialization Libraries
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    // Required annotation for Java 9+ compatibility with gRPC
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

// This block tells Gradle exactly how to compile the .proto file into Java code
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
            }
        }
    }
}