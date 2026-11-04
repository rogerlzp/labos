using System.Text.Json;
using System.Text.Json.Nodes;

namespace Labos.Sidecar;

/// <summary>
/// 中控 ↔ sidecar 的线上协议：**一行 JSON + 分隔符**（{@code #!LABSEP!#}）。
///
/// <para>
/// 与模拟器、与 labos-kernel 的 <c>TcpDriverAdapter</c> 完全一致 —— 这样才能做到
/// "仿真阶段怎么跑，现场就怎么跑"，换的只是 sidecar 内部那条传输。
/// </para>
///
/// <para><b>回包形状（与模拟器逐字一致）</b>：</para>
/// <code>
/// {"message_id":"..","message_type":5,"method":"Seal","equipment_id":"ID1183",
///  "equipment_status":1,"method_status":2,"error":null,
///  "data":{...},"process_info":{},"timestamp":"..","checksum":"","connected":1}
/// </code>
/// 失败时 {@code method_status=3}，{@code error={"error_code":"0x8000000C","error_text":".."}}。
/// <para>
/// **注意 error_code 必须是设备原始码**（{@code 0x8000000C}），不是中控码
/// {@code EC-08-400C}：翻译是 labos-kernel 里 {@code DeviceErrorMapper} 的职责，
/// sidecar 只负责"把设备说的话原样带回来"。
/// </para>
/// </summary>
public static class Protocol
{
    public const string Sep = "#!LABSEP!#";

    public sealed record Request(string MessageId, string Method, string EquipmentId,
                                 Dictionary<string, JsonElement> Parameters, string Raw);

    public static Request? Parse(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var id = Get(root, "message_id");
            var method = Get(root, "method");
            if (string.IsNullOrEmpty(method))
            {
                return null;
            }
            var ps = new Dictionary<string, JsonElement>(StringComparer.OrdinalIgnoreCase);
            if (root.TryGetProperty("parameters", out var p) && p.ValueKind == JsonValueKind.Object)
            {
                foreach (var item in p.EnumerateObject())
                {
                    ps[item.Name] = item.Value.Clone();
                }
            }
            return new Request(id, method, Get(root, "equipment_id"), ps, json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static string Ok(Request req, string dataJson = "{}") =>
        Build(req, 2, "null", dataJson);

    public static string Fail(Request req, string vendorCode, string text) =>
        Build(req, 3,
            new JsonObject { ["error_code"] = vendorCode, ["error_text"] = text }.ToJsonString(),
            "{}");

    private static string Build(Request req, int status, string errorJson, string dataJson)
    {
        var sb = new System.Text.StringBuilder(256);
        sb.Append('{');
        sb.Append("\"message_id\":").Append(JsonSerializer.Serialize(req.MessageId)).Append(',');
        sb.Append("\"message_type\":5,");
        sb.Append("\"method\":").Append(JsonSerializer.Serialize(req.Method)).Append(',');
        sb.Append("\"equipment_id\":").Append(JsonSerializer.Serialize(req.EquipmentId)).Append(',');
        sb.Append("\"equipment_status\":1,");
        sb.Append("\"method_status\":").Append(status).Append(',');
        sb.Append("\"error\":").Append(errorJson).Append(',');
        sb.Append("\"data\":").Append(string.IsNullOrWhiteSpace(dataJson) ? "{}" : dataJson).Append(',');
        sb.Append("\"process_info\":{},");
        sb.Append("\"timestamp\":\"").Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")).Append("\",");
        sb.Append("\"checksum\":\"\",\"connected\":1");
        sb.Append('}');
        return sb.ToString();
    }

    private static string Get(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";

    /// <summary>从 parameters 里取整数（字符串数字也认 —— 中控发的都是字符串）。</summary>
    public static int? Int(IReadOnlyDictionary<string, JsonElement> p, params string[] names)
    {
        foreach (var n in names)
        {
            if (!p.TryGetValue(n, out var v))
            {
                continue;
            }
            if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i))
            {
                return i;
            }
            if (v.ValueKind == JsonValueKind.String && int.TryParse(v.GetString(), out var s))
            {
                return s;
            }
        }
        return null;
    }
}
