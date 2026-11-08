package bfoot.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** POST JSON avec une seule nouvelle tentative en cas de 429 (quota). */
final class AiHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final long MAX_RETRY_WAIT_MS = 30_000;

    private AiHttp() {}

    record Response(int status, String body) {}

    static Response post(String url, Map<String, String> headers, String body) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429 && attempt == 0) {
                long wait = r.headers().firstValue("retry-after")
                    .map(v -> {
                        try {
                            return (long) (Double.parseDouble(v) * 1000);
                        } catch (NumberFormatException e) {
                            return 10_000L;
                        }
                    }).orElse(10_000L);
                Thread.sleep(Math.min(MAX_RETRY_WAIT_MS, Math.max(1_000, wait)));
                continue;
            }
            return new Response(r.statusCode(), r.body());
        }
    }
}
