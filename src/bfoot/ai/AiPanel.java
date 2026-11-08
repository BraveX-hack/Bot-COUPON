package bfoot.ai;

import bfoot.data.ResponseCache;
import bfoot.util.Log;
import org.json.JSONObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Les 3 IA (Gemini, Mistral, Groq) relisent les matchs PRÉSÉLECTIONNÉS, par
 * lots de plusieurs matchs par appel.
 *
 * Économie de requêtes : avant, 3 appels IA par match analysé (~360 appels
 * pour 120 matchs). Maintenant, seuls les matchs candidats aux coupons sont
 * relus (30 max), 6 par appel, et chaque avis est mis en cache 12 h :
 * ~5 appels par IA au premier passage, presque 0 ensuite.
 *
 * Consensus : moyenne des facteurs des IA qui ont répondu ; un match est
 * écarté si la majorité des IA qui ont répondu le jugent imprévisible.
 */
public final class AiPanel {

    private static final Duration CACHE_TTL = Duration.ofHours(12);
    private static final int MAX_CONSECUTIVE_FAILURES = 2;

    public record Consensus(double homeFactor, double awayFactor, boolean avoid, int votes, List<String> reasons) {}

    public record ProviderStatus(String name, boolean configured, int reviewed, int fromCache, String error) {
        public boolean ok() { return configured && error == null && reviewed + fromCache > 0; }
    }

    public record Result(Map<Integer, Consensus> consensus, List<ProviderStatus> providers) {
        public static Result empty() { return new Result(Map.of(), List.of()); }
    }

    private final List<AiReviewer> reviewers;
    private final ResponseCache cache;
    private final int batchSize;

    public AiPanel(List<AiReviewer> reviewers, ResponseCache cache, int batchSize) {
        this.reviewers = reviewers;
        this.cache = cache;
        this.batchSize = Math.max(1, batchSize);
    }

    /** synchronized : deux sources analysées en parallèle ne doublent pas le débit vers les IA (quotas). */
    public synchronized Result review(List<MatchContext> matches) {
        if (matches.isEmpty() || reviewers.isEmpty()) return Result.empty();
        ExecutorService pool = Executors.newFixedThreadPool(reviewers.size());
        try {
            List<CompletableFuture<ProviderRun>> futures = new ArrayList<>();
            for (AiReviewer r : reviewers) {
                futures.add(CompletableFuture.supplyAsync(() -> runProvider(r, matches), pool));
            }
            Map<Integer, List<AiAdjustment>> votes = new HashMap<>();
            List<ProviderStatus> statuses = new ArrayList<>();
            for (CompletableFuture<ProviderRun> f : futures) {
                ProviderRun run = f.join();
                statuses.add(run.status);
                for (AiAdjustment a : run.adjustments) {
                    votes.computeIfAbsent(a.eventId(), k -> new ArrayList<>()).add(a);
                }
            }
            Map<Integer, Consensus> consensus = new HashMap<>();
            votes.forEach((id, list) -> consensus.put(id, combine(list)));
            return new Result(consensus, statuses);
        } finally {
            pool.shutdownNow();
        }
    }

    static Consensus combine(List<AiAdjustment> list) {
        double h = 0, a = 0;
        int avoid = 0;
        List<String> reasons = new ArrayList<>();
        for (AiAdjustment adj : list) {
            h += adj.homeFactor();
            a += adj.awayFactor();
            if (adj.avoid()) avoid++;
            if (adj.reason() != null && !adj.reason().isBlank()) reasons.add(adj.reason());
        }
        int n = list.size();
        return new Consensus(h / n, a / n, avoid * 2 > n, n, reasons);
    }

    private record ProviderRun(ProviderStatus status, List<AiAdjustment> adjustments) {}

    private ProviderRun runProvider(AiReviewer reviewer, List<MatchContext> matches) {
        if (!reviewer.configured()) {
            return new ProviderRun(new ProviderStatus(reviewer.name(), false, 0, 0, null), List.of());
        }
        List<AiAdjustment> results = new ArrayList<>();
        List<MatchContext> todo = new ArrayList<>();
        int fromCache = 0;
        for (MatchContext m : matches) {
            var cached = cache.get(cacheKey(reviewer, m), CACHE_TTL);
            if (cached.isPresent()) {
                try {
                    results.add(AiAdjustment.fromJson(new JSONObject(cached.get().body())));
                    fromCache++;
                    continue;
                } catch (Exception ignored) {
                    // entrée corrompue : on redemande
                }
            }
            todo.add(m);
        }

        int reviewed = 0, failures = 0;
        String error = null;
        long lastCall = 0;
        for (int i = 0; i < todo.size(); i += batchSize) {
            List<MatchContext> batch = todo.subList(i, Math.min(todo.size(), i + batchSize));
            try {
                long wait = lastCall + reviewer.minIntervalMs() - System.currentTimeMillis();
                if (lastCall > 0 && wait > 0) Thread.sleep(wait);
                lastCall = System.currentTimeMillis();
                List<AiAdjustment> adj = reviewer.review(batch);
                for (int k = 0; k < adj.size() && k < batch.size(); k++) {
                    cache.put(cacheKey(reviewer, batch.get(k)), 200, adj.get(k).toJson().toString());
                }
                results.addAll(adj);
                reviewed += batch.size();
                failures = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "interrompu";
                break;
            } catch (Exception e) {
                failures++;
                error = e.getMessage();
                Log.warn("IA " + reviewer.name() + " : lot ignoré — " + e.getMessage());
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.warn("IA " + reviewer.name() + " : 2 échecs de suite, on arrête de l'appeler pour cette analyse.");
                    break;
                }
            }
        }
        if (reviewed + fromCache > 0 && error != null) {
            error = "partiel : " + error;
        }
        return new ProviderRun(new ProviderStatus(reviewer.name(), true, reviewed, fromCache, error), results);
    }

    /**
     * L'avis est mis en cache par match ET par contexte : si un nouvel absent
     * est annoncé ou la composition confirmée, les IA sont consultées à nouveau.
     */
    private static String cacheKey(AiReviewer reviewer, MatchContext m) {
        String context = m.context() == null ? "" : m.context();
        return "ai:" + reviewer.name() + ":" + m.eventId() + ":" + Integer.toHexString(context.hashCode());
    }
}
