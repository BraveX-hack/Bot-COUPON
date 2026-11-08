package bfoot.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Construction du prompt "lot de matchs" et lecture tolérante de la réponse JSON. */
public final class AiPrompt {

    private AiPrompt() {}

    public static String build(List<MatchContext> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            Tu es analyste football. Un modèle statistique a estimé les buts attendus (λ) de chaque match \
            à partir des résultats récents. Ton rôle : signaler ce que ces statistiques ne voient pas \
            (enjeu ou absence d'enjeu, rotation probable avant un autre match, changement d'entraîneur, \
            crise, absences importantes connues, match de coupe contre une équipe de division inférieure...).

            Réponds UNIQUEMENT avec un objet JSON, sans texte autour, de la forme :
            {"matches":[{"id":<id>,"homeFactor":<nombre>,"awayFactor":<nombre>,"avoid":<true|false>,"reason":"<12 mots max>"}]}

            Règles :
            - homeFactor multiplie les buts attendus de l'équipe à domicile, awayFactor ceux de l'équipe à l'extérieur.
            - Valeurs entre 0.85 et 1.15. Mets 1.0 si tu n'as pas d'information fiable : c'est la réponse normale.
            - avoid=true seulement si le match est trop imprévisible pour parier (enjeu nul, équipe B probable, etc.).
            - Tes connaissances peuvent être datées : reste prudent, n'invente pas d'absences.
            - Un objet par match, avec le même id que ci-dessous.

            Utilise surtout le contexte fourni (absents, note moyenne des joueurs, classement) : un absent
            important (buteur, gardien titulaire, meneur) justifie un facteur de 0.90 à 0.95 sur son équipe.

            Matchs :
            """);
        for (MatchContext m : batch) {
            sb.append(String.format(Locale.ROOT,
                "- id=%d | %s | %s | %s (dom.) vs %s (ext.) | λ dom=%.2f ext=%.2f | P(1/N/2)=%.0f%%/%.0f%%/%.0f%% | +2.5 buts=%.0f%% | BTTS=%.0f%% | forme dom: %s | forme ext: %s%s%n",
                m.eventId(), m.kickoff(), m.competition(), m.home(), m.away(), m.lambdaHome(), m.lambdaAway(),
                m.pHome() * 100, m.pDraw() * 100, m.pAway() * 100, m.pOver25() * 100, m.pBtts() * 100,
                m.homeForm(), m.awayForm(),
                m.context() == null || m.context().isBlank() ? "" : " | contexte: " + m.context()));
        }
        return sb.toString();
    }

    /**
     * Lit la réponse. Les matchs absents ou illisibles reçoivent un avis
     * neutre (facteurs 1.0) : une IA bavarde ne fait jamais échouer l'analyse.
     */
    public static List<AiAdjustment> parse(String raw, List<MatchContext> batch) {
        Map<Integer, AiAdjustment> byId = new HashMap<>();
        String cleaned = raw == null ? "" : raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Pas de JSON dans la réponse : " + abbreviate(cleaned));
        }
        JSONObject json = new JSONObject(cleaned.substring(start, end + 1));
        JSONArray matches = json.optJSONArray("matches");
        if (matches == null) {
            throw new IllegalStateException("Clé \"matches\" absente : " + abbreviate(cleaned));
        }
        for (int i = 0; i < matches.length(); i++) {
            JSONObject o = matches.optJSONObject(i);
            if (o == null || !o.has("id")) continue;
            try {
                AiAdjustment adj = AiAdjustment.fromJson(o);
                byId.put(adj.eventId(), adj);
            } catch (Exception ignored) {
                // entrée illisible : avis neutre
            }
        }
        List<AiAdjustment> out = new ArrayList<>();
        for (MatchContext m : batch) {
            out.add(byId.getOrDefault(m.eventId(), AiAdjustment.neutral(m.eventId())));
        }
        return out;
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
