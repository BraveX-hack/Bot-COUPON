package bfoot.ai;

import bfoot.data.ResponseCache;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPanelTest {

    static MatchContext ctx(int id) {
        return new MatchContext(id, "mar. 21/10 21:00", "Premier League", "A" + id, "B" + id,
            1.5, 1.1, 0.48, 0.26, 0.26, 0.52, 0.53, "V2-1 N0-0", "D0-1 V3-0", "absents dom: Saka (attaquant, blessure)");
    }

    /** Fausse IA : renvoie un texte fixe et compte ses appels. */
    static AiReviewer fake(String name, AtomicInteger calls, java.util.function.Function<List<MatchContext>, String> answer) {
        return new AiReviewer() {
            public String name() { return name; }
            public boolean configured() { return true; }
            public long minIntervalMs() { return 0; }
            public String complete(String prompt) { throw new UnsupportedOperationException(); }
            @Override public List<AiAdjustment> review(List<MatchContext> batch) {
                calls.incrementAndGet();
                return AiPrompt.parse(answer.apply(batch), batch);
            }
        };
    }

    static String json(List<MatchContext> batch, double home, boolean avoid) {
        StringBuilder sb = new StringBuilder("```json\n{\"matches\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(batch.get(i).eventId()).append(",\"homeFactor\":").append(home)
                .append(",\"awayFactor\":1.0,\"avoid\":").append(avoid).append(",\"reason\":\"test\"}");
        }
        return sb.append("]}\n```").toString();
    }

    @Test
    void parLotsAvecCacheEtConsensus() throws Exception {
        List<MatchContext> matches = new ArrayList<>();
        for (int i = 1; i <= 10; i++) matches.add(ctx(i));
        AtomicInteger a = new AtomicInteger(), b = new AtomicInteger(), c = new AtomicInteger();
        ResponseCache cache = new ResponseCache(Files.createTempDirectory("bfoot-ai"));
        AiPanel panel = new AiPanel(List.of(
            fake("A", a, batch -> json(batch, 1.10, true)),
            fake("B", b, batch -> json(batch, 0.90, false)),
            fake("C", c, batch -> json(batch, 2.00, true))   // 2.00 doit être borné à 1.15
        ), cache, 4);

        AiPanel.Result r = panel.review(matches);
        assertEquals(3, a.get(), "10 matchs par lots de 4 = 3 appels");
        AiPanel.Consensus first = r.consensus().get(1);
        assertEquals((1.10 + 0.90 + 1.15) / 3, first.homeFactor(), 1e-9);
        assertTrue(first.avoid(), "2 IA sur 3 disent d'éviter");

        panel.review(matches);
        assertEquals(3, a.get(), "deuxième passage : tout vient du cache");
    }

    @Test
    void uneIaQuiEchoueNeBloquePasLesAutres() throws Exception {
        AtomicInteger ok = new AtomicInteger(), ko = new AtomicInteger();
        AiPanel panel = new AiPanel(List.of(
            fake("OK", ok, batch -> json(batch, 1.05, false)),
            fake("KO", ko, batch -> "désolé, je ne peux pas")
        ), new ResponseCache(Files.createTempDirectory("bfoot-ai")), 2);
        List<MatchContext> matches = List.of(ctx(1), ctx(2), ctx(3), ctx(4), ctx(5), ctx(6));
        AiPanel.Result r = panel.review(matches);
        assertEquals(2, ko.get(), "arrêt après 2 échecs consécutifs");
        assertEquals(1.05, r.consensus().get(6).homeFactor(), 1e-9);
        assertFalse(r.consensus().get(6).avoid());
        AiPanel.ProviderStatus koStatus = r.providers().stream().filter(p -> p.name().equals("KO")).findFirst().orElseThrow();
        assertFalse(koStatus.ok());
    }

    @Test
    void matchOublieParLIaRecoitUnAvisNeutre() {
        List<MatchContext> batch = List.of(ctx(1), ctx(2));
        List<AiAdjustment> adj = AiPrompt.parse("{\"matches\":[{\"id\":2,\"homeFactor\":0.5,\"awayFactor\":1.1}]}", batch);
        assertEquals(1.0, adj.get(0).homeFactor(), 1e-9);
        assertEquals(0.85, adj.get(1).homeFactor(), 1e-9);
        assertThrows(IllegalStateException.class, () -> AiPrompt.parse("pas de json", batch));
        assertTrue(AiPrompt.build(batch).contains("id=1"));
        assertTrue(AiPrompt.build(batch).contains("Saka (attaquant, blessure)"), "le contexte joueurs doit être transmis");
    }
}
