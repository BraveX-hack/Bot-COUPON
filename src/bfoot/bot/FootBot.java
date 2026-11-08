package bfoot.bot;

import bfoot.data.DataProvider;
import bfoot.engine.CouponEngine;
import bfoot.engine.CouponReport;
import bfoot.engine.PredictionReport;
import bfoot.engine.SourceComparison;
import bfoot.store.ClubList;
import bfoot.store.PredictionLog;
import bfoot.util.Env;
import bfoot.util.Log;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bot Telegram B-Foot.
 *
 * Commandes :
 *   /semaine [jours]      3 coupons de cote ~10 sur les N prochains jours (7 par défaut)
 *   /brave                alias de /semaine (compatibilité avec ton envoi automatique)
 *   /grandsmatchs [jours] grands matchs du mois (30 jours) : 1 coupon sécurité ~50 % + 1 coupon cote ~120
 *   /clubs [liste]        affiche ou remplace la liste des clubs toujours considérés comme "grands"
 *   /pronostics [jours]   pronostic clair pour chaque grand match des N prochains jours (3 par défaut)
 *   /analyse <équipe>     fiche complète du prochain match d'une équipe
 *   /bilan                résultats des coupons envoyés, par type (ROI, réussite réelle vs prévue, CLV)
 *   /whoami               affiche ton chatId
 *
 * Sources : chaque analyse tourne sur toutes les sources configurées
 * (Sofascore, et "Duo" = football-data.org + The Odds API), en parallèle,
 * puis le bot envoie un message de comparaison des deux résultats.
 *
 * Sécurité / sobriété :
 *   - seuls les chatId de ALLOWED_CHAT_IDS (et AUTO_REPORT_CHAT_ID) ont accès ;
 *   - une seule analyse à la fois ;
 *   - un rapport de moins de REPORT_REUSE_MINUTES (60) est renvoyé tel quel, sans requête.
 */
public final class FootBot implements LongPollingSingleThreadUpdateConsumer {

    private static final int TELEGRAM_CHUNK = 3800;

