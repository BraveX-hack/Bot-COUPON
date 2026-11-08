package bfoot.store;

import bfoot.coupon.Leg;
import bfoot.coupon.Ticket;
import bfoot.data.CachedSource;
import bfoot.data.Fixture;
import bfoot.data.FootballData;
import bfoot.data.ResponseCache;
import bfoot.model.Market;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PredictionLogTest {

    private static Leg leg(int eventId, Instant kickoff, Market market, double odds) {
        Fixture f = new Fixture(eventId, kickoff, 17, "Premier League", 1, "A", 2, "B");
        return new Leg(f, market, odds, 0.6, 0.55, 0.575);
    }

    @Test
    void enregistreUneFoisEtCalculeLeBilan() throws Exception {
        Path dir = Files.createTempDirectory("bfoot-log");
        PredictionLog log = new PredictionLog(dir);
        Instant kickoff = Instant.parse("2026-10-10T18:00:00Z");
        Ticket won = new Ticket(List.of(leg(1, kickoff, Market.OVER_2_5, 2.0), leg(2, kickoff, Market.HOME_WIN, 2.5)));
        Ticket lost = new Ticket(List.of(leg(3, kickoff, Market.BTTS_YES, 1.8), leg(1, kickoff, Market.DC_1X, 1.4)));
        Ticket pending = new Ticket(List.of(leg(4, Instant.parse("2026-10-30T18:00:00Z"), Market.DRAW, 3.2)));

        assertEquals(2, log.record("sofascore", "semaine", List.of(won, lost), Instant.now()));
        assertEquals(1, log.record("sofascore", "mois-securite", List.of(pending), Instant.now()));
        assertEquals(0, log.record("sofascore", "semaine", List.of(won), Instant.now()), "un coupon identique n'est pas enregistré deux fois");

        Map<String, String> events = Map.of(
            "/event/1", "{\"event\":{\"status\":{\"type\":\"finished\"},\"homeScore\":{\"normaltime\":2},\"awayScore\":{\"normaltime\":1}}}",
            "/event/2", "{\"event\":{\"status\":{\"type\":\"finished\"},\"homeScore\":{\"normaltime\":1},\"awayScore\":{\"normaltime\":0}}}",
            "/event/3", "{\"event\":{\"status\":{\"type\":\"finished\"},\"homeScore\":{\"normaltime\":0},\"awayScore\":{\"normaltime\":0}}}",
            // cote de clôture du "plus de 2,5 buts" du match 1 : 1,80 (on l'avait jouée à 2,00 → CLV +11 %)
            "/event/1/odds/1/all", "{\"markets\":[{\"marketName\":\"Match goals\",\"choiceGroup\":\"2.5\",\"choices\":[{\"name\":\"Over\",\"fractionalValue\":\"4/5\"}]}]}");
        AtomicInteger calls = new AtomicInteger();
        FootballData data = new FootballData(new CachedSource(new ResponseCache(dir.resolve("cache")), path -> {
            calls.incrementAndGet();
            String body = events.get(path);
            return CachedSource.response(body == null ? 404 : 200, body == null ? "{}" : body);
        }));

        PredictionLog.Summary s = log.settle(data, Instant.parse("2026-10-19T12:00:00Z"));
        assertEquals(3, s.coupons());
        PredictionLog.TypeSummary week = s.byType().get("semaine");
        assertEquals(1, week.won());
        assertEquals(1, week.lost());
        assertEquals(2.0, week.staked(), 1e-9);
        assertEquals(5.0, week.returned(), 1e-9);  // 2,0 × 2,5
        assertEquals(1.5, week.roi(), 1e-9);
        assertEquals(1, s.byType().get("mois-securite").pending());
        assertEquals(1, s.clvLegs());
        assertEquals(2.0 / 1.8 - 1, s.averageClv(), 1e-9);
        // 3 résultats + 3 cotes de clôture, une seule fois
        assertEquals(6, calls.get());

        log.settle(data, Instant.parse("2026-10-19T12:00:00Z").plus(Duration.ofHours(1)));
        assertEquals(6, calls.get(), "un résultat déjà connu n'est jamais redemandé");
    }
}
