package com.labos.kernel.sim;

import com.labos.kernel.device.DeviceModel;
import com.labos.kernel.device.DeviceModels;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 桌面版设备模拟器：**一台设备一个窗口**。
 *
 * <p><b>为什么做成桌面程序</b>：真实现场每台设备都有一套厂商的控制软件
 * （离心机有面板、酶标仪有软件、机械臂有示教器）。中控对接的是"一台设备
 * 一个端点"。模拟器如果只是后台进程，我们测不到"设备侧现在是什么状态"
 * ——比如门开着还是关着、离心机转没转、机械臂手里有没有板。
 *
 * <p>做成窗口之后：
 * <ul>
 *   <li><b>设备面板按类型不同</b>：离心机给转速/时间/温度，酶标仪给波长，
 *       机械臂给点位表 + 抓/放，孵育箱给温度/CO2 + 层位格子</li>
 *   <li><b>能在界面上直接设 IP 和端口</b>——多网卡机器上可以指定绑定到哪块网卡，
 *       也可以在一台机器上用 127.0.0.2/3/4… 起多个"不同地址的设备"</li>
 *   <li><b>日志</b>：收到的每条命令、校验拒绝原因、客户端接入断开</li>
 *   <li><b>连接状态</b>：监听中/已停止、当前客户端数、累计命令数与失败数</li>
 *   <li><b>手动操作</b>：窗口上的按钮相当于"现场的人按了设备按钮"，
 *       可以用来制造中控没预料到的场景（比如运行中突然开门）</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   java -cp out com.labos.kernel.sim.SimulatorApp \
 *        --profile centrifuge --code CENT1 --device 214 --port 19502
 * </pre>
 */
public final class SimulatorApp {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DeviceCore core;
    private final JFrame frame;

    private final JTextField hostField;
    private final JTextField portField;
    private final JButton startBtn = new JButton("启动监听");
    private final JButton stopBtn = new JButton("停止");
    private final JLabel light = new JLabel("●");
    private final JLabel statLabel = new JLabel();
    private final JTextArea logArea = new JTextArea(10, 80);

    // 设备面板里需要随状态刷新的控件
    private JLabel doorLabel;
    private JLabel holdingLabel;
    private JLabel runningLabel;
    private JLabel faultLabel;
    private JLabel s100StateLabel;

    public SimulatorApp(DeviceCore core, String host, int port) {
        this.core = core;
        this.hostField = new JTextField(host, 12);
        this.portField = new JTextField(String.valueOf(port), 6);

        DeviceModel m = core.modelInfo();
        String title = "模拟器 · " + typeName(core.snapshot().get("profile").toString())
                + (m == null ? "" : " · " + m.vendor + " " + m.model)
                + " · " + core.code() + "（设备 " + core.deviceId() + "）";
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildNetworkPanel());
        top.add(buildDevicePanel());
        frame.add(top, BorderLayout.NORTH);

        frame.add(buildLogPanel(), BorderLayout.CENTER);
        frame.add(buildFooter(), BorderLayout.SOUTH);

        core.setListener(new DeviceCore.Listener() {
            @Override
            public void onLog(String line) {
                appendLog(line);
            }

            @Override
            public void onStateChange() {
                SwingUtilities.invokeLater(SimulatorApp.this::refreshStatus);
            }
        });

        startBtn.addActionListener(e -> doStart());
        stopBtn.addActionListener(e -> {
            core.stopServer();
            refreshStatus();
        });

        refreshStatus();
        // 命令行指定了端口就自动开始监听（自动化/批量启动时不用手点）；
        // 不指定端口则停在"未监听"，由用户在界面上填地址再点启动
        // —— 这和真实设备"先上电、再连线"的顺序一致。
        if (port > 0) {
            SwingUtilities.invokeLater(() -> {
                doStart();
                appendLog("已按启动参数自动监听。改地址请先点「停止」。");
            });
        } else {
            appendLog("窗口就绪。填好地址后点「启动监听」。");
        }
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    // ---------------------------------------------------------------- 网络面板

    private JPanel buildNetworkPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        p.setBorder(new TitledBorder("网络（中控通过这个地址连进来）"));

        p.add(new JLabel("绑定地址"));
        hostField.setToolTipText("0.0.0.0 = 所有网卡；127.0.0.1 = 只本机；"
                + "多网卡或多环回地址时可填具体 IP");
        p.add(hostField);

        p.add(new JLabel("端口"));
        p.add(portField);

        p.add(startBtn);
        p.add(stopBtn);

