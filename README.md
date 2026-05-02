# iOS To PC (ios2pc)

A MicYou desktop plugin that bridges iOS companion clients with the MicYou PC host, enabling iPhone/iPad devices to act as wireless microphones.

## How It Works

```
┌─────────────┐      TCP Control       ┌──────────────┐
│   iPhone    │ ◄────────────────────► │  ios2pc-myp  │
│  (iOS App)  │                        │   (Plugin)   │
│             │      UDP Audio         │              │
│             │ ─────────────────────► │              │
└─────────────┘                        └──────┬───────┘
                                              │
                                              ▼
                                       ┌──────────────┐
                                       │  MicYou Host │
                                       │ (AudioEffect)│
                                       └──────┬───────┘
                                              │
                                              ▼
                                       ┌──────────────┐
                                       │ Virtual Mic  │
                                       │  (VB-Cable)  │
                                       └──────────────┘
```

## Features

- **Dual-channel communication**: TCP for control, UDP for low-latency audio
- **Automatic audio injection**: Registers as an `AudioEffectProvider` in the MicYou pipeline
- **Device management**: Tracks connected iOS devices with keepalive
- **Protocol compatibility**: Uses Protobuf-encoded messages with magic headers

## Building

```bash
# Build plugin JAR
./gradlew jar

# The output will be in build/libs/ios2pc.jar
# Package as a MicYou plugin (zip with plugin.json + jar)
```

## Installation

1. Build the plugin: `./gradlew jar`
2. Create a plugin package:
   ```
   ios2pc-myp/
   ├── plugin.json
   └── plugin.jar
   ```
3. Zip the folder and import via MicYou's plugin manager

## Protocol

### Message Format

All messages use the following binary format:
- **4 bytes**: Magic number (`0x694F5354` = "iOST")
- **4 bytes**: Payload length (big-endian)
- **N bytes**: Protobuf-encoded payload

### Control Messages

| Type | Direction | Description |
|------|-----------|-------------|
| `HELLO` | iOS → PC | Initial handshake with device info |
| `ACK` | PC → iOS | Acknowledgment with port info |
| `KEEPALIVE` | iOS → PC | Periodic heartbeat (every 5s) |
| `DISCONNECT` | iOS → PC | Graceful disconnect |

### Audio Frames

PCM16LE audio data encoded as `IosAudioFrame`:
- `streamId`: Device identifier
- `sequence`: Monotonically increasing frame number
- `timestamp`: Milliseconds since epoch
- `sampleRate`: e.g., 48000
- `channels`: e.g., 1 (mono)
- `pcm16le`: Raw PCM16LE bytes

## Configuration

No configuration required. The plugin auto-binds to available ports and waits for iOS connections.

## Compatibility

- **MicYou Desktop**: v1.0.0+
- **iOS Client**: MicYou iOS app
- **Platform**: Desktop (Windows/Linux/macOS)

## License

MIT
