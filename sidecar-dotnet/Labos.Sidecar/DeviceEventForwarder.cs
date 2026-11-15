using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 把设备**主动上报**的帧，翻译成统一形状后转发给中控的设备事件入站端点。
///
/// <para><b>为什么翻译在这一层</b>：设备协议千差万别（帧格式、事件码各不相同），
/// 中控不该在 HTTP 层猜设备的协议 ⇒ 中控只认一个统一形状：
/// <code>
/// POST /api/device-events
/// {"device":"ID-EXT1","eventType":"waiting_interaction","payload":{...}}
/// </code>
/// 谁把设备的原始帧翻译成这个形状，就是<b>适配层</b>的活 —— 也就是这里。</para>
///
/// <para><b>刻意不引 NuGet 包</b>（与 {@link DeviceRegistry} 同口径）：HTTP 客户端是 BCL 自带的，
/// 依赖越少越好。</para>
///
/// <para><b>转发失败不致命</b>：设备事件是"锦上添花"的一路信息通道，
/// 它坏了不该把命令下发也带崩 ⇒ 失败只记日志，下一轮继续。</para>
/// </summary>
public sealed class DeviceEventForwarder
{
    /// <summary>中控的设备事件入站端点。</summary>
    public const string Path = "/api/device-events";

    private readonly string _baseUrl;
    private readonly string? _deviceCode;
    private readonly string? _token;
    private readonly ISpontaneousTranslator _translator;
    private readonly Action<string> _log;

    /// <param name="baseUrl">中控地址（与 --registry-url 同一个：那是中控）。</param>
    /// <param name="deviceCode">本 sidecar 认领的设备码；为空则用帧里的 equipment_id。</param>
    /// <param name="token">设备事件令牌（中控配了 LABOS_DEVICE_EVENT_TOKEN 时才校验）。</param>
    /// <param name="translator">该型号的自发帧翻译器（按型号专属；没有就通用 JSON）。</param>
    /// <param name="log">日志出口（sidecar 用 Console.WriteLine）。</param>
    public DeviceEventForwarder(string baseUrl, string? deviceCode, string? token,
                                 ISpontaneousTranslator translator, Action<string> log)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _deviceCode = string.IsNullOrWhiteSpace(deviceCode) ? null : deviceCode.Trim();
        _token = string.IsNullOrWhiteSpace(token) ? null : token.Trim();
        _translator = translator ?? JsonSpontaneousTranslator.Instance;
        _log = log;
    }

    /// <summary>
    /// 转发一帧。返回 false = 这一帧不是自发帧（或没认领到设备），不该发。
    /// </summary>
    public async Task<bool> ForwardAsync(string frame, CancellationToken ct = default)
    {
        var sp = _translator.Translate(frame);
        if (sp == null)
        {
            return false;
        }
        // 设备码以**本 sidecar 认领的**为准：它是这台设备的长连接持有者，
        // 比设备自己在帧里写的更可信（设备可能只写序号或短名）。
        var device = _deviceCode ?? sp.EquipmentId;
        var payload = string.IsNullOrWhiteSpace(sp.PayloadJson) ? "{}" : sp.PayloadJson;
        var body = "{\"device\":" + JsonSerializer.Serialize(device)
                 + ",\"eventType\":" + JsonSerializer.Serialize(sp.EventType)
                 + ",\"payload\":" + payload + "}";
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
            using var req = new HttpRequestMessage(HttpMethod.Post, _baseUrl + Path)
            {
                Content = new StringContent(body, System.Text.Encoding.UTF8, "application/json"),
            };
            if (_token != null)
            {
                req.Headers.TryAddWithoutValidation("X-Labos-Device-Token", _token);
            }
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
            {
                _log($"⚠ 设备事件转发被拒 {(int)resp.StatusCode}：{sp.EventType}");
                return true;
            }
            _log($"已转发设备事件：{device} {sp.EventType} {payload}");
            return true;
        }
        catch (Exception e)
        {
            // 转发失败**不致命**：命令下发这条路不能被事件通道带崩
            _log($"⚠ 设备事件转发失败（忽略）：{e.Message}");
            return true;
        }
    }
}
