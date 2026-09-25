# Local Windows media bridge: stdout JSON, stdin fixed playback verbs. No network or credentials.
param([switch]$Once)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetGenericArguments().Count -eq 1 -and
    $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await-Result($operation, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
    if (-not $task.Wait(5000)) { throw 'Media session timed out' }
    return $task.Result
}
function Emit($value) { [Console]::WriteLine(($value | ConvertTo-Json -Compress -Depth 5)) }
$streamAdapter = [System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1
} | Select-Object -First 1
# A CLR thread reads stdin without blocking polling or requiring a PS runspace.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Threading.Tasks;
public static class MediaInput {
    public static readonly ConcurrentQueue<string> Lines = new ConcurrentQueue<string>();
    public static volatile bool Closed;
    public static void Start() {
        Task.Run(() => {
            try { string line; while ((line = Console.ReadLine()) != null) {
                if (line.Length < 32 && Lines.Count < 8) Lines.Enqueue(line);
            }} finally { Closed = true; }
        });
    }
}
'@
if (-not $Once) { [MediaInput]::Start() }
$manager = Await-Result ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$artKey = ''; $art = ''; $notice = ''; $noticeUntil = [DateTime]::MinValue
do {
    try {
        # Only control a Spotify session, never the system-wide active media app.
        $sessions = @($manager.GetSessions() | Where-Object { $_.SourceAppUserModelId -match '(?i)spotify' })
        $session = $sessions | Where-Object { $_.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing' } | Select-Object -First 1
        if ($null -eq $session) { $session = $sessions | Select-Object -First 1 }
        $command = ''
        while ([MediaInput]::Lines.TryDequeue([ref]$command)) {
            if ($command -eq 'quit') { exit 0 }
            if ($null -eq $session) { continue }
            $controls = $session.GetPlaybackInfo().Controls
            $operation = $null
            switch ($command) {
                'previous' { if ($controls.IsPreviousEnabled) { $operation = $session.TrySkipPreviousAsync() } }
                'next' { if ($controls.IsNextEnabled) { $operation = $session.TrySkipNextAsync() } }
                'play' { if ($controls.IsPlayEnabled) { $operation = $session.TryPlayAsync() } }
                'pause' { if ($controls.IsPauseEnabled) { $operation = $session.TryPauseAsync() } }
            }
            if ($null -ne $operation -and -not (Await-Result $operation ([bool]))) {
                $notice = 'Spotify could not perform that action'; $noticeUntil = [DateTime]::UtcNow.AddSeconds(4)
            }
        }
        if ($null -eq $session) {
            $artKey = ''; $art = ''
            Emit @{ message = 'Open Spotify and play a song'; track = $null }
        } else {
            $media = Await-Result ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $info = $session.GetPlaybackInfo(); $timeline = $session.GetTimelineProperties()
            $playing = $info.PlaybackStatus.ToString() -eq 'Playing'
            $duration = [Math]::Max(0, ($timeline.EndTime - $timeline.StartTime).TotalMilliseconds)
            $position = [Math]::Max(0, ($timeline.Position - $timeline.StartTime).TotalMilliseconds)
            if ($playing -and $duration -gt 0) {
                $position += [Math]::Max(0, ([DateTimeOffset]::UtcNow - $timeline.LastUpdatedTime).TotalMilliseconds)
            }
            $position = [Math]::Min($position, $duration)
            $key = $session.SourceAppUserModelId + '|' + $media.Title + '|' + $media.Artist + '|' + $media.AlbumTitle
            if ($key -ne $artKey) {
                $artKey = $key; $art = ''
                if ($null -ne $media.Thumbnail) {
                    $stream = $null; $reader = $null; $netStream = $null
                    try {
                        $stream = Await-Result ($media.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                        # Reflection lets the CLR query the WinRT interface on the returned RCW.
                        $netStream = $streamAdapter.Invoke($null, @($stream))
                        if ($netStream.Length -gt 0 -and $netStream.Length -le 2000000) {
                            $reader = [System.IO.BinaryReader]::new($netStream)
                            $bytes = $reader.ReadBytes([int]$netStream.Length)
                            $art = [Convert]::ToBase64String($bytes)
                        }
                    } catch {
                        $art = ''
                        if ($Once) { [Console]::Error.WriteLine('Artwork: ' + $_.ToString()) }
                    } finally {
                        if ($null -ne $reader) { $reader.Dispose() }
                        elseif ($null -ne $netStream) { $netStream.Dispose() }
                        if ($null -ne $stream -and [System.Runtime.InteropServices.Marshal]::IsComObject($stream)) {
                            $null = [System.Runtime.InteropServices.Marshal]::ReleaseComObject($stream)
                        }
                    }
                }
            }
            $message = if ($playing) { 'Now playing' } else { 'Paused' }
            if ([DateTime]::UtcNow -lt $noticeUntil) { $message = $notice }
            Emit @{ message = $message; track = @{
                title = $media.Title; artist = $media.Artist; image = $art; key = $key
                progress = [long]$position; duration = [long]$duration; playing = $playing
                previous = [bool]$info.Controls.IsPreviousEnabled; next = [bool]$info.Controls.IsNextEnabled
                toggle = $(if ($playing) { [bool]$info.Controls.IsPauseEnabled } else { [bool]$info.Controls.IsPlayEnabled })
            }}
        }
    } catch {
        if ($Once) { [Console]::Error.WriteLine($_.ToString() + ' at ' + $_.ScriptStackTrace) }
        Emit @{ message = 'Local Spotify connection unavailable. Retrying...'; track = $null }
    }
    if ($Once) { break }
    Start-Sleep -Milliseconds 750
} while (-not [MediaInput]::Closed)
