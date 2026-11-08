package bfoot.engine;

import bfoot.ai.AiPanel;
import bfoot.data.RequestStats;

import java.time.Instant;
import java.util.List;

/** Résultat de /pronostics (plusieurs fiches) ou de /analyse (une seule fiche, ou aucune si l'équipe est introuvable). */
public record PredictionReport(
    String source,
    Instant createdAt,
    Instant from,
    Instant to,
    List<MatchCard> cards,
    String query,
    List<AiPanel.ProviderStatus> aiProviders,
    RequestStats.Snapshot requests,
    String quota,
    List<String> warnings
) {}
