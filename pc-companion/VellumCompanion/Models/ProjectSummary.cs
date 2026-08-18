using System;
using System.Text.Json.Serialization;

namespace VellumCompanion.Models;

/// <summary>
/// Mirrors the JSON shape returned by the tablet's sync server for
/// GET /projects:
///
///   [
///     {
///       "id": "string",
///       "name": "string",
///       "updatedAt": "2026-08-10T12:34:56Z",
///       "thumbnailUrl": "http://192.168.1.23:8642/projects/{id}/thumbnail.png"
///     },
///     ...
///   ]
/// </summary>
public sealed record ProjectSummary
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; init; } = string.Empty;

    [JsonPropertyName("updatedAt")]
    public DateTimeOffset UpdatedAt { get; init; }

    [JsonPropertyName("thumbnailUrl")]
    public string? ThumbnailUrl { get; init; }
}
