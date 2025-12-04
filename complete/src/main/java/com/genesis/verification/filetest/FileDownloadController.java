package com.genesis.verification.filetest;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api")
public class FileDownloadController {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadController.class);

    private static final Path FILE = Paths.get("C:\\Users\\itsme\\Downloads\\AirParrot-3.1.8-64.msi"); // Change this
    private static final String FILE_NAME = FILE.getFileName().toString();
    private static final MediaType OCTET_STREAM = MediaType.APPLICATION_OCTET_STREAM;

    // Cache file size (avoid calling Files.size() on every request)
    private static final long FILE_SIZE = initializeFileSize();

    private static long initializeFileSize() {
        try {
            return Files.size(FILE);
        } catch (IOException e) {
            log.error("Cannot access file: {}", FILE, e);
            throw new IllegalStateException("File not accessible: " + FILE, e);
        }
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletRequest request) {

        try {
            if (!Files.exists(FILE) || !Files.isReadable(FILE)) {
                log.warn("File not found or not readable: {}", FILE);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // No Range header → full file download (200 OK)
            if (rangeHeader == null || rangeHeader.trim().isEmpty()) {
                return fullDownload();
            }

            // Parse Range header
            List<HttpRange> ranges = parseRanges(rangeHeader);
            if (ranges.isEmpty()) {
                return rangeNotSatisfiable();
            }

            // Single range → standard 206
            if (ranges.size() == 1) {
                return singleRangeDownload(ranges.get(0));
            }

            // Multiple ranges → multipart/byteranges
            return multipartByteRangesDownload(ranges);

        } catch (Exception e) {
            log.error("Unexpected error during download", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ResponseEntity<StreamingResponseBody> fullDownload() {
        StreamingResponseBody stream = out -> {
            try (var in = Files.newInputStream(FILE)) {
                in.transferTo(out);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILE_NAME + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(OCTET_STREAM)
                .contentLength(FILE_SIZE)
                .body(stream);
    }

    private ResponseEntity<StreamingResponseBody> singleRangeDownload(HttpRange range) {
        long start = range.getRangeStart(FILE_SIZE);
        long end = range.getRangeEnd(FILE_SIZE);
        long contentLength = end - start + 1;

        StreamingResponseBody stream = out -> {
            try (var in = Files.newInputStream(FILE)) {
                in.skipNBytes(start);
                in.transferTo(out);
            }
        };

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILE_NAME + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + FILE_SIZE)
                .contentType(OCTET_STREAM)
                .contentLength(contentLength)
                .body(stream);
    }

    private ResponseEntity<StreamingResponseBody> multipartByteRangesDownload(List<HttpRange> ranges) {
        String boundary = "MULTIPART_BYTERANGES_BOUNDARY_" + System.nanoTime();

        StreamingResponseBody stream = out -> {
            // Use FileChannel for ranged reads
            try (FileChannel channel = FileChannel.open(FILE, StandardOpenOption.READ)) {
                WritableByteChannel outChannel = Channels.newChannel(out);
                for (HttpRange range : ranges) {
                    long start = range.getRangeStart(FILE_SIZE);
                    long end = range.getRangeEnd(FILE_SIZE);
                    long length = end - start + 1;

                    // Write part header
                    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Type: application/octet-stream\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Range: bytes " + start + "-" + end + "/" + FILE_SIZE + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

                    // Transfer requested bytes
                    long remaining = length;
                    long offset = start;
                    while (remaining > 0) {
                        long transferred = channel.transferTo(offset, remaining, outChannel);
                        if (transferred <= 0) {
                            break;
                        }
                        remaining -= transferred;
                        offset += transferred;
                    }
                    if (remaining != 0) {
                        log.warn("Incomplete chunk write: expected {} bytes but {} bytes remaining", length, remaining);
                    }

                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
        };

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_TYPE, "multipart/byteranges; boundary=" + boundary)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(stream);
    }

    private List<HttpRange> parseRanges(String rangeHeader) {
        try {
            return HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid Range header: {}", rangeHeader);
            return Collections.emptyList();
        }
    }

    private ResponseEntity<StreamingResponseBody> rangeNotSatisfiable() {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + FILE_SIZE)
                .build();
    }

    // HEAD request – clients use this to check Accept-Ranges and size
    @RequestMapping(value = "/download", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head() {
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(FILE_SIZE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILE_NAME + "\"")
                .contentType(OCTET_STREAM)
                .build();
    }

    // Global exception handler (optional but recommended)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleException(Exception e) {
        log.error("Download failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