    private final TelegramClient telegram;
    private final List<CouponEngine> engines;
    private final Map<String, DataProvider> providers = new LinkedHashMap<>();
    private final PredictionLog log;
    private final ClubList clubs;
    private final ZoneId zone;
    private final Set<Long> allowedChats;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final ExecutorService sourcePool = Executors.newCachedThreadPool();
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-report");
        t.setDaemon(true);
        return t;
    });

    private final Duration reportReuse = Duration.ofMinutes(Env.getInt("REPORT_REUSE_MINUTES", 60));
    private final Map<String, CachedReport> lastReports = new ConcurrentHashMap<>();

    private record CachedReport(Instant createdAt, List<String> parts) {}

    /** Résultat d'une source : la valeur, ou l'erreur si la source n'a pas pu tourner. */
    private record SourceResult<T>(String name, String key, T value, String error) {}

    public FootBot(String token, List<CouponEngine> engines, PredictionLog log, ClubList clubs, ZoneId zone) {
        this.telegram = new OkHttpTelegramClient(token);
        this.engines = engines;
        engines.forEach(e -> providers.put(e.provider().key(), e.provider()));
        this.log = log;
        this.clubs = clubs;
        this.zone = zone;
        this.allowedChats = Env.getLongSet("ALLOWED_CHAT_IDS");
        this.allowedChats.addAll(Env.getLongSet("AUTO_REPORT_CHAT_ID"));
        if (allowedChats.isEmpty()) {
            Log.warn("ALLOWED_CHAT_IDS vide : n'importe qui peut utiliser le bot. Ajoute ton chatId (voir /whoami).");
        }
        scheduleAutoReport();
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;
        String text = update.getMessage().getText().trim();
        long chatId = update.getMessage().getChatId();
        workers.submit(() -> {
            try {
                dispatch(chatId, text);
            } catch (Exception e) {
                Log.error("Commande " + text, e);
                send(chatId, "❌ Erreur inattendue. Les détails sont dans la console du bot.");
            }
        });
    }

    void dispatch(long chatId, String text) {
        String firstLine = text.split("\n", 2)[0].trim();
        String[] parts = firstLine.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1].trim() : "";
        int at = command.indexOf('@'); // /semaine@MonBot dans les groupes
        if (at > 0) command = command.substring(0, at);

        if (command.equals("/whoami")) {
            send(chatId, "Ton chatId : <code>" + chatId + "</code>\nAjoute-le à <code>ALLOWED_CHAT_IDS</code> dans le .env.");
            return;
        }
        if (!allowedChats.isEmpty() && !allowedChats.contains(chatId)) {
            send(chatId, "⛔ Accès réservé. Envoie /whoami et ajoute ton chatId à ALLOWED_CHAT_IDS.");
            return;
        }
        switch (command) {
            case "/start", "/aide", "/help" -> send(chatId, """
                ⚽ <b>B-Foot</b>
                /semaine — 3 coupons de cote ~10 avec les matchs des 7 prochains jours
                /grandsmatchs — grands matchs du mois : 1 coupon sécurité (~50 %) + 1 coupon cote ~120
                /pronostics — un pronostic clair (résultat, score, confiance) pour chaque grand match des 3 prochains jours
                /analyse Real Madrid — fiche complète du prochain match d'une équipe (puissance, forme, absents, notes, face-à-face)
                /clubs — voir ou modifier tes clubs favoris (ex : <code>/clubs Real Madrid, Arsenal, PSG</code>)
                /bilan — résultats des coupons déjà envoyés (par source)
                Chaque analyse est faite par toutes les sources configurées, puis comparée.
                /whoami — ton chatId""");
            case "/semaine", "/week", "/brave" -> {
                int days = parseDays(args, 7, 7);
                analysis(chatId, "semaine:" + days, "les matchs des " + days + " prochains jours",
                    () -> couponMessages(runAll(e -> e.weekly(days))));
            }
            case "/grandsmatchs", "/grands", "/mois" -> {
                int days = parseDays(args, 30, 31);
                analysis(chatId, "mois:" + days + ":" + clubs.clubs(), "les grands matchs des " + days + " prochains jours",
                    () -> couponMessages(runAll(e -> e.bigMatches(days, clubs))));
            }
            case "/clubs" -> clubsCommand(chatId, args);
            case "/pronostics", "/prono" -> {
                int days = parseDays(args, 3, 7);
                analysis(chatId, "prono:" + days + ":" + clubs.clubs(), "les grands matchs des " + days + " prochains jours",
                    () -> predictionMessages(runAll(e -> e.predictions(days, clubs))));
            }
            case "/analyse", "/analyser", "/match" -> {
                if (args.isBlank()) {
                    send(chatId, "Indique une équipe : <code>/analyse Real Madrid</code>");
                } else {
                    analysis(chatId, "analyse:" + ClubList.normalize(args), "le prochain match de " + ReportFormatter.esc(args),
                        () -> analysisMessages(runAll(e -> e.analyse(args, 30))));
                }
            }
            case "/bilan" -> send(chatId, ReportFormatter.summary(log.settle(providers, Instant.now())));
            default -> {
                if (command.startsWith("/")) send(chatId, "Commande inconnue. Tape /aide.");
            }
        }
    }

    private static int parseDays(String args, int defaultDays, int max) {
        if (args.isEmpty()) return defaultDays;
        try {
            return Math.max(1, Math.min(max, Integer.parseInt(args.split("\\s+")[0])));
        } catch (NumberFormatException e) {
            return defaultDays; // ex : ancienne syntaxe "/brave 2026-10-20"
        }
    }

    private void clubsCommand(long chatId, String args) {
        if (args.equalsIgnoreCase("vider") || args.equalsIgnoreCase("aucun")) {
            clubs.replace(List.of());
            send(chatId, "Liste de clubs vidée. Les grands matchs sont choisis uniquement selon la force des équipes.");
            return;
        }
        if (!args.isEmpty()) {
            clubs.replace(ClubList.parse(args));
            lastReports.keySet().removeIf(k -> k.startsWith("mois:"));
        }
        List<String> list = clubs.clubs();
        send(chatId, list.isEmpty()
            ? "Aucun club dans ta liste. Exemple : <code>/clubs Real Madrid, Barcelona, Arsenal, Bayern</code>\n"
                + "(écris les noms comme sur Sofascore ; <code>/clubs vider</code> pour tout effacer)"
            : "⭐ Clubs toujours inclus dans /grandsmatchs :\n• " + ReportFormatter.esc(String.join("\n• ", list)));
    }

    // --- Exécution sur toutes les sources ---

    /** Lance la même analyse sur chaque source, en parallèle ; une source en panne n'empêche pas les autres. */
    private <T> List<SourceResult<T>> runAll(Function<CouponEngine, T> job) {
        List<CompletableFuture<SourceResult<T>>> futures = new ArrayList<>();
        for (CouponEngine e : engines) {
            DataProvider p = e.provider();
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return new SourceResult<>(p.name(), p.key(), job.apply(e), null);
                } catch (Exception ex) {
                    Log.error("Analyse " + p.name(), ex);
                    return new SourceResult<T>(p.name(), p.key(), null, String.valueOf(ex.getMessage()));
                }
            }, sourcePool));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /** Coupons : un message par source (et enregistrement pour /bilan), puis la comparaison. */
    private List<String> couponMessages(List<SourceResult<CouponReport>> results) {
        List<String> parts = new ArrayList<>();
        List<CouponReport> ok = new ArrayList<>();
        for (SourceResult<CouponReport> r : results) {
            if (r.value() == null) {
                parts.add(ReportFormatter.sourceFailure(r.name(), r.error()));
                continue;
            }
            int saved = 0;
            for (CouponReport.Section s : r.value().sections()) {
                saved += log.record(r.key(), s.key(), s.result().tickets(), r.value().createdAt());
            }
            Log.info(r.name() + " : " + saved + " nouveau(x) coupon(s) enregistré(s).");
            parts.add(ReportFormatter.report(r.value(), zone));
            ok.add(r.value());
        }
        if (ok.size() >= 2) {
            CouponReport a = ok.get(0), b = ok.get(1);
            parts.add(a.cards().isEmpty() || b.cards().isEmpty()
                ? noComparison(a.cards().isEmpty() ? a.source() : b.source())
                : ReportFormatter.couponComparison(SourceComparison.compare(a, b)));
        }
        return parts;
    }

    private List<String> predictionMessages(List<SourceResult<PredictionReport>> results) {
        List<String> parts = new ArrayList<>();
        List<PredictionReport> ok = new ArrayList<>();
        for (SourceResult<PredictionReport> r : results) {
            if (r.value() == null) {
                parts.add(ReportFormatter.sourceFailure(r.name(), r.error()));
            } else {
                parts.add(ReportFormatter.predictions(r.value(), zone));
                ok.add(r.value());
            }
        }
        if (ok.size() >= 2) {
            PredictionReport a = ok.get(0), b = ok.get(1);
            parts.add(a.cards().isEmpty() || b.cards().isEmpty()
                ? noComparison(a.cards().isEmpty() ? a.source() : b.source())
                : ReportFormatter.predictionComparison(a.source(), b.source(), SourceComparison.compare(a, b), zone));
        }
        return parts;
    }

    private static String noComparison(String emptySource) {
        return "⚖️ Comparaison impossible cette fois : " + ReportFormatter.esc(emptySource)
            + " n'a analysé aucun match (voir ses remarques ci-dessus). Les résultats de l'autre source restent utilisables.";
    }

    /** /analyse : la fiche de la première source qui trouve le match, puis l'avis des autres. */
    private List<String> analysisMessages(List<SourceResult<PredictionReport>> results) {
        List<String> parts = new ArrayList<>();
        PredictionReport main = null;
        for (SourceResult<PredictionReport> r : results) {
            if (r.value() != null && !r.value().cards().isEmpty()) {
                main = r.value();
                break;
            }
        }
        if (main == null) {
            for (SourceResult<PredictionReport> r : results) {
                parts.add(r.value() != null ? ReportFormatter.analysis(r.value(), zone) : ReportFormatter.sourceFailure(r.name(), r.error()));
            }
            return parts;
        }
        parts.add(ReportFormatter.analysis(main, zone));
        for (SourceResult<PredictionReport> r : results) {
            if (r.value() == main) continue;
            parts.add(r.value() != null
                ? ReportFormatter.analysisComparison(r.name(), main, r.value())
                : ReportFormatter.sourceFailure(r.name(), r.error()));
        }
        return parts;
    }

    /**
     * Lance une analyse (une seule à la fois) et envoie les messages. Un rapport
     * de moins de REPORT_REUSE_MINUTES est renvoyé tel quel, sans requête.
     */
    private void analysis(long chatId, String cacheKey, String what, Supplier<List<String>> run) {
        CachedReport cached = lastReports.get(cacheKey);
        if (cached != null && cached.createdAt().plus(reportReuse).isAfter(Instant.now())) {
            cached.parts().forEach(p -> sendLong(chatId, p));
            send(chatId, "<i>(rapport de " + cached.createdAt().atZone(zone).toLocalTime().withNano(0)
                + ", réutilisé sans nouvelle requête)</i>");
            return;
        }
        if (!analysisRunning.compareAndSet(false, true)) {
            send(chatId, "⏳ Une analyse est déjà en cours : le résultat arrive dans quelques minutes.");
            return;
        }
        try {
            String sources = String.join(" + ", providers.values().stream().map(DataProvider::name).toList());
            send(chatId, "⏳ J'analyse " + what + " (sources : " + ReportFormatter.esc(sources)
                + ")… quelques minutes la première fois, beaucoup moins ensuite grâce au cache.");
            List<String> parts = run.get();
            lastReports.put(cacheKey, new CachedReport(Instant.now(), parts));
            parts.forEach(p -> sendLong(chatId, p));
        } catch (Exception e) {
            Log.error("Analyse " + what, e);
            send(chatId, "❌ L'analyse a échoué : " + ReportFormatter.esc(String.valueOf(e.getMessage())));
        } finally {
            analysisRunning.set(false);
        }
    }

    // --- Envoi automatique quotidien (AUTO_REPORT_*) ---

    private void scheduleAutoReport() {
        Set<Long> target = Env.getLongSet("AUTO_REPORT_CHAT_ID");
        if (target.isEmpty()) return;
        long chatId = target.iterator().next();
        LocalTime time;
        try {
            time = LocalTime.parse(Env.get("AUTO_REPORT_TIME", "17:00"));
        } catch (Exception e) {
            Log.warn("AUTO_REPORT_TIME invalide, 17:00 utilisé.");
            time = LocalTime.of(17, 0);
        }
        String command = Env.get("AUTO_REPORT_COMMAND", "/semaine");
        Log.info("Envoi automatique chaque jour à " + time + " (" + zone + ") : " + command);
        scheduleNext(chatId, time, command);
    }

    private void scheduleNext(long chatId, LocalTime time, String command) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.with(time);
        if (!next.isAfter(now)) next = next.plusDays(1);
        scheduler.schedule(() -> {
            try {
                dispatch(chatId, command);
            } catch (Exception e) {
                Log.error("Envoi automatique", e);
            } finally {
                scheduleNext(chatId, time, command);
            }
        }, Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS);
    }

    // --- Envoi Telegram ---

    private void sendLong(long chatId, String html) {
        StringBuilder chunk = new StringBuilder();
        for (String line : html.split("\n", -1)) {
            if (chunk.length() + line.length() + 1 > TELEGRAM_CHUNK && !chunk.isEmpty()) {
                send(chatId, chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(line).append('\n');
        }
        if (!chunk.isEmpty()) send(chatId, chunk.toString());
    }

    private void send(long chatId, String html) {
        try {
            telegram.execute(SendMessage.builder().chatId(chatId).text(html).parseMode("HTML").build());
        } catch (TelegramApiException e) {
            // Repli en texte brut : le message arrive toujours, même si le HTML est refusé.
            Log.warn("Envoi HTML refusé (" + e.getMessage() + "), renvoi en texte brut.");
            try {
                String plain = html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
                telegram.execute(SendMessage.builder().chatId(chatId).text(plain).build());
            } catch (TelegramApiException e2) {
                Log.error("Envoi Telegram impossible", e2);
            }
        }
    }
}
