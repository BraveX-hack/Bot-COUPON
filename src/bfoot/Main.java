package bfoot;

import bfoot.ai.AiPanel;
import bfoot.ai.AiReviewer;
import bfoot.ai.ChatCompletionsReviewer;
import bfoot.ai.GeminiReviewer;
import bfoot.bot.FootBot;
import bfoot.data.CachedSource;
import bfoot.data.DataProvider;
import bfoot.data.DuoProvider;
import bfoot.data.FootballData;
import bfoot.data.HttpFetcher;
import bfoot.data.RequestStats;
import bfoot.data.ResponseCache;
import bfoot.data.SofascoreClient;
import bfoot.engine.CouponEngine;
import bfoot.store.ClubList;
import bfoot.store.PredictionLog;
import bfoot.util.Env;
import bfoot.util.Log;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) throws Exception {
        String token = Env.get("TELEGRAM_BOT_TOKEN");
        if (token == null) {
            System.err.println("❌ TELEGRAM_BOT_TOKEN est introuvable dans le .env.");
            return;
        }
        ZoneId zone = zone();

        ResponseCache cache = new ResponseCache(Path.of(Env.get("CACHE_DIR", "cache")));
        int purged = cache.purgeOlderThan(Duration.ofDays(30));
        if (purged > 0) Log.info(purged + " fichier(s) de cache périmé(s) supprimé(s).");

        // Sources de données : DATA_SOURCES=sofascore,duo (par défaut : toutes celles qui sont configurées).
        Set<String> wanted = Arrays.stream(Env.get("DATA_SOURCES", "sofascore,duo").toLowerCase(Locale.ROOT).split("[,;\\s]+"))
            .collect(Collectors.toSet());
        List<DataProvider> providers = new ArrayList<>();
        if (wanted.contains("sofascore")) {
            try {
                // Cache devant Sofascore : la plupart des appels ne partent jamais sur le réseau.
                providers.add(new FootballData(new CachedSource(cache, SofascoreClient.start())));
            } catch (Exception e) {
                Log.error("Source Sofascore désactivée (serveur Python indisponible)", e);
            }
        }
        if (wanted.contains("duo")) {
            DataProvider duo = duo(cache);
            if (duo != null) providers.add(duo);
        }
        if (providers.isEmpty()) {
            System.err.println("❌ Aucune source de données disponible : configure Sofascore (Python) ou FOOTBALL_DATA_KEY.");
            return;
        }
        Log.info("Sources actives : " + providers.stream().map(DataProvider::name).collect(Collectors.joining(", ")));

        // Les 3 IA. Une IA sans clé API est simplement ignorée.
        List<AiReviewer> reviewers = List.of(new GeminiReviewer(), ChatCompletionsReviewer.mistral(), ChatCompletionsReviewer.groq());
        AiPanel aiPanel = new AiPanel(reviewers, cache, Env.getInt("AI_BATCH_SIZE", 6));

        List<CouponEngine> engines = providers.stream()
            .map(p -> new CouponEngine(p, aiPanel, Clock.systemUTC(), zone)).toList();
        Path dataDir = Path.of(Env.get("DATA_DIR", "data"));
        PredictionLog predictionLog = new PredictionLog(dataDir);
        ClubList clubs = new ClubList(dataDir);

        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, new FootBot(token, engines, predictionLog, clubs, zone));
            Log.info("🚀 Bot B-Foot démarré.");
            new CountDownLatch(1).await();
        }
    }

    /**
     * Source "Duo" : football-data.org (FOOTBALL_DATA_KEY, obligatoire) + The
     * Odds API (ODDS_API_KEY, facultative : sans elle, pas de cotes donc pas de
     * coupons, mais /pronostics et /analyse fonctionnent avec le modèle seul).
     */
    private static DataProvider duo(ResponseCache cache) {
        String fdKey = Env.get("FOOTBALL_DATA_KEY");
        if (fdKey == null) {
            Log.info("Source Duo inactive : ajoute FOOTBALL_DATA_KEY (et ODDS_API_KEY) dans le .env pour l'activer.");
            return null;
        }
        RequestStats stats = new RequestStats();
        HttpFetcher fdFetcher = new HttpFetcher("https://api.football-data.org/v4", Map.of("X-Auth-Token", fdKey), null,
            Env.getInt("FOOTBALL_DATA_MIN_INTERVAL_MS", 6500)); // offre gratuite : 10 requêtes / minute
        CachedSource fd = new CachedSource(cache, fdFetcher, stats, CachedSource.Policy.OFFICIAL_API, "fdo:");

        String oddsKey = Env.get("ODDS_API_KEY");
        HttpFetcher oddsFetcher = null;
        CachedSource odds = null;
        if (oddsKey != null) {
            oddsFetcher = new HttpFetcher("https://api.the-odds-api.com/v4", Map.of(), "apiKey=" + oddsKey, 1000);
            odds = new CachedSource(cache, oddsFetcher, stats, CachedSource.Policy.OFFICIAL_API, "odds:");
        } else {
            Log.warn("ODDS_API_KEY absente : la source Duo n'aura pas de cotes (pas de coupons Duo).");
        }
        final HttpFetcher of = oddsFetcher;
        return new DuoProvider(fd, odds, stats,
            Duration.ofHours(Math.max(1, Env.getInt("ODDS_API_CACHE_HOURS", 12))),
            Env.get("ODDS_API_REGIONS", "eu"),
            () -> {
                if (of == null) return "pas de clé The Odds API";
                String left = of.lastHeader("x-requests-remaining");
                return left == null ? null : "The Odds API : " + left + " crédits restants";
            });
    }

    /** Fuseau d'affichage : BOT_TIMEZONE, sinon AUTO_REPORT_TIMEZONE, sinon Africa/Porto-Novo. */
    private static ZoneId zone() {
        String z = Env.get("BOT_TIMEZONE", Env.get("AUTO_REPORT_TIMEZONE", "Africa/Porto-Novo"));
        try {
            return ZoneId.of(z);
        } catch (Exception e) {
            Log.warn("Fuseau invalide « " + z + " », Africa/Porto-Novo utilisé.");
            return ZoneId.of("Africa/Porto-Novo");
        }
    }
}
