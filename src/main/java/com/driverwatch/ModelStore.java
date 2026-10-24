package com.driverwatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Downloads the unmodified OpenCV Haar cascades on first run. */
final class ModelStore {
    private static final String BASE =
            "https://raw.githubusercontent.com/opencv/opencv/4.x/data/haarcascades/";

    static Path ensure(String name) throws IOException, InterruptedException {
        if (!name.equals("haarcascade_frontalface_default.xml") &&
            !name.equals("haarcascade_eye_tree_eyeglasses.xml")) {
            throw new IllegalArgumentException("Unknown model file: " + name);
        }
        Path folder = Path.of("models");
        Files.createDirectories(folder);
        Path destination = folder.resolve(name);
        if (Files.exists(destination) && Files.size(destination) > 100_000) return destination;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + name))
                .timeout(Duration.ofSeconds(45))
                .GET().build();
        Path partial = Files.createTempFile(folder, "model-", ".xml");
        try {
            HttpResponse<Path> response = http.send(request,
                    HttpResponse.BodyHandlers.ofFile(partial));
            if (response.statusCode() != 200 || Files.size(partial) < 100_000) {
                throw new IOException("Could not download OpenCV model " + name
                        + " (HTTP " + response.statusCode() + ")");
            }
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private ModelStore() {}
}
