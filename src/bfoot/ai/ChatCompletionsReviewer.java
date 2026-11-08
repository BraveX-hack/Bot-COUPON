package bfoot.ai;

import bfoot.util.Env;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fournisseur au format "chat completions" (OpenAI) : utilisé pour Groq et
 * Mistral. Les paramètres optionnels qu'un modèle refuse (erreur 400 qui les
 * cite) sont retirés automatiquement puis l'appel est refait une fois.
 */
public final class ChatCompletionsReviewer implements AiReviewer {

    private final String name;
    private final String url;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final long minIntervalMs;
    private final Map<String, Object> optionalParams;

    private ChatCompletionsReviewer(String name, String url, String apiKey, String model, int maxTokens,
                                    long minIntervalMs, Map<String, Object> optionalParams) {
        this.name = name;
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.minIntervalMs = minIntervalMs;
        this.optionalParams = optionalParams;
    }

    /** Groq : modèle configurable via GROQ_MODEL (défaut openai/gpt-oss-20b). */
    public static ChatCompletionsReviewer groq() {
        String model = Env.get("GROQ_MODEL", "openai/gpt-oss-20b");
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("reasoning_format", "hidden");
        if (model.startsWith("openai/gpt-oss")) opt.put("reasoning_effort", "low");
        // Le palier gratuit Groq limite les tokens par minute : un lot de 6
        // matchs coûte ~3 000 tokens (raisonnement compris), d'où l'espacement.
        return new ChatCompletionsReviewer("Groq", "https://api.groq.com/openai/v1/chat/completions",
            Env.get("GROQ_API_KEY"), model, 3000, Env.getInt("GROQ_MIN_INTERVAL_MS", 20_000), opt);
    }

    /** Mistral : modèle configurable via MISTRAL_MODEL (défaut mistral-small-latest). */
    public static ChatCompletionsReviewer mistral() {
        return new ChatCompletionsReviewer("Mistral", "https://api.mistral.ai/v1/chat/completions",
            Env.get("MISTRAL_API_KEY"), Env.get("MISTRAL_MODEL", "mistral-small-latest"), 1500,
            Env.getInt("MISTRAL_MIN_INTERVAL_MS", 2_000), new LinkedHashMap<>());
    }

    @Override public String name() { return name; }
    @Override public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    @Override public long minIntervalMs() { return minIntervalMs; }

    @Override
    public String complete(String prompt) throws Exception {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("temperature", 0.2);
        opt.put("response_format", new JSONObject().put("type", "json_object"));
        opt.putAll(optionalParams);
        for (int attempt = 0; attempt < 4; attempt++) {
            JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
                .put("max_tokens", maxTokens);
            opt.forEach(body::put);

            AiHttp.Response r = AiHttp.post(url, Map.of("Authorization", "Bearer " + apiKey), body.toString());
            if (r.status() == 200) {
                return new JSONObject(r.body()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
            }
            if (r.status() == 400) {
                String rejected = opt.keySet().stream().filter(k -> r.body().contains(k)).findFirst().orElse(null);
                if (rejected != null) {
                    opt.remove(rejected);
                    continue;
                }
            }
            throw new IllegalStateException(name + " a répondu " + r.status() + " : " + abbreviate(r.body()));
        }
        throw new IllegalStateException(name + " : paramètres refusés à répétition");
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
