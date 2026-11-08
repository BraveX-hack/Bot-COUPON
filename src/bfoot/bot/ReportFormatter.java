package bfoot.bot;

import bfoot.ai.AiPanel;
import bfoot.coupon.Leg;
import bfoot.coupon.Ticket;
import bfoot.data.Fixture;
import bfoot.data.MatchDetails;
import bfoot.engine.CouponReport;
import bfoot.engine.MatchCard;
import bfoot.engine.Prediction;
import bfoot.engine.PredictionReport;
import bfoot.engine.SourceComparison;
import bfoot.model.Market;
import bfoot.store.PredictionLog;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mise en forme des messages Telegram en HTML (plus robuste que l'ancien
 * Markdown : un "_" dans un nom d'équipe ou un message d'erreur ne fait plus
 * échouer l'envoi).
 */
public final class ReportFormatter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM", Locale.FRENCH);
    private static final DateTimeFormatter KICKOFF = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.FRENCH);

    private static final Map<String, String> TYPE_LABELS = Map.of(
        "semaine", "Semaine (cote ~10)",
        "mois-securite", "Grands matchs — sécurité",
        "mois-gros", "Grands matchs — grosse cote");

    private ReportFormatter() {}

    public static String report(CouponReport r, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        String period = " — du " + DAY.format(r.from().atZone(zone)) + " au " + DAY.format(r.to().atZone(zone));
        sb.append(sourceBadge(r.source())).append('\n');
        if (r.kind() == CouponReport.Kind.WEEK) {
            sb.append("🗓️ <b>Coupons de la semaine").append(period).append("</b>\n");
            sb.append("<i>").append(r.withOdds()).append(" matchs avec cotes analysés sur ")
                .append(r.fixturesFound()).append(" programmés · ").append(r.competitions()).append(" compétitions</i>\n");
        } else {
            sb.append("⭐ <b>Grands matchs du mois").append(period).append("</b>\n");
            sb.append("<i>").append(r.bigMatches().size()).append(" grands matchs retenus (Ligue des champions + top 5), dont ")
                .append(r.withOdds()).append(" avec des cotes publiées</i>\n");
            appendBigMatches(sb, r.bigMatches(), zone);
        }

        for (CouponReport.Section s : r.sections()) {
            appendSection(sb, s, zone);
        }

        sb.append("\n🔎 Fiabilité : ").append(r.filteredLegs()).append(" sélection(s) écartée(s) (cote qui dérive ou désaccord modèle/Elo)");
        if (r.aiAvoided() > 0) sb.append(", ").append(r.aiAvoided()).append(" match(s) écarté(s) par les IA");
        sb.append('\n');
        sb.append("📡 ").append(esc(r.source())).append(" : ").append(r.requests().network()).append(" requêtes réseau, ")
            .append(r.requests().cacheHits()).append(" depuis le cache");
        if (r.quota() != null) sb.append(" · ").append(esc(r.quota()));
        sb.append('\n');
        sb.append("🤖 IA (").append(r.aiReviewed()).append(" matchs relus) : ").append(aiStatus(r.aiProviders())).append('\n');

        if (!r.warnings().isEmpty()) {
            sb.append("\n<i>Remarques :</i>\n");
            r.warnings().stream().limit(6).forEach(w -> sb.append("• ").append(esc(w)).append('\n'));
            if (r.warnings().size() > 6) sb.append("• … et ").append(r.warnings().size() - 6).append(" autre(s)\n");
        }
        sb.append("\n<i>Probabilités estimées par le modèle : aucune garantie de gain. Joue de façon responsable (18+).</i>");
        return sb.toString();
    }

    private static void appendBigMatches(StringBuilder sb, List<Fixture> matches, ZoneId zone) {
        if (matches.isEmpty()) return;
        sb.append("<i>Affiches : ");
        matches.stream().limit(8).forEach(f -> sb.append(esc(f.label())).append(" (")
            .append(DAY.format(f.kickoff().atZone(zone))).append("), "));
        sb.setLength(sb.length() - 2);
        if (matches.size() > 8) sb.append("…");
        sb.append("</i>\n");
    }

    private static void appendSection(StringBuilder sb, CouponReport.Section s, ZoneId zone) {
        List<Ticket> tickets = s.result().tickets();
        String title = switch (s.key()) {
            case "mois-securite" -> "🛡️ <b>Coupon sécurité</b> (réussite estimée ≥ " + pct(s.config().minTicketProbability()) + ")";
            case "mois-gros" -> "🚀 <b>Coupon cote ~" + fmt0(s.config().targetOdds()) + "</b>";
            default -> "🎟️ <b>" + tickets.size() + " coupon(s) cote ~" + fmt0(s.config().targetOdds()) + "</b>";
        };
        sb.append('\n').append(title).append('\n');
        if (tickets.isEmpty()) {
            sb.append("Aucune combinaison ne respecte les critères pour l'instant (vraies cotes, sélections fiables). "
                + "Réessaie plus tard : les cotes sont publiées au fil des jours.\n");
            return;
        }
        int n = 1;
        for (Ticket t : tickets) {
            if (tickets.size() > 1) sb.append("<b>Coupon ").append(n++).append("</b> — ");
            sb.append("cote <b>").append(fmt2(t.odds())).append("</b> · réussite estimée <b>")
                .append(chance(t.probability())).append("</b> · espérance (modèle) ")
                .append(signedPct(t.expectedValue() - 1)).append('\n');
            int i = 1;
            for (Leg l : t.legs()) {
                sb.append(i++).append(". ").append(esc(KICKOFF.format(l.fixture().kickoff().atZone(zone))))
                    .append(" · ").append(esc(l.fixture().tournamentName())).append('\n')
                    .append("   ").append(esc(l.fixture().label())).append(" → <b>").append(esc(l.label())).append("</b>")
                    .append(" @").append(fmt2(l.odds()))
                    .append(" (").append(pct(l.finalProb())).append(")\n");
            }
        }
        if (s.key().equals("mois-gros")) {
            sb.append("<i>Une cote de ").append(fmt0(s.config().targetOdds()))
                .append(" ne peut pas passer souvent : à jouer avec une toute petite mise.</i>\n");
        }
        if (s.result().relaxed()) {
            sb.append("<i>ℹ️ Peu de « value » : certaines sélections sont légèrement sous l'équilibre.</i>\n");
        }
    }

    private static String aiStatus(List<AiPanel.ProviderStatus> providers) {
        if (providers.isEmpty()) return "aucune";
        StringBuilder sb = new StringBuilder();
        for (AiPanel.ProviderStatus p : providers) {
            if (!sb.isEmpty()) sb.append(" · ");
            String icon = !p.configured() ? "⛔" : p.error() == null ? "✅" : p.reviewed() + p.fromCache() > 0 ? "⚠️" : "❌";
            sb.append(esc(p.name())).append(' ').append(icon);
        }
        return sb.toString();
    }

    // --- /pronostics ---

    public static String predictions(PredictionReport r, ZoneId zone) {
        StringBuilder sb = new StringBuilder(sourceBadge(r.source())).append("\n🎯 <b>Pronostics des grands matchs — du ")
            .append(DAY.format(r.from().atZone(zone))).append(" au ").append(DAY.format(r.to().atZone(zone))).append("</b>\n");
        sb.append("<i>Ligue des champions + top 5 · force des équipes, forme, absents, notes des joueurs, classement, face-à-face, cotes et avis des IA</i>\n");
        if (r.cards().isEmpty()) {
            sb.append("\nAucun match à analyser sur cette période.\n");
        }
        for (MatchCard c : r.cards()) {
            Prediction p = c.prediction();
            String home = c.fixture().homeName(), away = c.fixture().awayName();
            sb.append('\n').append(esc(KICKOFF.format(c.fixture().kickoff().atZone(zone)))).append(" · ")
                .append(esc(c.fixture().tournamentName())).append('\n')
                .append("<b>").append(esc(c.fixture().label())).append("</b>\n")
                .append("→ <b>").append(esc(p.main().label(home, away))).append("</b> (").append(pct(p.mainProbability()))
                .append(") · score probable ").append(p.scoreHome()).append('-').append(p.scoreAway());
            if (p.goals() != null) sb.append(" · ").append(esc(p.goals().label(home, away))).append(" (").append(pct(p.goalsProbability())).append(')');
            sb.append(" · confiance ").append(p.confidence().label);
            int absents = c.details().homeMissing().size() + c.details().awayMissing().size();
            if (absents > 0) sb.append(" · 🚑 ").append(absents).append(" absent(s)");
            if (c.avoidedByAi()) sb.append(" · ⚠️ match jugé imprévisible par les IA");
            sb.append('\n');
        }
        appendFooter(sb, r);
        sb.append("\n<i>Pour la fiche complète d'un match : /analyse nom_de_l_équipe</i>");
        return sb.toString();
    }

    // --- /analyse ---

    public static String analysis(PredictionReport r, ZoneId zone) {
        if (r.cards().isEmpty()) {
            StringBuilder sb = new StringBuilder("🔍 Aucun match trouvé pour « ").append(esc(r.query()))
                .append(" » dans les compétitions suivies sur les 30 prochains jours.\n")
                .append("Écris le nom comme sur Sofascore (ex : <code>/analyse Real Madrid</code>, <code>/analyse Paris Saint-Germain</code>).");
            r.warnings().stream().limit(3).forEach(w -> sb.append("\n• ").append(esc(w)));
            return sb.toString();
        }
        MatchCard c = r.cards().get(0);
        Prediction p = c.prediction();
        MatchDetails d = c.details();
        String home = c.fixture().homeName(), away = c.fixture().awayName();
        StringBuilder sb = new StringBuilder(sourceBadge(r.source())).append('\n');
        sb.append("🔍 <b>").append(esc(c.fixture().label())).append("</b>\n")
            .append(esc(c.fixture().tournamentName())).append(" · ")
            .append(esc(KICKOFF.format(c.fixture().kickoff().atZone(zone)))).append('\n');

        sb.append("\n💪 <b>Puissance</b>\n")
            .append("Elo : ").append(fmt0(c.eloHome())).append(" vs ").append(fmt0(c.eloAway())).append('\n')
            .append("Attaque : ").append(fmt2(c.attackHome())).append(" vs ").append(fmt2(c.attackAway()))
            .append(" · Défense : ").append(fmt2(c.defenseHome())).append(" vs ").append(fmt2(c.defenseAway()))
            .append(" <i>(1,00 = moyenne ; défense sous 1 = solide)</i>\n")
            .append("Buts attendus : ").append(fmt2(c.lambdaHome())).append(" – ").append(fmt2(c.lambdaAway())).append('\n');
        if (d.homePosition() > 0 && d.awayPosition() > 0) {
            sb.append("Classement : ").append(d.homePosition()).append("e vs ").append(d.awayPosition()).append("e\n");
        }

        sb.append("\n📈 <b>Forme</b> (5 derniers, du plus récent)\n")
            .append(esc(home)).append(" : ").append(esc(c.homeForm())).append('\n')
            .append(esc(away)).append(" : ").append(esc(c.awayForm())).append('\n');
        if (d.headToHead() != null && d.headToHead().total() > 0) {
            sb.append("Face-à-face : ").append(d.headToHead().homeWins()).append(" victoire(s) ").append(esc(home)).append(", ")
                .append(d.headToHead().draws()).append(" nul(s), ").append(d.headToHead().awayWins()).append(" victoire(s) ").append(esc(away)).append('\n');
        }

        sb.append("\n🧑‍🤝‍🧑 <b>Joueurs</b>\n");
        if (!Double.isNaN(d.homeAvgRating()) && !Double.isNaN(d.awayAvgRating())) {
            sb.append("Note moyenne des joueurs (Sofascore) : ").append(fmt2(d.homeAvgRating())).append(" vs ").append(fmt2(d.awayAvgRating())).append('\n');
        }
        if (d.lineupConfirmed() != null) {
            sb.append("Compositions : ").append(d.lineupConfirmed() ? "confirmées ✅" : "probables (pas encore officielles)");
            if (!d.homeFormation().isBlank() || !d.awayFormation().isBlank()) {
                sb.append(" · ").append(esc(d.homeFormation())).append(" vs ").append(esc(d.awayFormation()));
            }
            sb.append('\n');
        } else {
            sb.append("Compositions : pas encore publiées (relance /analyse ~1 h avant le match)\n");
        }
        sb.append("🚑 ").append(esc(home)).append(" : ").append(esc(absents(d.homeMissing()))).append('\n')
            .append("🚑 ").append(esc(away)).append(" : ").append(esc(absents(d.awayMissing()))).append('\n');

        if (c.aiVotes() > 0) {
            sb.append("\n🤖 <b>Avis des IA</b> (").append(c.aiVotes()).append(" IA consultée(s))\n");
            sb.append("Ajustement buts attendus : ").append(signedPct(c.aiHomeFactor() - 1)).append(" / ")
                .append(signedPct(c.aiAwayFactor() - 1)).append('\n');
            c.aiReasons().stream().distinct().limit(3).forEach(reason -> sb.append("• ").append(esc(reason)).append('\n'));
            if (c.avoidedByAi()) sb.append("⚠️ La majorité des IA jugent ce match trop imprévisible pour parier.\n");
        }

        Map<Market, Double> pr = p.probabilities();
        sb.append("\n📊 <b>Probabilités</b> (modèle + cotes du marché)\n")
            .append("1 : ").append(pct(pr.get(Market.HOME_WIN))).append(" · N : ").append(pct(pr.get(Market.DRAW)))
            .append(" · 2 : ").append(pct(pr.get(Market.AWAY_WIN))).append('\n')
            .append("Plus de 2,5 buts : ").append(pct(pr.get(Market.OVER_2_5)))
            .append(" · Les deux marquent : ").append(pct(pr.get(Market.BTTS_YES))).append('\n');
        if (c.odds().containsKey(Market.HOME_WIN)) {
            sb.append("Cotes : ").append(fmt2(c.odds().get(Market.HOME_WIN))).append(" / ")
                .append(fmt2(c.odds().getOrDefault(Market.DRAW, 0.0))).append(" / ")
                .append(fmt2(c.odds().getOrDefault(Market.AWAY_WIN, 0.0))).append('\n');
        }

        sb.append("\n🎯 <b>Pronostic du bot</b>\n")
            .append("Résultat : <b>").append(esc(p.main().label(home, away))).append("</b> (").append(pct(p.mainProbability())).append(")\n")
            .append("Score le plus probable : <b>").append(p.scoreHome()).append('-').append(p.scoreAway()).append("</b> (")
            .append(pct(p.scoreProbability())).append(")\n");
        if (p.goals() != null) sb.append("Buts : ").append(esc(p.goals().label(home, away))).append(" (").append(pct(p.goalsProbability())).append(")\n");
        if (p.btts() != null) sb.append(esc(p.btts().label(home, away))).append(" (").append(pct(p.bttsProbability())).append(")\n");
        sb.append("Confiance : <b>").append(p.confidence().label).append("</b>\n");
        sb.append("<i>").append(confidenceExplanation(p)).append("</i>\n");

        appendFooter(sb, r);
        return sb.toString();
    }

    private static String confidenceExplanation(Prediction p) {
        long errors = Math.round((1 - p.mainProbability()) * 10);
        return "Ce pronostic n'est pas garanti : sur 10 matchs comme celui-ci, il se tromperait environ "
            + errors + " fois. Le score exact, lui, ne sort qu'environ " + Math.round(p.scoreProbability() * 100) + " fois sur 100.";
    }

    private static String absents(List<MatchDetails.Absence> list) {
        if (list.isEmpty()) return "aucun absent signalé";
        return list.stream().map(MatchDetails.Absence::describe).collect(java.util.stream.Collectors.joining(", "));
    }

    private static void appendFooter(StringBuilder sb, PredictionReport r) {
        sb.append("\n📡 ").append(esc(r.source())).append(" : ").append(r.requests().network()).append(" requêtes réseau, ")
            .append(r.requests().cacheHits()).append(" depuis le cache · 🤖 IA : ").append(aiStatus(r.aiProviders()));
        if (r.quota() != null) sb.append(" · ").append(esc(r.quota()));
        sb.append('\n');
        if (!r.warnings().isEmpty()) {
            r.warnings().stream().limit(4).forEach(w -> sb.append("• <i>").append(esc(w)).append("</i>\n"));
        }
        sb.append("<i>Aucun pronostic n'est garanti. Joue de façon responsable (18+).</i>");
    }

    public static String summary(PredictionLog.Summary s) {
        if (s.coupons() == 0) {
            return "📊 Aucun coupon enregistré pour l'instant. Lance /semaine ou /grandsmatchs d'abord.";
        }
        StringBuilder sb = new StringBuilder("📊 <b>Bilan des coupons</b> (mise de 1 € par coupon)\n");
        s.byType().forEach((type, t) -> {
            sb.append("\n<b>").append(esc(typeLabel(type))).append("</b>\n");
            sb.append(t.coupons()).append(" coupon(s) : ").append(t.won()).append(" gagnés, ")
                .append(t.lost()).append(" perdus, ").append(t.pending()).append(" en cours\n");
            if (t.staked() > 0) {
                sb.append("Misé ").append(fmt2(t.staked())).append(" € → rendu ").append(fmt2(t.returned()))
                    .append(" € · ROI <b>").append(signedPct(t.roi())).append("</b>\n");
                sb.append("Gagnés : ").append(t.won()).append(" (le modèle en prévoyait ")
                    .append(String.format(Locale.FRENCH, "%.1f", t.expectedWins())).append(")\n");
            }
        });
        if (s.legsWon() + s.legsLost() > 0) {
            sb.append("\nSélections gagnées : ").append(pct(s.legHitRate())).append(" (prévu ")
                .append(pct(s.expectedLegHitRate())).append(") sur ").append(s.legsWon() + s.legsLost()).append('\n');
        }
        if (s.clvLegs() > 0) {
            sb.append("Cote de clôture (CLV) : ").append(signedPct(s.averageClv())).append(" en moyenne sur ")
                .append(s.clvLegs()).append(" sélections ").append(s.averageClv() > 0
                    ? "✅ tu joues de meilleures cotes que la clôture" : "⚠️ la clôture est meilleure que tes cotes")
                .append('\n');
        }
        if (s.lookupErrors() > 0) {
            sb.append("<i>").append(s.lookupErrors()).append(" résultat(s) non récupéré(s), nouvel essai au prochain /bilan.</i>\n");
        }
        sb.append("<i>Il faut au moins 50 à 100 sélections réglées pour que ces chiffres veuillent dire quelque chose.</i>");
        return sb.toString();
    }

    // --- Sources et comparaison ---

    static String sourceBadge(String source) {
        return "Duo".equals(source)
            ? "🟢 <b>Source Duo</b> <i>(football-data.org + The Odds API)</i>"
            : "🔵 <b>Source " + esc(source) + "</b>";
    }

    static String typeLabel(String type) {
        if (type.startsWith("duo:")) return TYPE_LABELS.getOrDefault(type.substring(4), type.substring(4)) + " · Duo";
        return TYPE_LABELS.getOrDefault(type, type) + " · Sofascore";
    }

    /** Une source n'a pas pu tourner du tout. */
    public static String sourceFailure(String source, String error) {
        return sourceBadge(source) + "\n❌ Analyse impossible : " + esc(error);
    }

    public static String couponComparison(SourceComparison.CouponComparison c) {
        StringBuilder sb = new StringBuilder("⚖️ <b>Comparaison ").append(esc(c.sourceA())).append(" ↔ ").append(esc(c.sourceB())).append("</b>\n");
        sb.append("<i>Pour chaque sélection, la probabilité que lui donne l'autre source sur le même match. "
            + c.commonMatches() + " match(s) analysé(s) par les deux.</i>\n");
        if (c.tickets().isEmpty()) {
            return sb.append("\nAucun coupon à comparer.").toString();
        }
        for (SourceComparison.TicketCheck t : c.tickets()) {
            String other = t.source().equals(c.sourceA()) ? c.sourceB() : c.sourceA();
            sb.append("\n<b>").append(esc(t.source())).append(" — ").append(esc(sectionTitle(t.sectionKey()))).append(' ').append(t.number())
                .append("</b> (cote ").append(fmt2(t.ticket().odds())).append(") : ")
                .append(t.confirmed()).append('/').append(t.legs().size()).append(" confirmée(s) par ").append(esc(other)).append('\n');
            for (SourceComparison.LegCheck l : t.legs()) {
                String icon = switch (l.verdict()) { case AGREE -> "✅"; case DISAGREE -> "⚠️"; case UNKNOWN -> "➖"; };
                sb.append(icon).append(' ').append(esc(l.leg().fixture().label())).append(" · ").append(esc(l.leg().label()))
                    .append(" (").append(pct(l.leg().finalProb())).append(" → ").append(esc(other)).append(" : ")
                    .append(Double.isNaN(l.otherProbability()) ? "match non analysé" : pct(l.otherProbability())).append(")\n");
            }
        }
        c.mostConfirmed().ifPresent(t -> sb.append("\n👉 Le plus confirmé : <b>").append(esc(t.source())).append(" — ")
            .append(esc(sectionTitle(t.sectionKey()))).append(' ').append(t.number()).append("</b> (")
            .append(t.confirmed()).append('/').append(t.legs().size()).append(" sélections confirmées)\n"));
        sb.append("<i>✅ l'autre source est d'accord · ⚠️ elle juge la sélection nettement moins probable · ➖ match absent de l'autre source</i>");
        return sb.toString();
    }

    public static String predictionComparison(String sourceA, String sourceB, List<SourceComparison.PredictionPair> pairs, ZoneId zone) {
        StringBuilder sb = new StringBuilder("⚖️ <b>Pronostics comparés ").append(esc(sourceA)).append(" ↔ ").append(esc(sourceB)).append("</b>\n");
        long both = pairs.stream().filter(SourceComparison.PredictionPair::bothKnown).count();
        long agree = pairs.stream().filter(SourceComparison.PredictionPair::agree).count();
        sb.append("<i>").append(agree).append(" pronostic(s) compatibles sur ").append(both).append(" match(s) analysé(s) par les deux sources.</i>\n");
        for (SourceComparison.PredictionPair p : pairs) {
            MatchCard ref = p.a() != null ? p.a() : p.b();
            String home = ref.fixture().homeName(), away = ref.fixture().awayName();
            sb.append('\n').append(esc(KICKOFF.format(ref.fixture().kickoff().atZone(zone)))).append(" · <b>")
                .append(esc(ref.fixture().label())).append("</b>\n");
            sb.append(esc(sourceA)).append(" : ").append(p.a() == null ? "non analysé" : pickText(p.a(), home, away)).append('\n');
            sb.append(esc(sourceB)).append(" : ").append(p.b() == null ? "non analysé" : pickText(p.b(), home, away)).append('\n');
            if (p.bothKnown()) sb.append(p.agree() ? "✅ compatibles" : "⚠️ désaccord : prudence sur ce match").append('\n');
        }
        return sb.toString();
    }

    /** Ligne de comparaison ajoutée sous une fiche /analyse. */
    public static String analysisComparison(String otherSource, PredictionReport main, PredictionReport other) {
        if (main.cards().isEmpty()) return "";
        MatchCard c = main.cards().get(0);
        java.util.Optional<MatchCard> o = SourceComparison.find(other.cards(), c.fixture());
        StringBuilder sb = new StringBuilder("⚖️ <b>Avis de la source ").append(esc(otherSource)).append("</b>\n");
        if (o.isEmpty()) {
            return sb.append("Match non analysé par ").append(esc(otherSource)).append(other.warnings().isEmpty() ? "." : " (" + esc(other.warnings().get(0)) + ")").toString();
        }
        String home = c.fixture().homeName(), away = c.fixture().awayName();
        sb.append(pickText(o.get(), home, away)).append('\n');
        Map<Market, Double> pa = c.prediction().probabilities(), pb = o.get().prediction().probabilities();
        sb.append("1/N/2 : ").append(pct(pb.get(Market.HOME_WIN))).append(" / ").append(pct(pb.get(Market.DRAW))).append(" / ")
            .append(pct(pb.get(Market.AWAY_WIN))).append(" (").append(esc(main.source())).append(" : ")
            .append(pct(pa.get(Market.HOME_WIN))).append(" / ").append(pct(pa.get(Market.DRAW))).append(" / ").append(pct(pa.get(Market.AWAY_WIN))).append(")\n");
        boolean agree = new SourceComparison.PredictionPair(c, o.get()).agree();
        sb.append(agree ? "✅ Les deux sources donnent un pronostic compatible." : "⚠️ Les deux sources ne sont pas d'accord : prudence.");
        return sb.toString();
    }

    private static String pickText(MatchCard c, String home, String away) {
        Prediction p = c.prediction();
        return esc(p.main().label(home, away)) + " (" + pct(p.mainProbability()) + ") · score " + p.scoreHome() + "-" + p.scoreAway()
            + " · confiance " + p.confidence().label;
    }

    private static String sectionTitle(String key) {
        return switch (key) {
            case "mois-securite" -> "coupon sécurité";
            case "mois-gros" -> "gros coupon";
            default -> "coupon";
        };
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 0,52 → "52 %" ; 0,008 → "0,8 % (1 chance sur 125)". */
    static String chance(double p) {
        if (p >= 0.05) return pct(p);
        return String.format(Locale.FRENCH, "%.1f %% (1 chance sur %d)", p * 100, Math.round(1 / Math.max(p, 1e-6)));
    }

    static String pct(double p) {
        return String.format(Locale.FRENCH, "%.0f %%", p * 100);
    }

    static String signedPct(double p) {
        return String.format(Locale.FRENCH, "%+.0f %%", p * 100);
    }

    static String fmt2(double v) {
        return String.format(Locale.FRENCH, "%.2f", v);
    }

    static String fmt0(double v) {
        return String.format(Locale.FRENCH, "%.0f", v);
    }
}
