package com.genesis.verification.filetest;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Usage:
 * java com.genesis.verification.filetest.RangeDownloader <file-url> <output-file-path> [ranges-comma-separated]
 *
 * Example:
 * java com.genesis.verification.filetest.RangeDownloader
 *   https://example.com/file.msi `C:\Users\itsme\Desktop\decoded.msi`
 *   0-7925350,7925351-15850701,15850702-23776052,23776053-31701403,31701404-39626753,39626754-47552103,47552104-55477453,55477454-63402803,63402804-71328153,71328154-79253503
 */
public class RangeDownloader {

    public static void main(String[] args) throws Exception {
        args = new String[3];
        args[0] = "https://www.airsquirrels.com/airparrot/download/app/windows/64?hsLang=en";
        args[0] = "http://localhost:8080/api/download";
        args[1] = "C:\\Users\\itsme\\Desktop\\decoded.msi";
        args[2] = "0-7925350,7925351-15850701,15850702-23776052,23776053-31701403,31701404-39626753,39626754-47552103,47552104-55477453,55477454-63402803,63402804-71328153,71328154-79253503";

        if (args.length < 2) {
            System.err.println("Usage: java RangeDownloader <file-url> <output-file-path> [ranges-comma-separated]");
            System.exit(2);
        }
        String url = args[0];
        Path outPath = Paths.get(args[1]);
        String rangesCsv = args.length >= 3
                ? args[2]
                : "0-7925350,7925351-15850701,15850702-23776052,23776053-31701403,31701404-39626753,39626754-47552103,47552104-55477453,55477454-63402803,63402804-71328153,71328154-79253503";

        List<long[]> ranges = parseRanges(rangesCsv);

        downloadAndAssemble(url, ranges, outPath);
        System.out.println("Download finished: " + outPath);
    }

    private static List<long[]> parseRanges(String csv) {
        String[] tokens = csv.split(",");
        List<long[]> list = new ArrayList<>(tokens.length);
        for (String t : tokens) {
            String[] p = t.trim().split("-");
            long start = Long.parseLong(p[0]);
            long end = Long.parseLong(p[1]);
            list.add(new long[] { start, end });
        }
        return list;
    }

    private static void downloadAndAssemble(String url, List<long[]> ranges, Path out) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // compute final size (max end + 1)
        long finalSize = ranges.stream().mapToLong(r -> r[1] + 1).max().orElse(0L);

        // pre-allocate file
        try (RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) {
            raf.setLength(finalSize);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>(ranges.size());

        for (int i = 0; i < ranges.size(); i++) {
            final int idx = i;
            final long start = ranges.get(i)[0];
            final long end = ranges.get(i)[1];
            String rangeHeader = "bytes=" + start + "-" + end;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Range", rangeHeader)
                    .GET()
                    .build();

            CompletableFuture<Void> f = client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAcceptAsync(resp -> {
                        int sc = resp.statusCode();
                        if (sc == 206 || (sc == 200 && ranges.size() == 1)) {
                            byte[] body = resp.body();
                            try (RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) {
                                raf.seek(start);
                                raf.write(body);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to write part " + idx + " to file", e);
                            }
                        } else {
                            throw new RuntimeException("Unexpected status for range " + rangeHeader + ": " + sc);
                        }
                    });

            futures.add(f);
        }

        // wait for all parts
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}
