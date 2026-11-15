using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 自发帧先翻译并持久化到本地 outbox，再由唯一后台泵串行 POST。
/// 非 2xx / 网络异常均保留文件；2xx 后才删除。重启后自动加载同一批文件补发。
/// </summary>
public sealed class DeviceEventForwarder
{
    public const string Path = "/api/device-events";

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(5) };

    private readonly string _baseUrl;
    private readonly string? _deviceCode;
    private readonly string? _token;
    private readonly ISpontaneousTranslator _translator;
    private readonly Action<string> _log;
    private readonly DeviceEventOutbox _outbox;
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _wake = new(0, 1);
    private readonly Task _pump;

    public DeviceEventForwarder(string baseUrl, string? deviceCode, string? token,
                                ISpontaneousTranslator translator, Action<string> log,
                                string outboxDirectory)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _deviceCode = string.IsNullOrWhiteSpace(deviceCode) ? null : deviceCode.Trim();
        _token = string.IsNullOrWhiteSpace(token) ? null : token.Trim();
        _translator = translator ?? JsonSpontaneousTranslator.Instance;
        _log = log;
        _outbox = new DeviceEventOutbox(outboxDirectory);
        _pump = Task.Run(PumpAsync);
        _log("持久 outbox 已启用：" + _outbox.DirectoryPath);
    }

    /// <summary>
    /// 返回 false = 不是可翻译的自发帧。true 表示请求体已可靠落盘，不代表 HTTP 已送达。
    /// 此方法把磁盘写完才返回，调用方不会遗留无人观察的转发 Task。
    /// </summary>
    public Task<bool> ForwardAsync(string frame, CancellationToken ct = default)
    {
        var sp = _translator.Translate(frame);
        if (sp == null)
        {
            return Task.FromResult(false);
        }
        var device = _deviceCode ?? sp.EquipmentId;
        var payload = string.IsNullOrWhiteSpace(sp.PayloadJson) ? "{}" : sp.PayloadJson;
        var sourceEventId = Guid.NewGuid().ToString();
        var body = "{\"sourceEventId\":" + JsonSerializer.Serialize(sourceEventId)
                 + ",\"device\":" + JsonSerializer.Serialize(device)
                 + ",\"eventType\":" + JsonSerializer.Serialize(sp.EventType)
                 + ",\"payload\":" + payload + "}";
        _outbox.Append(sourceEventId, body);
        Signal();
        _log($"事件已持久化等待投递：{device} {sp.EventType} sourceEventId={sourceEventId}");
        return Task.FromResult(true);
    }

    private async Task PumpAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            try
            {
                var pending = _outbox.LoadPending();
                if (pending.Count == 0)
                {
                    await _wake.WaitAsync(TimeSpan.FromMilliseconds(250), _stop.Token)
                        .ConfigureAwait(false);
                    continue;
                }

                var failed = false;
                foreach (var entry in pending)
                {
                    _stop.Token.ThrowIfCancellationRequested();
                    if (!await SendAsync(entry).ConfigureAwait(false))
                    {
                        failed = true;
                        break; // 保留顺序，最早失败的事件先补发；后续事件也不会丢。
                    }
                    _outbox.Ack(entry);
                    _log("设备事件已确认投递并移出 outbox：sourceEventId=" + entry.SourceEventId);
                }
                if (failed)
                {
                    await Task.Delay(TimeSpan.FromSeconds(1), _stop.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (_stop.IsCancellationRequested)
            {
                break;
            }
            catch (Exception e)
            {
                // 文件损坏、权限或删除异常均不能让后台泵永久退出。
                _log("⚠ 设备事件 outbox 泵异常（文件保留，稍后重试）：" + e.Message);
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(1), _stop.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
    }

    private async Task<bool> SendAsync(DeviceEventOutbox.Entry entry)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, _baseUrl + Path)
            {
                Content = new StringContent(entry.Body, System.Text.Encoding.UTF8, "application/json"),
            };
            if (_token != null)
            {
                req.Headers.TryAddWithoutValidation("X-Labos-Device-Token", _token);
            }
            using var resp = await Http.SendAsync(req, _stop.Token).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
            {
                _log($"⚠ 设备事件投递未确认 {(int)resp.StatusCode}，保留并重试："
                     + entry.SourceEventId);
                return false;
            }
            return true;
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested)
        {
            return false;
        }
        catch (Exception e)
        {
            _log("⚠ 设备事件投递失败，保留并重试：" + entry.SourceEventId + "；" + e.Message);
            return false;
        }
    }

    private void Signal()
    {
        if (_wake.CurrentCount == 0)
        {
            _wake.Release();
        }
    }
}
