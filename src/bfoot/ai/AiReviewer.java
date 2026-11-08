package bfoot.ai;

import java.util.List;

/** Un fournisseur d'IA capable de relire un LOT de matchs en un seul appel. */
public interface AiReviewer {

    /** Nom affiché (Gemini, Mistral, Groq). */
    String name();

    /** Faux si la clé API n'est pas configurée : le fournisseur est alors ignoré. */
    boolean configured();

    /** Délai minimal entre deux appels à ce fournisseur (quotas gratuits). */
    long minIntervalMs();

    /** Envoie le prompt et renvoie le texte brut de la réponse. */
    String complete(String prompt) throws Exception;

    /** Relit un lot de matchs (un seul appel réseau). */
    default List<AiAdjustment> review(List<MatchContext> batch) throws Exception {
        return AiPrompt.parse(complete(AiPrompt.build(batch)), batch);
    }
}
