using System.Collections.Concurrent;
using LabOS.Driver.V1;

namespace LabOS.Sidecar.Services;

/// <summary>
/// 幂等缓存：commandId → 已成功的执行结果。
///
/// <para><b>必须是单例</b>。gRPC service 默认是 Scoped（每请求一个实例），
/// 把缓存放在 service 里会导致第二次请求读到空缓存，幂等直接失效——
/// 这个坑我在自测时踩到了（驱动侧收到 2 次同样指令）。</para>
/// </summary>
public sealed class IdempotentCache
{
    private readonly ConcurrentDictionary<string, CommandResult> _map = new();
    private readonly int _limit;

    public IdempotentCache(IConfiguration cfg)
    {
        _limit = Math.Max(16, cfg.GetValue("Sidecar:IdempotentCacheSize", 2000));
    }

    public bool TryGet(string commandId, out CommandResult? result) =>
        _map.TryGetValue(commandId, out result);

    public void Put(string commandId, CommandResult result)
    {
        _map[commandId] = result;

        if (_map.Count > _limit)
        {
            // 近似淘汰：清掉最早的一半（精确 LRU 需要额外结构，这里够用）
            foreach (string k in _map.Keys.Take(_limit / 2))
            {
                _map.TryRemove(k, out _);
            }
        }
    }

    public int Count => _map.Count;
}
