package bfoot.engine;

import bfoot.TestWorld;
import bfoot.ai.AiAdjustment;
import bfoot.ai.AiPanel;
import bfoot.ai.AiReviewer;
import bfoot.ai.MatchContext;
import bfoot.coupon.Leg;
import bfoot.coupon.Ticket;
import bfoot.data.CachedSource;
import bfoot.data.Fixture;
import bfoot.data.FootballData;
import bfoot.data.ResponseCache;
import bfoot.store.ClubList;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simulations complètes (semaine et grands matchs du mois) sur un faux Sofascore. */
class CouponEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Africa/Porto-Novo");

    private static AiReviewer neutralAi(AtomicInteger calls, List<MatchContext> seen) {
        return new AiReviewer() {
            public String name() { return "Fake"; }
            public boolean configured() { return true; }
            public long minIntervalMs() { return 0; }
            public String complete(String prompt) { throw new UnsupportedOperationException(); }
            @Override public List<AiAdjustment> review(List<MatchContext> batch) {
                calls.incrementAndGet();
                seen.addAll(batch);
                return batch.stream().map(m -> AiAdjustment.neutral(m.eventId())).toList();
            }
        };
    }

    private record Setup(TestWorld world, CouponEngine engine, Path dir, AtomicInteger aiCalls, List<MatchContext> seen) {}

    private static Setup setup() throws Exception {
        TestWorld world = new TestWorld(NOW);
        Path dir = Files.createTempDirectory("bfoot-e2e");
        ResponseCache cache = new ResponseCache(dir);
        FootballData data = new FootballData(new CachedSource(cache, world));
        AtomicInteger aiCalls = new AtomicInteger();
        List<MatchContext> seen = new ArrayList<>();
        AiPanel panel = new AiPanel(List.of(neutralAi(aiCalls, seen)), cache, 6);
        return new Setup(world, new CouponEngine(data, panel, Clock.fixed(NOW, ZoneOffset.UTC), ZONE), dir, aiCalls, seen);
    }

    @Test
    void semaine_troisCouponsCote10EtCacheAuDeuxiemePassage() throws Exception {
        Setup s = setup();
        CouponReport report = s.engine().weekly(7);

        assertEquals(12, report.fixturesFound(), "6 matchs à venir par championnat sur 7 jours, le match en cours exclu");
        assertEquals(12, report.withOdds());
        assertEquals(2, report.competitions());
        List<Ticket> tickets = report.sections().get(0).result().tickets();
        assertEquals(3, tickets.size(), "3 coupons demandés");
        for (Ticket t : tickets) {
            assertTrue(t.odds() >= 9.5 && t.odds() <= 11.0, "cote du coupon : " + t.odds());
            Set<Integer> inTicket = new HashSet<>();
            for (Leg l : t.legs()) {
                assertTrue(inTicket.add(l.fixture().eventId()), "une seule sélection par match");
                assertTrue(l.finalProb() >= 0.45, "sélections fiables uniquement");
            }
            for (Ticket other : tickets) {
                if (other != t) assertTrue(t.sharedMatches(other) <= 1, "au plus un match commun entre deux coupons");
            }
        }
        assertTrue(report.filteredLegs() > 0, "des cotes qui dérivent doivent être écartées");
        assertTrue(!s.seen().isEmpty() && s.seen().size() <= 30, "seuls les matchs présélectionnés sont relus par l'IA");
        assertTrue(s.aiCalls().get() <= 5, "matchs envoyés par lots : " + s.aiCalls().get() + " appels");
        assertTrue(s.seen().get(0).homeForm().matches("([VND]\\d+-\\d+ ?){5}"), "forme : " + s.seen().get(0).homeForm());

        // 2 ligues × (saisons + prochains matchs) + 24 équipes + 12 cotes + 23 "saisons" en 404 = 63
        long firstRun = report.requests().network();
        assertTrue(firstRun <= 90, "requêtes au premier passage : " + firstRun);

        int before = s.world().calls.get();
        CouponReport again = s.engine().weekly(7);
        assertEquals(0, s.world().calls.get() - before, "deuxième passage le même jour : tout vient du cache");
        assertEquals(3, again.sections().get(0).result().tickets().size());
        System.out.println(bfoot.bot.ReportFormatter.report(report, ZONE));
    }

    @Test
    void grandsMatchs_couponSecuriteEtCoupon120() throws Exception {
        Setup s = setup();
        ClubList clubs = new ClubList(s.dir().resolve("data"));
        clubs.replace(List.of("equipe 210")); // sans accent ni majuscule : doit quand même correspondre
        CouponReport report = s.engine().bigMatches(30, clubs);

        assertEquals(48, report.fixturesFound(), "4 journées × 6 matchs × 2 championnats");
        List<Fixture> big = report.bigMatches();
        assertFalse(big.isEmpty());
        assertTrue(big.size() < report.fixturesFound(), "seuls les grands matchs sont retenus");
        assertTrue(big.stream().anyMatch(f -> f.homeName().equals("Équipe 210") || f.awayName().equals("Équipe 210")),
            "un club de la liste /clubs est toujours inclus");
        Set<Integer> bigIds = new HashSet<>();
        big.forEach(f -> bigIds.add(f.eventId()));

        CouponReport.Section safe = report.sections().get(0);
        assertEquals("mois-securite", safe.key());
        assertEquals(1, safe.result().tickets().size());
        Ticket t = safe.result().tickets().get(0);
        assertTrue(t.probability() >= 0.50, "réussite estimée du coupon sécurité : " + t.probability());
        assertTrue(t.odds() >= 1.55 && t.odds() <= 2.40, "cote du coupon sécurité : " + t.odds());
        assertTrue(t.legs().size() <= 3);

        CouponReport.Section longShot = report.sections().get(1);
        assertEquals("mois-gros", longShot.key());
        assertEquals(1, longShot.result().tickets().size());
        Ticket g = longShot.result().tickets().get(0);
        assertTrue(g.odds() >= 110 && g.odds() <= 135, "cote du gros coupon : " + g.odds());
        assertTrue(g.legs().size() >= 5 && g.legs().size() <= 10);
        assertTrue(g.probability() < 0.05, "une cote ~120 ne peut pas avoir une grosse probabilité : " + g.probability());

        for (CouponReport.Section sec : report.sections()) {
            for (Ticket ticket : sec.result().tickets()) {
                for (Leg l : ticket.legs()) assertTrue(bigIds.contains(l.fixture().eventId()), "uniquement des grands matchs");
            }
        }
        System.out.println(bfoot.bot.ReportFormatter.report(report, ZONE));
    }

    @Test
    void pronostics_unPronosticClairParMatchAvecLeContexteJoueurs() throws Exception {
        Setup s = setup();
        PredictionReport r = s.engine().predictions(3, new ClubList(s.dir().resolve("data")));
        assertFalse(r.cards().isEmpty());
        for (int i = 1; i < r.cards().size(); i++) {
            assertTrue(!r.cards().get(i).fixture().kickoff().isBefore(r.cards().get(i - 1).fixture().kickoff()), "ordre chronologique");
        }
        MatchCard first = r.cards().get(0);
        assertTrue(first.details().hasAnything(), "absents / notes / classement chargés");
        assertTrue(first.prediction().mainProbability() > 0.4);
        assertTrue(s.seen().stream().anyMatch(m -> m.context().contains("absents dom: Buteur")),
            "les absents doivent être transmis aux IA");
        System.out.println(bfoot.bot.ReportFormatter.predictions(r, ZONE));
    }

    @Test
    void analyse_ficheCompleteDuProchainMatch() throws Exception {
        Setup s = setup();
        PredictionReport r = s.engine().analyse("équipe 104", 30);
        assertEquals(1, r.cards().size());
        MatchCard c = r.cards().get(0);
        assertTrue(c.fixture().homeName().equals("Équipe 104") || c.fixture().awayName().equals("Équipe 104"));
                assertTrue(c.details().headToHead() != null, "face-à-face chargé pour la fiche");
        assertTrue(c.eloHome() != c.eloAway(), "puissance Elo calculée");
        assertTrue(c.aiVotes() > 0, "avis des IA intégré");
        System.out.println(bfoot.bot.ReportFormatter.analysis(r, ZONE));

        PredictionReport none = s.engine().analyse("Club Inexistant", 30);
        assertTrue(none.cards().isEmpty());
        assertTrue(bfoot.bot.ReportFormatter.analysis(none, ZONE).contains("Aucun match trouvé"));
    }
}
