package bfoot.ai;

import org.json.JSONObject;

/**
 * Avis d'une IA sur un match : facteurs multiplicatifs (prudents, bornés à
 * ±15 %) sur les buts attendus de chaque équipe, et un drapeau "à éviter".
 * Les IA ne donnent plus directement des probabilités : elles corrigent le
 * modèle de score, qui recalcule ensuite tous les marchés de façon cohérente.
 */
public record AiAdjustment(int eventId, double homeFactor, double awayFactor, boolean avoid, String reason) {

    public static final double MIN_FACTOR = 0.85;
    public static final double MAX_FACTOR = 1.15;

    public static AiAdjustment neutral(int eventId) {
        return new AiAdjustment(eventId, 1.0, 1.0, false, "");
    }

    public static double clampFactor(double f) {
        if (Double.isNaN(f) || Double.isInfinite(f)) return 1.0;
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, f));
    }

    public JSONObject toJson() {
        return new JSONObject().put("id", eventId).put("homeFactor", homeFactor)
            .put("awayFactor", awayFactor).put("avoid", avoid).put("reason", reason);
    }

    public static AiAdjustment fromJson(JSONObject o) {
        return new AiAdjustment(o.getInt("id"), clampFactor(o.optDouble("homeFactor", 1.0)),
            clampFactor(o.optDouble("awayFactor", 1.0)), o.optBoolean("avoid", false), o.optString("reason", ""));
    }
}
