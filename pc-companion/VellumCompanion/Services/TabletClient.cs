using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
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
///
/// Every request must carry the 6-digit pairing PIN the tablet shows on its Connect screen, as an
/// X-Vellum-Pin header. Without it (or with a wrong one) the tablet answers 401; after five wrong PINs
/// it answers 429 for the rest of that sync session and only a Stop/Start on the tablet (which makes a
/// new PIN) clears it. This is plain HTTP: the PIN keeps other people on the network out, it does not
/// encrypt anything.
/// </summary>
public sealed class TabletClient : IDisposable
{
    public const string PinHeader = "X-Vellum-Pin";
    public const int PinLength = 6;

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
    /// Normalizes what the user typed into the PIN box (tolerating a space or dash they copied from
    /// the tablet, e.g. "123 456") and rejects anything that is not exactly 6 digits, so a typo is
    /// caught here instead of costing one of the tablet's five allowed attempts.
    /// </summary>
    public static string NormalizePin(string? entered)
    {
        var digits = new string((entered ?? string.Empty).Where(char.IsAsciiDigit).ToArray());
        if (digits.Length != PinLength)
        {
            throw new ArgumentException(
                $"Enter the {PinLength}-digit PIN shown on the tablet's Connect screen.", nameof(entered));
        }

        return digits;
    }

    /// <summary>
    /// Sends a GET carrying the PIN and turns the tablet's auth refusals into <see cref="TabletAuthException"/>
    /// with a message the user can act on. Any other non-success status is left to EnsureSuccessStatusCode.
    /// </summary>
    private async Task<HttpResponseMessage> SendAsync(
        string requestUri,
        string pin,
        HttpCompletionOption completion,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, requestUri);
        request.Headers.Add(PinHeader, pin);

        var response = await _httpClient
            .SendAsync(request, completion, cancellationToken)
            .ConfigureAwait(false);

        if (response.StatusCode == HttpStatusCode.Unauthorized)
        {
            response.Dispose();
            throw new TabletAuthException(
                "The tablet rejected the PIN. Check the PIN on the tablet's Connect screen and try again.",
                locked: false);
        }

        if (response.StatusCode == HttpStatusCode.TooManyRequests)
        {
            response.Dispose();
            throw new TabletAuthException(
                "The tablet locked itself after too many wrong PINs. On the tablet, tap Stop then Start Wi-Fi Sync to get a new PIN.",
                locked: true);
        }

        return response;
    }

    /// <summary>
    /// Fetches the list of projects currently on the tablet via GET /projects.
    /// </summary>
    public async Task<IReadOnlyList<ProjectSummary>> GetProjectsAsync(
        string baseUrl,
        string pin,
        CancellationToken cancellationToken = default)
    {
        var requestUri = $"{baseUrl}/projects";

        using var response = await SendAsync(requestUri, pin, HttpCompletionOption.ResponseContentRead, cancellationToken)
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
        string pin,
        string projectId,
        string destinationFilePath,
        CancellationToken cancellationToken = default)
    {
        var requestUri = $"{baseUrl}/projects/{Uri.EscapeDataString(projectId)}/export.zip";

        using var response = await SendAsync(requestUri, pin, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
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

/// <summary>
/// The tablet refused the request's PIN (401) or has locked itself after too many wrong ones (429).
/// Kept separate from HttpRequestException so the window can show the fix instead of "401 Unauthorized".
/// </summary>
public sealed class TabletAuthException : Exception
{
    public TabletAuthException(string message, bool locked) : base(message)
    {
        Locked = locked;
    }

    /// <summary>True when the tablet is locked out (429): retrying, even with the right PIN, cannot succeed.</summary>
    public bool Locked { get; }
}
