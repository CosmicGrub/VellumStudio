using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using VellumCompanion.Models;

namespace VellumCompanion.Services;

/// <summary>
/// Thin wrapper around the HTTP calls Vellum Studio's PC companion makes to
/// the tablet app's LAN sync server. Keeps networking/JSON concerns out of
/// the window's code-behind.
///
/// The tablet is expected to expose (default port 8642):
///   GET /projects                -> JSON array of ProjectSummary
///   GET /projects/{id}/export.zip -> zip containing the flattened PNG,
///                                     individual layer PNGs, and metadata.json
/// </summary>
public sealed class TabletClient : IDisposable
{
    private readonly HttpClient _httpClient;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    public TabletClient(TimeSpan? timeout = null)
    {
        _httpClient = new HttpClient
        {
            Timeout = timeout ?? TimeSpan.FromSeconds(10),
        };
    }

    /// <summary>
    /// Normalizes a user-entered "host:port" (or "host") string into a base
    /// "http://host:port" URI. Defaults to port 8642 when none is given.
    /// </summary>
    public static string BuildBaseUrl(string hostAndPort)
    {
        if (string.IsNullOrWhiteSpace(hostAndPort))
        {
            throw new ArgumentException("Tablet address cannot be empty.", nameof(hostAndPort));
        }

        var trimmed = hostAndPort.Trim();

        // Allow the user to paste a full URL, or just "ip:port" / "ip".
        if (trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
            trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return trimmed.TrimEnd('/');
        }

        if (!trimmed.Contains(':'))
        {
            trimmed += ":8642";
        }

        return $"http://{trimmed}";
    }

    /// <summary>
    /// Fetches the list of projects currently on the tablet via GET /projects.
    /// </summary>
    public async Task<IReadOnlyList<ProjectSummary>> GetProjectsAsync(
        string baseUrl,
        CancellationToken cancellationToken = default)
    {
        var requestUri = $"{baseUrl}/projects";

        using var response = await _httpClient
            .GetAsync(requestUri, cancellationToken)
            .ConfigureAwait(false);

        response.EnsureSuccessStatusCode();

        await using var stream = await response.Content
            .ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);

        var projects = await JsonSerializer
            .DeserializeAsync<List<ProjectSummary>>(stream, JsonOptions, cancellationToken)
            .ConfigureAwait(false);

        return projects ?? new List<ProjectSummary>();
    }

    /// <summary>
    /// Downloads the export zip for a single project (flattened PNG + layer
    /// PNGs + metadata.json) via GET /projects/{id}/export.zip and writes it
    /// to the given destination path. The caller is responsible for choosing
    /// that path (e.g. via a SaveFileDialog) — this method never picks a
    /// location on its own.
    /// </summary>
    public async Task DownloadProjectZipAsync(
        string baseUrl,
        string projectId,
        string destinationFilePath,
        CancellationToken cancellationToken = default)
    {
        var requestUri = $"{baseUrl}/projects/{Uri.EscapeDataString(projectId)}/export.zip";

        using var response = await _httpClient
            .GetAsync(requestUri, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);

        response.EnsureSuccessStatusCode();

        await using var httpStream = await response.Content
            .ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);

        await using var fileStream = File.Create(destinationFilePath);
        await httpStream.CopyToAsync(fileStream, cancellationToken).ConfigureAwait(false);
    }

    public void Dispose()
    {
        _httpClient.Dispose();
    }
}
