using System.Reflection;
using System.Runtime.InteropServices;

namespace Labos.Sidecar;

/// <summary>
/// **晚绑定（late-bound）COM** 的最小封装：给 BioTek LHC / Gen5 这类
/// "设备知识在厂商 COM 组件里"的仪器用。
///
/// <para><b>为什么用晚绑定，而不是加一个 Interop 程序集引用</b>：</para>
/// <list type="number">
///   <item>那些 Interop 程序集（<c>Interop.BTILHCRunner.dll</c> /
///         <c>Interop.Gen5.dll</c>）**只能在装了厂商软件的 Windows 上生成**
///         （tlbimp 要读注册表里的类型库）⇒ 开发机（macOS）根本编不过，
///         整个 sidecar 工程都会被这两台设备拖成"只能在工控机上构建"。</item>
///   <item>晚绑定不依赖编译期类型，**型号库改了不用重新生成 Interop**；
///         代价是方法名写错要到运行时才炸 —— 所以下面每个成员名都加了
///         与手册条款号的对照注释，并且 <see cref="Describe"/> 支持自检。</item>
/// </list>
///
/// <para><b>平台限制（必须诚实标注）</b>：COM 只在 Windows 可用。
/// 非 Windows 上 <see cref="Create"/> 直接返回失败，**不会假装能连**；
/// 开发机请用 <c>--transport sim</c>（转发 Java 模拟器）。</para>
///
/// <para><b>线程</b>：厂商组件不是线程安全的（LHC Runner 手册明确说要自己选线程模型，
/// Gen5 手册明确说读板期间不能并发调用）。所以本类内部串行化，
/// 与 <see cref="TcpServer"/> 的"同一条连接串行"叠加，恰好满足手册要求。</para>
/// </summary>
public sealed class ComDispatch : IDisposable
{
    private readonly object _com;
    private readonly string _progId;
    private readonly object _lock = new();

    private ComDispatch(object com, string progId)
    {
        _com = com;
        _progId = progId;
    }

    /// <summary>创建组件实例；失败返回 <c>(null, 原因)</c>，不抛。</summary>
    public static (ComDispatch? Com, string? Error) Create(string progId)
    {
        if (!OperatingSystem.IsWindows())
        {
            return (null, "COM 只在 Windows 可用（开发机请用 --transport sim）");
        }
#pragma warning disable CA1416 // Type.GetTypeFromProgID 只在 Windows 上有效，上一行已判
        var type = Type.GetTypeFromProgID(progId);
#pragma warning restore CA1416
        if (type == null)
        {
            return (null, $"注册表里没有 ProgID {progId}：厂商软件没装，或没注册过（先手工启动一次）");
        }
        try
        {
            var obj = Activator.CreateInstance(type);
            if (obj == null)
            {
                return (null, $"Activator 返回 null（{progId}）");
            }
            return (new ComDispatch(obj, progId), null);
        }
        catch (Exception e)
        {
            return (null, $"创建 {progId} 失败：{e.GetType().Name} {e.Message}");
        }
    }

    /// <summary>
    /// 包装一个已经拿到的子对象（Gen5 的 <c>Experiment</c> / <c>Plates</c> / <c>Plate</c> /
    /// <c>PlateReadMonitor</c>）。子对象与根对象**不同源**（不同接口），所以必须各自包装。
    /// </summary>
    public static ComDispatch Wrap(object? com, string label)
    {
        if (com == null)
        {
            throw new ComCallException(label, new InvalidOperationException("子对象为 null（上一步没成功）"));
        }
        return new ComDispatch(com, label);
    }

    /// <summary>ProgID / 子对象标签（日志里说清"连的是哪个对象"）。</summary>
    public string ProgId => _progId;

    /// <summary>底层 COM 对象（给需要拿子对象的传输层用，如 Gen5 的 Experiment）。</summary>
    public object Raw => _com;

