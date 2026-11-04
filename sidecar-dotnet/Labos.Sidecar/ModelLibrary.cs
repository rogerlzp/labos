using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 型号库：**运行期从 models.json 读**，sidecar 里不写死任何设备知识。
///
/// <para>
/// 这份 JSON 由 Java 侧 <c>com.labos.kernel.device.DeviceModelExport</c> 从
/// <c>DeviceModels</c> 生成。理由：型号定义同时被模拟器、中控适配层、真机 sidecar 用，
/// 抄三份一定漂移 —— 现象是"模拟器能跑，现场发不出指令"。
/// </para>
/// </summary>
public sealed class ModelLibrary
{
    public sealed record MethodDef(string Cmd, string Alias, string? Args, long DelayMs, bool Documented);

    public sealed record ErrorDef(string VendorCode, string Meaning, string Disposition,
                                  string KernelCode, string Action);

    public sealed class ModelDef
    {
        public required string Id { get; init; }
        public required string Type { get; init; }
        public required string Vendor { get; init; }
        public required string Model { get; init; }
        public required string Protocol { get; init; }
        public required string Transport { get; init; }
        public required string Evidence { get; init; }
        public int DoorCount { get; init; }
        public long ActionTimeoutMs { get; init; }
        public int ErrorBlock { get; init; }
        public string? Note { get; init; }
        public List<MethodDef> Methods { get; init; } = new();
        public List<ErrorDef> Errors { get; init; } = new();

        /// <summary>按别名或真实指令名找方法；找不到返回 null（不能猜）。</summary>
        public MethodDef? Find(string name) =>
            Methods.FirstOrDefault(m => string.Equals(m.Alias, name, StringComparison.OrdinalIgnoreCase)
                                     || string.Equals(m.Cmd, name, StringComparison.OrdinalIgnoreCase));
    }

    public sealed record PlannedDef(string Id, string Type, string Model, string Missing);

    public List<ModelDef> Models { get; } = new();
    public List<PlannedDef> Planned { get; } = new();

    public ModelDef? Get(string id) =>
        Models.FirstOrDefault(m => string.Equals(m.Id, id, StringComparison.OrdinalIgnoreCase));

    public static ModelLibrary Load(string path)
    {
        if (!File.Exists(path))
        {
            throw new FileNotFoundException(
                $"找不到型号库 {path}。生成方法：\n" +
                "  cd labos-kernel && java -cp out com.labos.kernel.device.DeviceModelExport > ../sidecar-dotnet/models.json");
        }
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var lib = new ModelLibrary();
        foreach (var e in doc.RootElement.GetProperty("models").EnumerateArray())
        {
            var m = new ModelDef
            {
                Id = Str(e, "id"), Type = Str(e, "type"), Vendor = Str(e, "vendor"),
                Model = Str(e, "model"), Protocol = Str(e, "protocol"),
                Transport = Str(e, "transport"), Evidence = Str(e, "evidence"),
                DoorCount = e.GetProperty("doorCount").GetInt32(),
                ActionTimeoutMs = e.GetProperty("actionTimeoutMs").GetInt64(),
                ErrorBlock = e.GetProperty("errorBlock").GetInt32(),
                Note = Str(e, "note"),
            };
            foreach (var x in e.GetProperty("methods").EnumerateArray())
            {
                m.Methods.Add(new MethodDef(
                    Str(x, "cmd"), Str(x, "alias"),
                    x.TryGetProperty("args", out var a) && a.ValueKind == JsonValueKind.String
                        ? a.GetString() : null,
                    x.GetProperty("delayMs").GetInt64(),
                    x.GetProperty("documented").GetBoolean()));
            }
            foreach (var x in e.GetProperty("errors").EnumerateArray())
            {
                m.Errors.Add(new ErrorDef(
                    Str(x, "vendorCode"), Str(x, "meaning"), Str(x, "disposition"),
                    Str(x, "kernelCode"), Str(x, "action")));
            }
            lib.Models.Add(m);
        }
        if (doc.RootElement.TryGetProperty("planned", out var planned))
        {
            foreach (var p in planned.EnumerateArray())
            {
                lib.Planned.Add(new PlannedDef(Str(p, "id"), Str(p, "type"),
                    Str(p, "model"), Str(p, "missing")));
            }
        }
        return lib;
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";
}
