package bfoot.coupon;

import bfoot.data.Fixture;
import bfoot.model.Market;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouponOptimizerTest {

    private static List<Leg> semaine(int matches, long seed) {
        Random rnd = new Random(seed);
        List<Leg> legs = new ArrayList<>();
        Market[] markets = {Market.HOME_WIN, Market.OVER_2_5, Market.BTTS_YES, Market.DC_1X, Market.UNDER_3_5, Market.OVER_1_5};
        for (int i = 0; i < matches; i++) {
            Fixture f = new Fixture(i, Instant.parse("2026-10-20T18:00:00Z").plusSeconds(3600L * i), 17, "Premier League",
                100 + i, "Dom" + i, 200 + i, "Ext" + i);
            for (Market m : markets) {
                double odds = 1.05 + rnd.nextDouble() * 2.5;
                double market = 1 / odds / 1.05;
                double model = Math.min(0.97, market * (0.9 + rnd.nextDouble() * 0.25));
                legs.add(new Leg(f, m, odds, model, market, (model + market) / 2));
            }
        }
        return legs;
    }

    @Test
    void troisCouponsDisjointsDansLaFourchette() {
        CouponOptimizer.Config cfg = CouponOptimizer.Config.forTarget(10, 3);
        CouponOptimizer.Result r = new CouponOptimizer(cfg).build(semaine(80, 1));
        assertEquals(3, r.tickets().size());
        Set<Integer> used = new HashSet<>();
        for (Ticket t : r.tickets()) {
            assertTrue(t.odds() >= cfg.minTotalOdds() && t.odds() <= cfg.maxTotalOdds(), "cote " + t.odds());
            assertTrue(t.legs().size() >= 3 && t.legs().size() <= 6);
            Set<Integer> inTicket = new HashSet<>();
            for (Leg l : t.legs()) {
                assertTrue(inTicket.add(l.fixture().eventId()), "une seule sélection par match");
                assertTrue(l.odds() >= 1.30 && l.odds() <= 2.60);
                assertTrue(used.add(l.fixture().eventId()), "les coupons ne doivent pas partager de match");
            }
        }
        assertFalse(r.relaxed());
    }

    @Test
    void lesCouponsNeSontPasPerdantsQuandIlYADeLaValue() {
        CouponOptimizer.Result r = new CouponOptimizer(CouponOptimizer.Config.forTarget(10, 3)).build(semaine(40, 2));
        assertFalse(r.relaxed());
        for (Ticket t : r.tickets()) assertTrue(t.expectedValue() >= 1.0, "espérance " + t.expectedValue());
    }

    @Test
    void pasAssezDeMatchs() {
        CouponOptimizer.Result r = new CouponOptimizer(CouponOptimizer.Config.forTarget(10, 3)).build(semaine(2, 3));
        assertTrue(r.tickets().isEmpty(), "2 matchs ne suffisent pas pour une cote 10 avec des sélections ≤ 2,60");
    }

    @Test
    void couponSecuriteAuMoinsCinquantePourcent() {
        CouponOptimizer.Config cfg = CouponOptimizer.Config.safe(0.50, 1);
        CouponOptimizer.Result r = new CouponOptimizer(cfg).build(semaine(40, 4));
        assertEquals(1, r.tickets().size());
        Ticket t = r.tickets().get(0);
        assertTrue(t.probability() >= 0.50, "réussite " + t.probability());
        assertTrue(t.odds() >= 1.55 && t.odds() <= 2.40, "cote " + t.odds());
        assertTrue(t.legs().size() >= 1 && t.legs().size() <= 3);
    }

    @Test
    void grosCouponCote120() {
        CouponOptimizer.Config cfg = CouponOptimizer.Config.longShot(120, 1);
        CouponOptimizer.Result r = new CouponOptimizer(cfg).build(semaine(25, 5));
        assertEquals(1, r.tickets().size());
        Ticket t = r.tickets().get(0);
        assertTrue(t.odds() >= 110 && t.odds() <= 135, "cote " + t.odds());
        assertTrue(t.legs().size() >= 5 && t.legs().size() <= 10);
        assertTrue(t.probability() < 0.05, "probabilité honnête attendue, obtenu " + t.probability());
    }

    @Test
    void objectifProbabiliteChoisitLeCouponLePlusSur() {
        CouponOptimizer.Result r = new CouponOptimizer(CouponOptimizer.Config.forTarget(10, 3)).build(semaine(40, 6));
        assertTrue(r.tickets().get(0).probability() >= r.tickets().get(1).probability());
    }
}
