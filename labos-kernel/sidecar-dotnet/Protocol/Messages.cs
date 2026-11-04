using System.Text.Json.Serialization;

namespace LabOS.Sidecar.Protocol;

/// <summary>
/// 驱动通信协议的消息类型。
/// </summary>
public static class MessageTypes
{
    public const int GatewayHeartBeat = -1;
    public const int HeartBeat = 0;
    public const int Task = 1;
    public const int Query = 2;
    public const int Troubleshoot = 3;
    public const int Ack = 4;
    public const int Finish = 5;
}

/// <summary>帧分隔符。驱动按这个字符串切消息，不能改。</summary>
public static class Framing
{
    public const string Delimiter = "#!LABSEP!#";
}

/// <summary>
/// 下行指令（对应老库 MethodMessage）。
/// 注意字段名必须与驱动端一致——驱动反序列化时按原字段名匹配。
/// </summary>
public sealed class MethodMessage
{
    [JsonPropertyName("message_id")] public string MessageId { get; set; } = "";
    [JsonPropertyName("message_type")] public int MessageType { get; set; } = MessageTypes.Task;
    [JsonPropertyName("method")] public string Method { get; set; } = "";
    [JsonPropertyName("equipment_id")] public string EquipmentId { get; set; } = "";
    [JsonPropertyName("workflow_id")] public string WorkflowId { get; set; } = "";
    [JsonPropertyName("experiment_id")] public string ExperimentId { get; set; } = "";
    [JsonPropertyName("parameters")] public Dictionary<string, string> Parameters { get; set; } = new();
    [JsonPropertyName("description")] public string Description { get; set; } = "";
}

/// <summary>
/// 上行结果（对应老库 FinishMessage）。
/// 字段比 Java DTO 精简，但保留了适配必需的：message_id / message_type / method_status / error。
/// </summary>
public sealed class FinishMessage
{
    [JsonPropertyName("message_id")] public string MessageId { get; set; } = "";
    [JsonPropertyName("message_type")] public int MessageType { get; set; }
    [JsonPropertyName("method")] public string Method { get; set; } = "";
    [JsonPropertyName("equipment_id")] public string EquipmentId { get; set; } = "";
    [JsonPropertyName("equipment_status")] public int EquipmentStatus { get; set; } = -1;
    [JsonPropertyName("method_status")] public int MethodStatus { get; set; }
    [JsonPropertyName("error")] public ErrorInfo? Error { get; set; }
    [JsonPropertyName("data")] public object? Data { get; set; }
    [JsonPropertyName("process_info")] public object? ProcessInfo { get; set; }
    [JsonPropertyName("timestamp")] public string Timestamp { get; set; } = "";
    [JsonPropertyName("checksum")] public string Checksum { get; set; } = "";
    [JsonPropertyName("connected")] public int Connected { get; set; }

    /// <summary>是否执行成功。老协议里 method_status=2 表示成功。</summary>
    [JsonIgnore] public bool IsSuccess => MethodStatus == 2;
}

/// <summary>错误对象。</summary>
public sealed class ErrorInfo
{
    [JsonPropertyName("error_code")] public string? ErrorCode { get; set; }
    [JsonPropertyName("troubleshoot")] public int Troubleshoot { get; set; }
    [JsonPropertyName("error_text")] public string? ErrorText { get; set; }
}

/// <summary>心跳（适配层用它判断连接活性）。</summary>
public sealed class HeartBeatMessage
{
    [JsonPropertyName("message_id")] public string MessageId { get; set; } = "";
    [JsonPropertyName("message_type")] public int MessageType { get; set; } = MessageTypes.HeartBeat;
    [JsonPropertyName("equipment_id")] public string EquipmentId { get; set; } = "";
    [JsonPropertyName("equipment_status")] public int EquipmentStatus { get; set; } = -1;
    [JsonPropertyName("timestamp")] public string Timestamp { get; set; } = "";
    [JsonPropertyName("connected")] public int Connected { get; set; }
}
