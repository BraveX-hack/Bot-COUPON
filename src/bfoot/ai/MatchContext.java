package bfoot.ai;

/**
 * Ce que les IA reçoivent pour chaque match : les chiffres du modèle, la
 * forme récente, et le contexte joueurs/équipe (absents, note moyenne des
 * joueurs, classement, composition, face-à-face) dans "context".
 */
public record MatchContext(
    int eventId,
    String kickoff,
    String competition,
    String home,
    String away,
    double lambdaHome,
    double lambdaAway,
    double pHome,
    double pDraw,
    double pAway,
    double pOver25,
    double pBtts,
    String homeForm,
    String awayForm,
    String context
) {}
