package io.github.huaaudio.neomusicbot.diagnostics;

import club.minnced.opus.util.OpusLibrary;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import net.dv8tion.jda.internal.audio.AudioEncryption;
import net.dv8tion.jda.internal.audio.AudioPacket;
import net.dv8tion.jda.internal.audio.CryptoAdapter;
import net.dv8tion.jda.internal.utils.ResizingByteBuffer;
import tomp2p.opuswrapper.Opus;

/** Exercise the actual JDA transport dependencies offline; no Discord session is created. */
final class VoiceRuntimeSelfTest
{
    private VoiceRuntimeSelfTest() { }

    static byte[] verifyOpus() throws IOException
    {
        OpusLibrary.loadFromJar();
        if(!OpusLibrary.isInitialized()) throw new IOException("JDA Opus library did not load");
        Opus opus = Opus.INSTANCE;
        PointerByReference encoder = null;
        PointerByReference decoder = null;
        try
        {
            IntBuffer error = IntBuffer.allocate(1);
            encoder = opus.opus_encoder_create(48_000, 2, Opus.OPUS_APPLICATION_AUDIO, error);
            if(encoder == null || error.get(0) != Opus.OPUS_OK)
                throw new IOException("Could not create JDA Opus encoder");
            decoder = opus.opus_decoder_create(48_000, 2, error);
            if(decoder == null || error.get(0) != Opus.OPUS_OK)
                throw new IOException("Could not create JDA Opus decoder");

            ShortBuffer input = ShortBuffer.allocate(960 * 2);
            for(int sample = 0; sample < 960; sample++)
            {
                short value = (short) (8_000 * Math.sin(2 * Math.PI * 440 * sample / 48_000));
                input.put(sample * 2, value);
                input.put(sample * 2 + 1, value);
            }
            ByteBuffer encoded = ByteBuffer.allocateDirect(4_096);
            int bytes = opus.opus_encode(encoder, input, 960, encoded, encoded.capacity());
            if(bytes <= 0 || bytes > encoded.capacity()) throw new IOException("JDA Opus encoding failed");
            byte[] packet = new byte[bytes];
            encoded.get(packet);
            ShortBuffer output = ShortBuffer.allocate(960 * 2);
            if(opus.opus_decode(decoder, packet, bytes, output, 960, 0) != 960)
                throw new IOException("JDA Opus decoding failed");
            boolean audibleSamples = false;
            for(int i = 0; i < output.capacity(); i++) audibleSamples |= output.get(i) != 0;
            if(!audibleSamples) throw new IOException("JDA Opus test tone decoded to silence");
            return packet;
        }
        finally
        {
            if(decoder != null) opus.opus_decoder_destroy(decoder);
            if(encoder != null) opus.opus_encoder_destroy(encoder);
        }
    }

    static void verifyTransportEncryption(byte[] opusPacket) throws IOException
    {
        for(AudioEncryption mode : new AudioEncryption[]{AudioEncryption.AEAD_AES256_GCM_RTPSIZE,
                AudioEncryption.AEAD_XCHACHA20_POLY1305_RTPSIZE})
        {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            CryptoAdapter sender = CryptoAdapter.getAdapter(mode, key);
            CryptoAdapter receiver = CryptoAdapter.getAdapter(mode, key);
            var wire = new ResizingByteBuffer(ByteBuffer.allocate(64));
            new AudioPacket((char) 1, 960, 42, ByteBuffer.wrap(opusPacket)).asEncryptedPacket(sender, wire);
            byte[] encrypted = new byte[wire.buffer().remaining()];
            wire.buffer().get(encrypted);
            var clear = new ResizingByteBuffer(ByteBuffer.allocate(64));
            AudioPacket decoded = new AudioPacket(encrypted).asDecryptAudioPacket(receiver, 42, clear);
            if(decoded == null) throw new IOException("JDA RTP decryption failed");
            byte[] actual = new byte[decoded.getEncodedAudio().remaining()];
            decoded.getEncodedAudio().get(actual);
            if(!Arrays.equals(opusPacket, actual)) throw new IOException("JDA RTP payload mismatch");

            // Corrupt the authenticated RTP timestamp, then the encrypted payload.
            for(int offset : new int[]{4, 12})
            {
                byte[] corrupt = encrypted.clone();
                corrupt[offset] ^= 1;
                boolean rejected;
                try { rejected = new AudioPacket(corrupt).asDecryptAudioPacket(receiver, 42, clear) == null; }
                catch(RuntimeException expected) { rejected = true; }
                if(!rejected) throw new IOException("JDA RTP accepted a modified packet");
            }
        }
    }
}
