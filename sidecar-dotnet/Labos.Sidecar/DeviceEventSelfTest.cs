using System.Text.Json;

namespace Labos.Sidecar;

/// <summary>
/// 设备事件帧判定的**纯逻辑自测**（{@code dotnet run -- --selftest}）。
///
/// <para><b>为什么要有</b>：自发帧与回包的区分是整条事件链的地基 ——
/// 判据漂移一次，后果是"事件被当成回包丢掉"或"回包被当成事件发出去"，
/// 而且现象极隐蔽（现场只看到"流程卡住等不到事件"）。
/// 这一层不依赖任何进程，必须能单独钉死。</para>
///
/// <para><b>判据</b>：<b>没有 message_id 的帧 = 设备主动上报</b>。
/// 与内核 {@code TcpPersistentDriver.dispatch()} 的规则必须一致。</para>
/// </summary>
public static class DeviceEventSelfTest
{
    private static int _pass;
    private static int _fail;

    public static int Run()
    {
        Console.WriteLine("════ 设备事件帧判定 自测 ════");
        Console.WriteLine();

        // ① 回包（有 message_id）⇒ 不是自发帧
        var reply = """
            {"message_id":"m-1","message_type":5,"method":"Seal","equipment_id":"501",
             "equipment_status":1,"method_status":2,"error":null,"data":{"ok":true}}
            """;
        ok("回包不是自发帧", Protocol.ParseSpontaneous(reply) == null);
        ok("回包 HasMessageId=true", Protocol.HasMessageId(reply));

        // ② 自发帧（无 message_id）⇒ 认得出设备码/事件类型/载荷
        var spontaneous = """
            {"message_type":6,"equipment_id":"ID-EXT1","event_type":"waiting_interaction",
             "data":{"station":"exchange"},"timestamp":"2026-11-15 07:40:00","checksum":"","connected":1}
            """;
        var sp = Protocol.ParseSpontaneous(spontaneous);
        ok("自发帧被认出来", sp != null);
        ok("设备码对", sp?.EquipmentId == "ID-EXT1", sp?.EquipmentId);
        ok("事件类型对", sp?.EventType == "waiting_interaction", sp?.EventType);
        ok("载荷原样带出", sp?.PayloadJson == "{\"station\":\"exchange\"}", sp?.PayloadJson);
        ok("自发帧 HasMessageId=false", !Protocol.HasMessageId(spontaneous));

        // ③ 没有 event_type ⇒ 按 message_type 归一类（不能丢）
        var noType = """
            {"message_type":6,"equipment_id":"ID-EXT1","data":{}}
            """;
        var sp2 = Protocol.ParseSpontaneous(noType);
        ok("缺 event_type 时按 message_type 归类", sp2?.EventType == "device_event_6", sp2?.EventType);
        ok("缺 data 时载荷退化成 {}", sp2?.PayloadJson == "{}", sp2?.PayloadJson);

        // ④ 没有设备码 ⇒ 认不出是谁报的 ⇒ 不发（发出去也没人认领）
        var noEquip = """
            {"message_type":6,"event_type":"waiting_interaction","data":{}}
            """;
        ok("没有设备码 ⇒ 不认领", Protocol.ParseSpontaneous(noEquip) == null);

        // ⑤ 垃圾输入不炸
        ok("空串安全", Protocol.ParseSpontaneous("") == null);
        ok("null 安全", Protocol.ParseSpontaneous(null!) == null);
        ok("非 JSON 安全", Protocol.ParseSpontaneous("not json at all") == null);
        ok("截断的 JSON 安全", Protocol.ParseSpontaneous("{\"equipment_id\":\"X\"") == null);

        // ⑥ 默认传输（串口/dll）拿不到自发帧 —— 接口默认值必须是不支持。
        //    注意：接口的默认实现只能**通过接口**调到（具体类型上看不到）。
        ITransport plain = new NullTransport();
        ok("ITransport 默认不支持自发帧",
            !plain.TryDequeueSpontaneous(out _));

        Console.WriteLine();
        Console.WriteLine("── 4. 按型号翻译（接真机时唯一要改的地方）──");
        var json = """
            {"message_type":6,"equipment_id":"ID-EXT1","event_type":"waiting_interaction",
             "data":{"station":"exchange"}}
            """;
        // 未登记的型号 ⇒ 回退通用 JSON（模拟器走的就是这条）
        var fallback = SpontaneousTranslators.For("no-such-model");
        var fb = fallback.Translate(json);
        ok("未登记型号回退通用 JSON 翻译器",
            fallback is JsonSpontaneousTranslator && fb?.EventType == "waiting_interaction");

        // 登记一个型号专属翻译器 ⇒ 它优先于通用的
        SpontaneousTranslators.Register(new SampleXmlSpontaneousTranslator());
        var xmlTranslator = SpontaneousTranslators.For("sample-xml");
        ok("登记后按型号取到专属翻译器",
            xmlTranslator is SampleXmlSpontaneousTranslator);

        // 模拟的 **XML 风格**厂商帧（真机协议还不知道，这是"样子"）
        var xmlFrame = """
            <DeviceFrame><EquipmentId>ID-ASPIN</EquipmentId>
            <EventType>plate_detected</EventType>
            <Payload><Slot>2</Slot><Plate>PL001</Plate></Payload></DeviceFrame>
            """;
        var xs = xmlTranslator.Translate(xmlFrame);
        ok("XML 风格帧：设备码对", xs?.EquipmentId == "ID-ASPIN", xs?.EquipmentId);
        ok("XML 风格帧：事件类型对", xs?.EventType == "plate_detected", xs?.EventType);
        ok("XML 风格帧：载荷转成 JSON",
            xs?.PayloadJson == "{\"Slot\":\"2\",\"Plate\":\"PL001\"}", xs?.PayloadJson);
        ok("XML 风格帧：不是 JSON 的通用翻译器认不出来的",
            JsonSpontaneousTranslator.Instance.Translate(xmlFrame) == null);

        // 模拟的 **二进制风格**厂商帧
        SpontaneousTranslators.Register(new SampleBinarySpontaneousTranslator());
        var binTranslator = SpontaneousTranslators.For("sample-binary");
        var payload = "ID-CELL1|slot=3";
        var bin = new List<byte> { 0xAA, 0xF0, 0x02, (byte)payload.Length };
        bin.AddRange(System.Text.Encoding.UTF8.GetBytes(payload));
        bin.Add(0xFF);
        var binFrame = Convert.ToBase64String(bin.ToArray());
        var bs = binTranslator.Translate(binFrame);
        ok("二进制风格帧：设备码对", bs?.EquipmentId == "ID-CELL1", bs?.EquipmentId);
        ok("二进制风格帧：事件码映射对", bs?.EventType == "plate_detected", bs?.EventType);
        ok("二进制风格帧：载荷转成 JSON",
            bs?.PayloadJson == "{\"detail\":\"slot=3\"}", bs?.PayloadJson);
        // 回包（不是自发帧）必须被拒
        var replyBin = new List<byte> { 0xAA, 0x02, 0x00, 0x00, 0xFF };
        ok("二进制风格帧：回包被拒（不是自发帧）",
            binTranslator.Translate(Convert.ToBase64String(replyBin.ToArray())) == null);
        ok("二进制风格帧：未知事件码不认领",
            binTranslator.Translate(Convert.ToBase64String(
                new List<byte> { 0xAA, 0xF0, 0x7F, 0x00, 0xFF }.ToArray())) == null);

        // 翻译器不得把脏数据变成异常
        ok("XML 翻译器：脏数据不抛异常",
            xmlTranslator.Translate("<<<not xml") == null);
        ok("二进制翻译器：脏数据不抛异常",
            binTranslator.Translate("not base64 at all!!") == null);
        ok("二进制翻译器：长度不够不抛异常",
            binTranslator.Translate(Convert.ToBase64String(new byte[] { 0xAA, 0xF0 })) == null);

        Console.WriteLine();
        Console.WriteLine($"通过 {_pass}, 失败 {_fail}");
        return _fail == 0 ? 0 : 1;
    }

    private static void ok(string what, bool cond, string? detail = null)
    {
        if (cond)
        {
            _pass++;
            Console.WriteLine($"  ✔ {what}");
        }
        else
        {
            _fail++;
            Console.WriteLine($"  ✘ {what}{(detail == null ? "" : "（" + detail + "）")}");
        }
    }

    /// <summary>只实现必需成员的传输（用来验接口默认值）。</summary>
    private sealed class NullTransport : ITransport
    {
        public string Name => "null";
        public TransportReply Send(Protocol.Request req, ModelLibrary.ModelDef model)
            => TransportReply.Success("{}");
        public void Dispose() { }
    }
}
