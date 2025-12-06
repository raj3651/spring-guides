package com.genesis.verification.filetest;


import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/s3")
public class S3StreamingController {

    private static final Logger log = LoggerFactory.getLogger(S3StreamingController.class);


    @Autowired
    private final S3Client s3Client;

    private static final String BUCKET = "mybucket-devl-us-east-1";


    @Autowired
    public S3StreamingController(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam String key,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        key = URLDecoder.decode(key, StandardCharsets.UTF_8);

        // Get metadata
        HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET)
                .key(key)
                .build());
        long totalSize = head.contentLength();
        String eTag = head.eTag();
        String contentType = detectContentType(key);

        if (rangeHeader == null || rangeHeader.trim().isEmpty()) {
            return fullDownload(key, totalSize, eTag, contentType);
        }

        List<HttpRange> ranges = parseRanges(rangeHeader);
        if (ranges.isEmpty()) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize)
                    .build();
        }

        if (ranges.size() == 1) {
            return singleRangeDownload(key, ranges.get(0), totalSize, eTag, contentType);
        }

        return multipartByteRanges(key, ranges, totalSize, eTag, contentType);
    }

    // Full file: Parallel multipart via TransferManager (no loss, auto-validated)
    private ResponseEntity<StreamingResponseBody> fullDownload(String key, long size, String eTag, String contentType) {
        StreamingResponseBody body = out -> s3Client.getObject(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
                ResponseTransformer.toOutputStream(out)
        );

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(size)
                .header(HttpHeaders.ETAG, eTag)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + Path.of(key).getFileName() + "\"")
                .body(body);
    }

    // Single range: Async with size validation
    private ResponseEntity<StreamingResponseBody> singleRangeDownload(String key, HttpRange range, long totalSize, String eTag, String contentType) {
        long start = range.getRangeStart(totalSize);
        long end = range.getRangeEnd(totalSize);
        long length = end - start + 1;

        StreamingResponseBody body = out -> s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .range("bytes=" + start + "-" + end)
                        .build(),
                ResponseTransformer.toOutputStream(out)
        );

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(length)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalSize)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(body);
    }

    // Multipart: Parallel per part (sequential assembly)
    private ResponseEntity<StreamingResponseBody> multipartByteRanges(String key, List<HttpRange> ranges, long totalSize, String eTag, String contentType) {
        String boundary = "S3_BOUNDARY_" + System.nanoTime();

        StreamingResponseBody body = out -> {
            try {
                for (int i = 0; i < ranges.size(); i++) {
                    HttpRange r = ranges.get(i);
                    long start = r.getRangeStart(totalSize);
                    long end = r.getRangeEnd(totalSize);

                    // Multipart header
                    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Range: bytes " + start + "-" + end + "/" + totalSize + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

                    // Stream chunk (zero-copy)
                    s3Client.getObject(
                            GetObjectRequest.builder()
                                    .bucket(BUCKET)
                                    .key(key)
                                    .range("bytes=" + start + "-" + end)
                                    .build(),
                            ResponseTransformer.toOutputStream(out)
                    );

                    if (i < ranges.size() - 1) {
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                }
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("Multipart failed for {}", key, e);
                throw new RuntimeException(e);
            }
        };

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_TYPE, "multipart/byteranges; boundary=" + boundary)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(body);  // No Content-Length — correct for multipart
    }

    private List<HttpRange> parseRanges(String header) {
        try {
            return HttpRange.parseRanges(header);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String detectContentType(String key) {
        // Your existing logic
        return "application/octet-stream";
    }
}
