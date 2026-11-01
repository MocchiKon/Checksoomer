package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the SHA-1 hashing primitive, including byte counting and mid-file abort.
 */
class ChecksumsTest
{
    @Test
    void shouldMatchKnownVectorWhenHashingAbc(@TempDir Path tmp) throws Exception
    {
        // GIVEN a file containing "abc"
        File f = tmp.resolve("abc.txt").toFile();
        Files.writeString(f.toPath(), "abc");

        // WHEN it is hashed
        Checksums.Sha1Result result = Checksums.hash(f, () -> false, Checksums.newBuffer());

        // THEN the digest and byte count match the known vector
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", result.hex());
        assertEquals(3, result.bytesRead());
    }

    @Test
    void shouldProduceCanonicalEmptyDigestWhenFileIsEmpty(@TempDir Path tmp) throws Exception
    {
        // GIVEN an empty file
        File f = tmp.resolve("empty.txt").toFile();
        Files.write(f.toPath(), new byte[0]);

        // WHEN it is hashed
        Checksums.Sha1Result result = Checksums.hash(f, () -> false, Checksums.newBuffer());

        // THEN the canonical empty SHA-1 with zero bytes read is returned
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", result.hex());
        assertEquals(0, result.bytesRead());
    }

    @Test
    void shouldHashCorrectlyWhenFileSpansMultipleBuffers(@TempDir Path tmp) throws Exception
    {
        // GIVEN a file that spans several 1 MB reads plus a partial tail
        byte[] data = new byte[(3 * 1024 * 1024) + 7];
        for (int i = 0; i < data.length; i++)
        {
            data[i] = (byte) (i * 31 + 7);
        }
        File f = tmp.resolve("big.bin").toFile();
        Files.write(f.toPath(), data);

        // AND an independent expectation computed without going through Checksums
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String expected = HexFormat.of().formatHex(md.digest(data));

        // WHEN it is hashed
        Checksums.Sha1Result result = Checksums.hash(f, () -> false, Checksums.newBuffer());

        // THEN the digest and byte count match the independent expectation
        assertEquals(expected, result.hex());
        assertEquals(data.length, result.bytesRead());
    }

    @Test
    void shouldThrowAbortedExceptionWhenAbortRequestedMidFile(@TempDir Path tmp) throws Exception
    {
        // GIVEN a file larger than the buffer so the abort poll runs before the file ends
        byte[] data = new byte[2 * 1024 * 1024];
        File f = tmp.resolve("big.bin").toFile();
        Files.write(f.toPath(), data);

        // WHEN it is hashed with an always-abort control
        // THEN the hash aborts
        assertThrows(AbortedException.class,
                () -> Checksums.hash(f, () -> true, Checksums.newBuffer()));
    }
}
