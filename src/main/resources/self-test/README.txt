NeoMusicBot offline codec fixtures

These 0.4-second, 440 Hz stereo tones were generated for this project from the
FFmpeg lavfi sine source (48 kHz) and are distributed under the project's Apache
2.0 license. They contain no third-party recordings.

Reproduction commands (FFmpeg with AAC, libopus and libmp3lame support):
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -ac 2 -t 0.4 -c:a aac -b:a 96k tone.m4a
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -ac 2 -t 0.4 -c:a libopus -b:a 64k tone.ogg
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -ac 2 -t 0.4 -c:a libmp3lame -b:a 96k tone.mp3

The self-test loads only these internal fixtures. Local file playback remains
unavailable through Discord commands. It does not connect to Discord or prove
online source availability, audible voice delivery, or a DAVE handshake.
