using System.Text;

namespace Labos.Sidecar;

/// <summary>
/// 设备事件本地磁盘 outbox：一条事件一个 JSON 文件。
/// 临时文件完整写入并 flush 后原子改名；只有收到中控 2xx 才由调用方 Ack 删除。
/// </summary>
public sealed class DeviceEventOutbox
{
    public sealed record Entry(string SourceEventId, string Body, string FilePath, DateTime CreatedAtUtc);

    private readonly string _directory;

    public DeviceEventOutbox(string directory)
    {
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new ArgumentException("设备事件 outbox 目录不能为空", nameof(directory));
        }
        _directory = Path.GetFullPath(directory);
        Directory.CreateDirectory(_directory);
    }

    public string DirectoryPath => _directory;

    /// <summary>持久化待发请求；sourceEventId 必须来自首次接帧，后续重试不得更换。</summary>
    public Entry Append(string sourceEventId, string body)
    {
        if (!IsSafeId(sourceEventId))
        {
            throw new ArgumentException("sourceEventId 只能包含字母、数字和连字符", nameof(sourceEventId));
        }
        if (string.IsNullOrWhiteSpace(body))
        {
            throw new ArgumentException("事件请求体不能为空", nameof(body));
        }
        var finalPath = Path.Combine(_directory, sourceEventId + ".json");
        if (File.Exists(finalPath))
        {
            var old = File.ReadAllText(finalPath, Encoding.UTF8);
            if (!string.Equals(old, body, StringComparison.Ordinal))
            {
                throw new IOException("同一 sourceEventId 对应了不同请求体：" + sourceEventId);
            }
            return ToEntry(finalPath);
        }

        var tempPath = Path.Combine(_directory,
            sourceEventId + "." + Guid.NewGuid().ToString("N") + ".tmp");
        var bytes = Encoding.UTF8.GetBytes(body);
        using (var stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            stream.Write(bytes, 0, bytes.Length);
            stream.Flush(true);
        }
        File.Move(tempPath, finalPath);
        return ToEntry(finalPath);
    }

    /// <summary>按落盘时间串行加载；启动时调用同一方法即可恢复未确认事件。</summary>
    public IReadOnlyList<Entry> LoadPending()
    {
        return Directory.EnumerateFiles(_directory, "*.json", SearchOption.TopDirectoryOnly)
            .OrderBy(File.GetCreationTimeUtc)
            .ThenBy(path => path, StringComparer.Ordinal)
            .Select(ToEntry)
            .ToList();
    }

    /// <summary>仅在中控明确返回 2xx 后调用。删除失败会保留文件并以同幂等键重发。</summary>
    public void Ack(Entry entry)
    {
        File.Delete(entry.FilePath);
    }

    public static string DefaultDirectory(string? deviceCode, int port)
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(root))
        {
            root = Path.GetTempPath();
        }
        var device = string.IsNullOrWhiteSpace(deviceCode) ? "unknown" : deviceCode.Trim();
        var safe = string.Concat(device.Select(ch =>
            char.IsLetterOrDigit(ch) || ch is '-' or '_' ? ch : '_'));
        return Path.Combine(root, "Labos", "sidecar", "device-event-outbox",
            safe + "-" + port);
    }

    private static Entry ToEntry(string path)
    {
        var id = Path.GetFileNameWithoutExtension(path);
        return new Entry(id, File.ReadAllText(path, Encoding.UTF8), path,
            File.GetCreationTimeUtc(path));
    }

    private static bool IsSafeId(string? id)
    {
        return !string.IsNullOrWhiteSpace(id)
            && id.Length <= 128
            && id.All(ch => char.IsLetterOrDigit(ch) || ch == '-');
    }
}
