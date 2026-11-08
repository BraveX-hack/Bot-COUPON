package bfoot.engine;

import bfoot.DuoWorld;
import bfoot.TestWorld;
import bfoot.ai.AiPanel;
import bfoot.data.CachedSource;
import bfoot.data.DuoProvider;
import bfoot.data.FootballData;
import bfoot.data.RequestStats;
import bfoot.data.ResponseCache;
import bfoot.model.Market;
import bfoot.store.ClubList;
import bfoot.store.PredictionLog;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Les deux sources analysent les mêmes matchs ; le bot compare leurs résultats. */
class SourceComparisonTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Porto-Novo");

    private record Setup(CouponEngine sofa, CouponEngine duo, Path dir) {}

    private static Setup setup() throws Exception {
        Instant now = Instant.now();
        TestWorld world = new TestWorld(now);
        DuoWorld duoWorld = new DuoWorld(world);
        Path dir = Files.createTempDirectory("bfoot-cmp");
        ResponseCache cache = new ResponseCache(dir);
        AiPanel noAi = new AiPanel(List.of(), cache, 6);
        FootballData sofa = new FootballData(new CachedSource(cache, world));
        RequestStats stats = new RequestStats();
        DuoProvider duo = new DuoProvider(
            new CachedSource(cache, duoWorld.footballData(), stats, CachedSource.Policy.OFFICIAL_API, "fdo:"),
            new CachedSource(cache, duoWorld.oddsApi(), stats, CachedSource.Policy.OFFICIAL_API, "odds:"),
            stats, Duration.ofHours(12), "eu", () -> null);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new Setup(new CouponEngine(sofa, noAi, clock, ZONE), new CouponEngine(duo, noAi, clock, ZONE), dir);
    }

    @Test
    void couponsDesDeuxSourcesComparesSelectionParSelection() throws Exception {
        Setup s = setup();
        CouponReport a = s.sofa().weekly(7);
        CouponReport b = s.duo().weekly(7);
        assertEquals("Sofascore", a.source());
        assertEquals("Duo", b.source());
        assertEquals(12, b.fixturesFound(), "Duo voit les mêmes 12 matchs");
        assertFalse(b.sections().get(0).result().tickets().isEmpty(), "Duo produit ses propres coupons");
        b.cards().forEach(c -> assertFalse(c.odds().containsKey(Market.BTTS_YES)));

        SourceComparison.CouponComparison cmp = SourceComparison.compare(a, b);
        assertEquals(12, cmp.commonMatches(), "les matchs sont rapprochés malgré des identifiants et des noms différents");
        long checked = cmp.tickets().stream().mapToLong(SourceComparison.TicketCheck::checked).sum();
        long agree = cmp.tickets().stream().mapToLong(SourceComparison.TicketCheck::confirmed).sum();
        assertTrue(checked > 0 && agree * 2 >= checked, "mêmes données sous-jacentes : majorité d'accords (" + agree + "/" + checked + ")");
        assertTrue(cmp.mostConfirmed().isPresent());
        String text = bfoot.bot.ReportFormatter.couponComparison(cmp);
        assertTrue(text.contains("✅"));
        System.out.println(bfoot.bot.ReportFormatter.report(b, ZONE));
        System.out.println(text);
    }

    @Test
    void pronosticsComparesEtAnalyse() throws Exception {
        Setup s = setup();
        ClubList clubs = new ClubList(s.dir().resolve("data"));
        PredictionReport a = s.sofa().predictions(3, clubs);
        PredictionReport b = s.duo().predictions(3, clubs);
        List<SourceComparison.PredictionPair> pairs = SourceComparison.compare(a, b);
        long both = pairs.stream().filter(SourceComparison.PredictionPair::bothKnown).count();
        long agree = pairs.stream().filter(SourceComparison.PredictionPair::agree).count();
        assertEquals(a.cards().size(), both, "chaque match Sofascore retrouvé chez Duo");
        assertTrue(agree * 3 >= both * 2, "au moins 2/3 de pronostics compatibles : " + agree + "/" + both);
        System.out.println(bfoot.bot.ReportFormatter.predictionComparison("Sofascore", "Duo", pairs, ZONE));

        PredictionReport fa = s.sofa().analyse("Équipe 104", 30);
        PredictionReport fb = s.duo().analyse("Équipe 104", 30);
        assertEquals(1, fb.cards().size(), "Duo trouve aussi le match (nom « Équipe 104 FC »)");
        String line = bfoot.bot.ReportFormatter.analysisComparison("Duo", fa, fb);
        assertTrue(line.contains("Avis de la source Duo") && (line.contains("✅") || line.contains("⚠️")));
        System.out.println(line);
    }

    @Test
    void bilanParSource() throws Exception {
        Setup s = setup();
        CouponReport b = s.duo().weekly(7);
        PredictionLog log = new PredictionLog(s.dir().resolve("log"));
        int saved = log.record("duo", "semaine", b.sections().get(0).result().tickets(), Instant.now());
        assertTrue(saved > 0);
        PredictionLog.Summary sum = log.settle(Map.of("duo", s.duo().provider()), Instant.now());
        assertTrue(sum.byType().containsKey("duo:semaine"), sum.byType().keySet().toString());
        assertEquals(saved, sum.byType().get("duo:semaine").pending(), "matchs pas encore joués");
        assertTrue(bfoot.bot.ReportFormatter.summary(sum).contains("· Duo"));
    }
}