        p.add(Box.createHorizontalStrut(16));
        light.setFont(light.getFont().deriveFont(Font.BOLD, 16f));
        p.add(light);
        p.add(statLabel);
        return p;
    }

    private void doStart() {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            String host = hostField.getText().trim();
            core.startServer(host, port);
            appendLog("已启动：" + host + ":" + port);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(frame, "端口必须是数字", "参数错误",
                    JOptionPane.ERROR_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame,
                    "启动失败：" + ex.getMessage() + "\n\n端口被占用就换一个；"
                            + "地址无效就改成 0.0.0.0",
                    "启动失败", JOptionPane.ERROR_MESSAGE);
        }
        refreshStatus();
    }

    private void refreshStatus() {
        var s = core.snapshot();
        boolean on = Boolean.TRUE.equals(s.get("listening"));
        light.setForeground(on ? new Color(0, 160, 0) : new Color(170, 170, 170));
        statLabel.setText((on ? "监听中" : "未监听")
                + "   客户端 " + s.get("clients")
                + "   命令 " + s.get("received")
                + "   失败 " + s.get("failed"));
        startBtn.setEnabled(!on);
        stopBtn.setEnabled(on);
        hostField.setEnabled(!on);
        portField.setEnabled(!on);

        if (doorLabel != null) {
            doorLabel.setText(Boolean.TRUE.equals(s.get("doorOpen")) ? "开" : "关");
            doorLabel.setForeground(Boolean.TRUE.equals(s.get("doorOpen"))
                    ? new Color(200, 120, 0) : new Color(0, 130, 0));
        }
        if (holdingLabel != null) {
            holdingLabel.setText(Boolean.TRUE.equals(s.get("holding")) ? "有板" : "空");
            holdingLabel.setForeground(Boolean.TRUE.equals(s.get("holding"))
                    ? new Color(200, 120, 0) : new Color(110, 110, 110));
        }
        if (runningLabel != null) {
            runningLabel.setText(Boolean.TRUE.equals(s.get("running")) ? "运行中" : "空闲");
            runningLabel.setForeground(Boolean.TRUE.equals(s.get("running"))
                    ? new Color(0, 110, 200) : new Color(110, 110, 110));
        }
        if (faultLabel != null) {
            Object f = s.get("latchedFault");
            faultLabel.setText(f == null ? "无" : String.valueOf(f));
            faultLabel.setForeground(f == null ? new Color(0, 130, 0) : new Color(190, 40, 40));
        }
        if (s100StateLabel != null && s.get("state") instanceof java.util.Map<?, ?> st) {
            Object tray = st.get("trayPos");
            Object type = st.get("plateType");
            boolean onOrigin = tray != null && "0".equals(String.valueOf(tray));
            s100StateLabel.setText((onOrigin ? "原点" : "位置 " + tray)
                    + "  板型 " + type);
            s100StateLabel.setForeground(onOrigin ? new Color(0, 130, 0) : new Color(200, 120, 0));
        }
    }

    // ---------------------------------------------------------------- 设备面板

    private JPanel buildDevicePanel() {
        String profile = core.snapshot().get("profile").toString();
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new TitledBorder("设备面板 · " + typeName(profile)));

        // 型号行：有型号时把它是什么机器、依据什么手册摆在最上面。
        // 现场最常问的两个问题就是"这是哪台"和"照哪份手册"。
        DeviceModel model = core.modelInfo();
        if (model != null) {
            JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
            head.add(new JLabel("型号：" + model.vendor + " " + model.model
                    + "（" + model.id + "）"));
            head.add(new JLabel("协议：" + model.protocol));
            head.add(new JLabel("依据：" + switch (model.evidence) {
                case MANUAL -> "手册（可对接真机）";
                case SPEC -> "公开规格（指令名待核对）";
                case PLANNED -> "仅型号（不能对接）";
            }));
            if (model.doorCount > 0) {
                head.add(new JLabel("门数：" + model.doorCount));
            }
            if (model.manual != null) {
                head.setToolTipText("手册：" + model.manual
                        + (model.note == null ? "" : "\n注意：" + model.note));
            }
            outer.add(head, BorderLayout.NORTH);
        }

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        if (model != null && "s100".equals(model.id)) {
            buildS100(p);
        } else if (model != null && "aspin-2k".equals(model.id)) {
            buildCentrifuge(p);
            p.add(Box.createHorizontalStrut(12));
            p.add(new JLabel("门 A/B 由 ControlCover(1/2) 控制；Init 未执行时动作会被拒绝"));
        } else {
            switch (profile) {
                case "robot" -> buildRobot(p);
                case "centrifuge" -> buildCentrifuge(p);
                case "reader" -> buildReader(p);
                case "incubator", "freezer", "plate_hotel" -> buildChamber(p, profile);
                case "liquid_handler", "dispenser", "washer" -> buildLiquid(p, profile);
                case "thermocycler", "shaker", "sealer", "peeler" -> buildProgram(p, profile);
                default -> p.add(new JLabel("该方法集无专用面板，用下方方法列表"));
            }
        }
        if (model != null && !model.errorTable().isEmpty()) {
            p.add(Box.createHorizontalStrut(18));
            p.add(buildFaultInjector(model));
        }
        outer.add(p, BorderLayout.CENTER);
        return outer;
    }

    /**
     * 故障注入器：**按手册上的错误码**制造一次故障。
     *
     * <p>这是模拟器最实用的一栏——现场报"无纸"，你不用等它真的没纸，
     * 选 {@code 0x8000000C} 点一下，就能验证中控的告警、恢复策略、
     * 以及"换纸后按清除故障能不能继续"。
     */
    private JPanel buildFaultInjector(DeviceModel model) {
        JPanel box = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        box.setBorder(new TitledBorder("制造故障"));
        JComboBox<String> codes = new JComboBox<>();
        for (DeviceModel.ErrorDef e : model.errorTable().values()) {
            codes.addItem(e.vendorCode() + " " + e.meaning() + " [" + e.disposition() + "]");
        }
        codes.setPrototypeDisplayValue("0x8000000C 未检测到孔板 [MANUAL]");
        box.add(codes);

        JButton fire = new JButton("触发一次");
        fire.setToolTipText("下一条动作类命令会返回这个错误码（真机通常是锁存的，"
                + "要 ClearFault/Reset 才能继续）");
        fire.addActionListener(e -> {
            String sel = (String) codes.getSelectedItem();
            if (sel == null) {
                return;
            }
            String code = sel.split(" ")[0];
            core.injectNextFailure(code);
            refreshStatus();
        });
        box.add(fire);

        JButton clear = new JButton("清除故障");
        clear.addActionListener(e -> {
            core.clearLatchedFault();
            refreshStatus();
        });
        box.add(clear);

        faultLabel = new JLabel("无");
        faultLabel.setFont(faultLabel.getFont().deriveFont(Font.BOLD));
        box.add(new JLabel("  锁存:"));
        box.add(faultLabel);
        return box;
    }

    /** 机械臂：点位表 + 抓/放。 */
    private void buildRobot(JPanel p) {
        JTextArea pts = new JTextArea("""
                L1  (320, 140, 45)
                L2  (320, 180, 45)
                L3  (320, 220, 45)
                L4  (320, 260, 45)""", 4, 20);
        pts.setEditable(false);
        pts.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(new JScrollPane(pts));

        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("手持:"));
        holdingLabel = new JLabel("空");
        holdingLabel.setFont(holdingLabel.getFont().deriveFont(Font.BOLD));
        p.add(holdingLabel);

        p.add(Box.createHorizontalStrut(8));
        JButton grip = new JButton("抓板(手动)");
        grip.addActionListener(e -> { core.manualToggleHolding(true); refreshStatus(); });
        JButton rel = new JButton("放板(手动)");
        rel.addActionListener(e -> { core.manualToggleHolding(false); refreshStatus(); });
        p.add(grip);
        p.add(rel);
    }

    /** 离心机：门 + 转速/时间/温度 + 启停。 */
    private void buildCentrifuge(JPanel p) {
        p.add(new JLabel("门:"));
        doorLabel = new JLabel("关");
        doorLabel.setFont(doorLabel.getFont().deriveFont(Font.BOLD));
        p.add(doorLabel);
        // 有型号时走型号行为（ASpin 是 A/B 两扇门），否则只翻通用标志位
        boolean modeled = core.modelInfo() != null;
        JButton open = new JButton(modeled ? "开 A 门" : "开门");
        open.addActionListener(e -> {
            if (modeled) {
                core.manualModelCommand("OpenDoorA", null);
            } else {
                core.manualOpenDoor();
            }
            refreshStatus();
        });
        JButton close = new JButton("关门");
        close.addActionListener(e -> {
            if (modeled) {
                core.manualModelCommand("CloseDoor", null);
            } else {
                core.manualCloseDoor();
            }
            refreshStatus();
        });
        p.add(open);
        p.add(close);
        if (modeled) {
            JButton openB = new JButton("开 B 门");
            openB.addActionListener(e -> {
                core.manualModelCommand("OpenDoorB", null);
                refreshStatus();
            });
            p.add(openB);
        }

        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("转速"));
        p.add(new JTextField("12000", 6));
        p.add(new JLabel("rpm (200–14000)"));
        p.add(new JLabel("时间"));
        p.add(new JTextField("5", 3));
        p.add(new JLabel("分"));
        p.add(new JLabel("温度"));
        p.add(new JTextField("4", 3));
        p.add(new JLabel("°C (-9~40)"));

        p.add(Box.createHorizontalStrut(12));
        runningLabel = new JLabel("空闲");
        runningLabel.setFont(runningLabel.getFont().deriveFont(Font.BOLD));
        p.add(runningLabel);
        JButton run = new JButton("启动离心(手动)");
        run.addActionListener(e -> { core.manualToggleRunning(true); refreshStatus(); });
        JButton stop = new JButton("停止");
        stop.addActionListener(e -> { core.manualToggleRunning(false); refreshStatus(); });
        p.add(run);
        p.add(stop);
    }

    /** 酶标仪：门 + 波长。 */
    private void buildReader(JPanel p) {
        p.add(new JLabel("门:"));
        doorLabel = new JLabel("关");
        doorLabel.setFont(doorLabel.getFont().deriveFont(Font.BOLD));
        p.add(doorLabel);
        JButton open = new JButton("开门");
        open.addActionListener(e -> { core.manualOpenDoor(); refreshStatus(); });
        JButton close = new JButton("关门");
        close.addActionListener(e -> { core.manualCloseDoor(); refreshStatus(); });
        p.add(open);
        p.add(close);

        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("波长"));
        p.add(new JTextField("450", 5));
        p.add(new JLabel("nm (190–850)"));
        p.add(Box.createHorizontalStrut(8));
        p.add(new JLabel("检测模式"));
        JComboBox<String> mode = new JComboBox<>(new String[]{"吸收", "荧光", "发光", "时间分辨"});
        p.add(mode);
    }

    /** 孵育箱 / 低温存储 / 板库：温控 + 层位格子。 */
    private void buildChamber(JPanel p, String profile) {
        p.add(new JLabel("温度"));
        p.add(new JTextField(profile.equals("freezer") ? "-80" : "37", 4));
        p.add(new JLabel("°C"));
        if (!profile.equals("freezer")) {
            p.add(new JLabel("  CO2"));
            p.add(new JTextField("5", 3));
            p.add(new JLabel("%"));
        }

        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("层位:"));
        JPanel slots = new JPanel(new GridLayout(2, 5, 3, 3));
        for (int i = 1; i <= 10; i++) {
            JLabel cell = new JLabel("L" + i, SwingConstants.CENTER);
            cell.setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180)));
            cell.setOpaque(true);
            cell.setBackground(new Color(245, 245, 245));
            cell.setPreferredSize(new Dimension(38, 26));
            slots.add(cell);
        }
        p.add(slots);
    }

    /** 移液 / 分液 / 洗板：量程 + 体积。 */
    private void buildLiquid(JPanel p, String profile) {
        p.add(new JLabel("体积"));
        p.add(new JTextField("100", 6));
        p.add(new JLabel("µL"));
        p.add(Box.createHorizontalStrut(8));
        if (profile.equals("washer")) {
            p.add(new JLabel("次数"));
            p.add(new JTextField("3", 3));
            p.add(new JLabel("循环"));
        } else {
            p.add(new JLabel("通道"));
            JComboBox<String> ch = new JComboBox<>(new String[]{"8", "96", "384"});
            p.add(ch);
        }
        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("枪头:"));
        p.add(new JLabel("未装"));
    }

    /** PCR / 摇床 / 封膜 / 撕膜：程序参数。 */
    private void buildProgram(JPanel p, String profile) {
        switch (profile) {
            case "thermocycler" -> {
                p.add(new JLabel("程序"));
                p.add(new JTextField("STD-40cycle", 14));
                p.add(new JLabel("  温度"));
                p.add(new JTextField("95", 4));
                p.add(new JLabel("°C (4–100)"));
            }
            case "shaker" -> {
                p.add(new JLabel("转速"));
                p.add(new JTextField("800", 5));
                p.add(new JLabel("rpm (0–3000)  时间"));
                p.add(new JTextField("10", 3));
                p.add(new JLabel("分"));
            }
            case "sealer" -> {
                p.add(new JLabel("温度"));
                p.add(new JTextField("165", 4));
                p.add(new JLabel("°C (100–200)  时间"));
                p.add(new JTextField("3.5", 3));
                p.add(new JLabel("秒"));
            }
            default -> p.add(new JLabel("撕膜参数：速度/位置"));
        }
    }

    /**
     * S100 封膜机面板：托盘位置 / 板型 + 手动挪托盘。
     *
     * <p>挪托盘不是玩具按钮：{@code 0x8000001A}（托盘不在原点）是**真实会发生的**
     * 故障，现场最常见的成因就是上一步动作没回原点。有了这个按钮，
     * 不用等现场出事就能验证"中控流程里到底有没有回原点这一步"。
     */
    private void buildS100(JPanel p) {
        p.add(new JLabel("托盘"));
        s100StateLabel = new JLabel("原点  板型 1");
        s100StateLabel.setFont(s100StateLabel.getFont().deriveFont(Font.BOLD));
        p.add(s100StateLabel);

        JButton home = new JButton("回原点(0x0C=0)");
        home.addActionListener(e -> {
            core.manualModelCommand("MoveTray", "\"value\":0");
            refreshStatus();
        });
        p.add(home);

        JButton move = new JButton("托盘移到 10000");
        move.addActionListener(e -> {
            core.manualModelCommand("MoveTray", "\"value\":10000");
            refreshStatus();
        });
        p.add(move);

        p.add(Box.createHorizontalStrut(12));
        p.add(new JLabel("托盘不在原点时封膜会回 0x8000001A"));
    }

    // ---------------------------------------------------------------- 日志与页脚

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder("日志（收到 / 拒绝 / 连接）"));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(true);
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton clear = new JButton("清空日志");
        clear.addActionListener(e -> logArea.setText(""));
        p.add(clear);

        p.add(new JLabel("|  支持的方法："));
        JLabel methods = new JLabel(String.join(" / ",
                core.snapshot().get("methods").toString()
                        .replace("[", "").replace("]", "").split(", ")));
        methods.setForeground(new Color(90, 90, 90));
        p.add(methods);
        return p;
    }

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(LocalTime.now().format(TS) + "  " + line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private static String typeName(String profile) {
        return switch (profile) {
            case "robot" -> "机械臂";
            case "liquid_handler" -> "移液工作站";
            case "centrifuge" -> "离心机";
            case "reader" -> "酶标仪";
            case "incubator" -> "孵育箱";
            case "washer" -> "洗板机";
            case "dispenser" -> "分液器";
            case "plate_hotel" -> "板存储";
            case "sealer" -> "封膜机";
            case "peeler" -> "撕膜机";
            case "barcode" -> "条码扫描";
            case "thermocycler" -> "PCR 仪";
            case "shaker" -> "摇床";
            case "freezer" -> "低温存储";
            default -> profile;
        };
    }

    // ---------------------------------------------------------------- 入口

    public static void main(String[] args) throws Exception {
        int port = 19500;
        String device = "0";
        String code = "SIM";
        String cap = "-";
        String host = "0.0.0.0";
        String profile = "generic";
        String modelId = null;
        long delay = -1;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--device" -> device = args[++i];
                case "--code" -> code = args[++i];
                case "--cap" -> cap = args[++i];
                case "--profile" -> profile = args[++i];
                case "--model" -> modelId = args[++i];
                case "--delay" -> delay = Long.parseLong(args[++i]);
                default -> { }
            }
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面（headless）。"
                    + "用命令行版：com.labos.kernel.sim.DeviceSimulator");
            System.exit(2);
        }

        // 中文显示
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Font ui = new Font("PingFang SC", Font.PLAIN, 13);
        UIManager.put("Label.font", ui);
        UIManager.put("Button.font", ui);
        UIManager.put("TextField.font", ui);
        UIManager.put("ComboBox.font", ui);
        UIManager.put("TitledBorder.font", ui);
        UIManager.put("TextArea.font", new Font(Font.MONOSPACED, Font.PLAIN, 12));

        DeviceModel model = DeviceModels.of(modelId);
        if (modelId != null && model == null) {
            System.err.println("未知型号 " + modelId + "，可用：" + DeviceModels.knownIds());
            System.exit(2);
        }
        if (model != null) {
            profile = model.type;
        }

        DeviceCore core = new DeviceCore(port, device, code, cap,
                delay, false, 0, "EC-05-0001", false, profile, model);

        final String fHost = host;
        final int fPort = port;
        SwingUtilities.invokeLater(() -> new SimulatorApp(core, fHost, fPort));
    }

    private SimulatorApp() {
        throw new UnsupportedOperationException();
    }
}