    /// <summary>
    /// 调一个成员（方法或属性读）。失败抛 <see cref="ComCallException"/>，
    /// 由调用方翻成设备码 —— **这里不猜设备码**。
    /// </summary>
    public object? Call(string member, params object?[] args)
    {
        lock (_lock)
        {
            try
            {
                return _com.GetType().InvokeMember(
                    member,
                    BindingFlags.InvokeMethod | BindingFlags.GetProperty
                    | BindingFlags.Public | BindingFlags.Instance,
                    binder: null, target: _com, args: args);
            }
            catch (TargetInvocationException e) when (e.InnerException != null)
            {
                throw new ComCallException(member, e.InnerException);
            }
            catch (COMException e)
            {
                throw new ComCallException(member, e);
            }
        }
    }

    /// <summary>
    /// 写一个属性（如 Gen5 的 <c>DataExportEnabled</c>）。
    ///
    /// <para>为什么单独一个方法而不复用 <see cref="Call"/>：IDispatch 的
    /// Get/Set 标志位不同，混用会在某些组件上静默读回旧值。</para>
    /// </summary>
    public void Set(string member, object? value)
    {
        lock (_lock)
        {
            try
            {
                _com.GetType().InvokeMember(
                    member,
                    BindingFlags.SetProperty | BindingFlags.Public | BindingFlags.Instance,
                    binder: null, target: _com, args: new[] { value });
            }
            catch (TargetInvocationException e) when (e.InnerException != null)
            {
                throw new ComCallException(member, e.InnerException);
            }
            catch (COMException e)
            {
                throw new ComCallException(member, e);
            }
        }
    }

    /// <summary>调一个成员并转成 <see cref="short"/>（厂商的 nRetValue 都是 Int16）。</summary>
    public short CallShort(string member, params object?[] args)
    {
        var v = Call(member, args);
        return v == null ? (short)-1 : Convert.ToInt16(v);
    }

    /// <summary>调一个成员并转成 <see cref="int"/>。</summary>
    public int CallInt(string member, params object?[] args)
    {
        var v = Call(member, args);
        return v == null ? -1 : Convert.ToInt32(v);
    }

    /// <summary>调一个成员并转成字符串（<c>ref string</c> 形式的手册方法也走这里）。</summary>
    public string? CallString(string member, params object?[] args)
    {
        var v = Call(member, args);
        return v?.ToString();
    }

    /// <summary>
    /// 调一个带 <c>ref/out</c> 参数的方法，返回填好的值。
    ///
    /// <para>IDispatch 的 by-ref 参数会写回 <paramref name="args"/> 数组，
    /// 所以调用后直接读那个槽位即可（LHC 的 <c>LHC_GetProductName(ref string)</c>
    /// 是这种形状）。若某厂商组件不写回，这里返回 null 而不是猜一个值。</para>
    /// </summary>
    public string? CallWithRefString(string member)
    {
        var args = new object?[] { "" };
        Call(member, args);
        return args[0]?.ToString();
    }

    /// <summary>某个成员在这个组件上到底存不存在（自检/排障用，不用于业务判断）。</summary>
    public bool HasMember(string member)
    {
        lock (_lock)
        {
            try
            {
                _com.GetType().InvokeMember(member, BindingFlags.GetProperty, null, _com, null);
                return true;
            }
            catch (Exception)
            {
                // 属性读失败也可能只是"这个成员需要参数"；用反射再确认一次
                return _com.GetType().GetMember(member,
                    BindingFlags.Public | BindingFlags.Instance).Length > 0;
            }
        }
    }

    public void Dispose()
    {
        if (OperatingSystem.IsWindows() && Marshal.IsComObject(_com))
        {
            try { Marshal.FinalReleaseComObject(_com); } catch { /* 退出阶段，忽略 */ }
        }
    }
}

/// <summary>厂商 COM 组件调用失败（异常来自组件内部或 COM 层）。</summary>
public sealed class ComCallException : Exception
{
    public ComCallException(string member, Exception inner)
        : base($"{member} 调用失败：{inner.GetType().Name} {inner.Message}", inner)
    {
        Member = member;
        HResult = inner.HResult;
    }

    /// <summary>被调用的成员名（写进错误文本，便于对着手册查）。</summary>
    public string Member { get; }
}
