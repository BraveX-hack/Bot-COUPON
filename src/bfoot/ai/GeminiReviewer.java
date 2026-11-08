package bfoot.ai;

import bfoot.util.Env;
import bfoot.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Gemini via l'API REST (plus besoin de la bibliothèque google-genai).
 *
 * Les noms de modèles sont configurables (GEMINI_MODELS, séparés par des
 * virgules) : Google ferme régulièrement d'anciens modèles, et les alias
 * "-latest" suivent automatiquement la version courante. Le premier modèle
 * qui répond est mémorisé pour les appels suivants.
 */
public final class GeminiReviewer implements AiReviewer {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey = Env.get("GEMINI_API_KEY");
    private final List<String> models = Arrays.stream(
            Env.get("GEMINI_MODELS", "gemini-flash-latest,gemini-3-flash-preview,gemini-flash-lite-latest").split(","))
        .map(String::trim).filter(s -> !s.isEmpty()).toList();
    private volatile String workingModel;

    @Override public String name() { return "Gemini"; }
    @Override public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    @Override public long minIntervalMs() { return Env.getInt("GEMINI_MIN_INTERVAL_MS", 5_000); }

    @Override
    public String complete(String prompt) throws Exception {
        JSONObject body = new JSONObject()
            .put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
            .put("generationConfig", new JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json"));

        List<String> order = workingModel != null ? List.of(workingModel) : models;
        Exception last = null;
        for (String model : order) {
            String url = BASE + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent";
            AiHttp.Response r = AiHttp.post(url, Map.of("x-goog-api-key", apiKey), body.toString());
            if (r.status() == 200) {
                workingModel = model;
                return extractText(new JSONObject(r.body()));
            }
            last = new IllegalStateException("Gemini [" + model + "] a répondu " + r.status() + " : "
                + (r.body().length() > 300 ? r.body().substring(0, 300) + "…" : r.body()));
            Log.warn(last.getMessage());
            if (r.status() == 429) break; // quota : inutile d'essayer les autres modèles
        }
        if (workingModel != null && order.size() == 1) workingModel = null; // re-tester la liste au prochain appel
        throw last != null ? last : new IllegalStateException("Aucun modèle Gemini configuré");
    }

    private static String extractText(JSONObject json) {
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini : réponse sans candidat " + json.optJSONObject("promptFeedback"));
        }
        JSONArray parts = candidates.getJSONObject(0).optJSONObject("content") != null
            ? candidates.getJSONObject(0).getJSONObject("content").optJSONArray("parts") : null;
        StringBuilder sb = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""));
            }
        }
        return sb.toString();
    }
}
