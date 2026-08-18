using System;
using System.Collections.ObjectModel;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using VellumCompanion.Models;
using VellumCompanion.Services;

namespace VellumCompanion;

public partial class MainWindow : Window
{
    private readonly TabletClient _tabletClient = new();
    private readonly ObservableCollection<ProjectSummary> _projects = new();

    private string? _connectedBaseUrl;

    public MainWindow()
    {
        InitializeComponent();
        ProjectsListView.ItemsSource = _projects;
        ProjectsListView.SelectionChanged += (_, _) =>
            DownloadButton.IsEnabled = ProjectsListView.SelectedItem is ProjectSummary;
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        ConnectButton.IsEnabled = false;
        DownloadButton.IsEnabled = false;
        _projects.Clear();

        try
        {
            var baseUrl = TabletClient.BuildBaseUrl(TabletAddressTextBox.Text);
            SetStatus($"Connecting to {baseUrl}...");

            var projects = await _tabletClient.GetProjectsAsync(baseUrl);

            foreach (var project in projects)
            {
                _projects.Add(project);
            }

            _connectedBaseUrl = baseUrl;
            SetStatus($"Connected to {baseUrl}. Found {_projects.Count} project(s).");
        }
        catch (ArgumentException ex)
        {
            SetStatus($"Invalid address: {ex.Message}");
        }
        catch (HttpRequestException ex)
        {
            SetStatus($"Could not reach tablet: {ex.Message}");
        }
        catch (TaskCanceledException)
        {
            SetStatus("Connection timed out. Check the tablet's IP:port and that both devices are on the same network.");
        }
        catch (Exception ex)
        {
            // Catch-all so a flaky LAN connection never crashes the app.
            SetStatus($"Unexpected error: {ex.Message}");
        }
        finally
        {
            ConnectButton.IsEnabled = true;
        }
    }

    private async void DownloadButton_Click(object sender, RoutedEventArgs e)
    {
        if (_connectedBaseUrl is null || ProjectsListView.SelectedItem is not ProjectSummary selected)
        {
            SetStatus("Select a project first.");
            return;
        }

        var saveDialog = new SaveFileDialog
        {
            Title = "Save Project Export",
            FileName = $"{SanitizeFileName(selected.Name)}.zip",
            Filter = "Zip archive (*.zip)|*.zip|All files (*.*)|*.*",
            DefaultExt = ".zip",
        };

        if (saveDialog.ShowDialog(this) != true)
        {
            return; // User cancelled — do not write anything.
        }

        DownloadButton.IsEnabled = false;
        try
        {
            SetStatus($"Downloading '{selected.Name}'...");

            await _tabletClient.DownloadProjectZipAsync(
                _connectedBaseUrl,
                selected.Id,
                saveDialog.FileName);

            SetStatus($"Saved '{selected.Name}' to {saveDialog.FileName}");
        }
        catch (HttpRequestException ex)
        {
            SetStatus($"Download failed: {ex.Message}");
        }
        catch (Exception ex)
        {
            SetStatus($"Unexpected error while downloading: {ex.Message}");
        }
        finally
        {
            DownloadButton.IsEnabled = true;
        }
    }

    private void SetStatus(string message) => StatusTextBlock.Text = message;

    private static string SanitizeFileName(string name)
    {
        foreach (var invalidChar in System.IO.Path.GetInvalidFileNameChars())
        {
            name = name.Replace(invalidChar, '_');
        }

        return string.IsNullOrWhiteSpace(name) ? "project" : name;
    }
}
