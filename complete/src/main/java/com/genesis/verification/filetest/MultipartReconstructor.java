package com.genesis.verification.filetest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Usage:
 * java MultipartReconstructor <response.bin> <reconstructed.bin>
 *
 * Reads the saved multipart/byteranges response and writes concatenated part bodies
 * to the output file.
 */
public class MultipartReconstructor {

    public static void main(String[] args) throws IOException {
        args = new String[2];
        args[0] = "C:\\Users\\itsme\\Desktop\\multpart_download004";
        args[1] = "C:\\Users\\itsme\\Desktop\\decoded.mp4";
        if (args.length != 2) {
            System.err.println("Usage: java MultipartReconstructor <response.bin> <reconstructed.bin>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        reconstruct(in, out);
        System.out.println("Reconstruction finished: " + out);
    }

    public static void reconstruct(Path responseFile, Path outputFile) throws IOException {
        byte[] raw = Files.readAllBytes(responseFile);

        // Convert to ISO_8859_1 string for byte-preserving manipulations
        String s = new String(raw, StandardCharsets.ISO_8859_1);

        // Find boundary from the first line that starts with --
        Pattern p = Pattern.compile("(?m)^--([^\r\n]+)");
        Matcher m = p.matcher(s);
        if (!m.find()) {
            throw new IllegalStateException("Boundary not found in response");
        }
        String boundary = m.group(1);
        String sep = "--" + Pattern.quote(boundary);

        // Split by boundary occurrences
        String[] parts = s.split(sep);

        // Open output
        var outStream = Files.newOutputStream(outputFile);

        try (outStream) {
            for (int i = 1; i < parts.length; i++) { // parts[0] is preamble
                String part = parts[i];

                // final boundary marker will start with "--"
                if (part.startsWith("--")) {
                    break;
                }

                // Remove leading CRLF if present
                if (part.startsWith("\r\n")) {
                    part = part.substring(2);
                }

                // Headers and body separated by CRLF CRLF
                int idx = part.indexOf("\r\n\r\n");
                if (idx < 0) {
                    continue;
                }

                String bodyStr = part.substring(idx + 4);

                // Remove trailing CRLF between parts if present
                if (bodyStr.endsWith("\r\n")) {
                    bodyStr = bodyStr.substring(0, bodyStr.length() - 2);
                }

                // Write bytes (ISO_8859_1 preserves original bytes)
                outStream.write(bodyStr.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
    }
}
