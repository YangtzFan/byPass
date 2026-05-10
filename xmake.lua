---@diagnostic disable: undefined-global, undefined-field

-- ============================================================================
-- RTL 生成：将 Chisel 源码编译为 SystemVerilog
-- IROM 指令内容不再在编译期加载，改为仿真运行时由 difftest 框架动态加载
-- ============================================================================
target("rtl", function()
    set_kind("phony")
    on_run(function()
        local build_dir = path.join("build", "rtl")

        local rtl_opts = {
            "-i",
            "chiselTemplate.runMain",
            "mycpu.GenerateVerilog",
            "--target",
            "systemverilog",
            "--split-verilog",
            "-td",
            build_dir
        }
        os.tryrm(build_dir)
        os.execv("mill", rtl_opts)
    end)
end)

target("comp", function()
    set_kind("phony")
    on_run(function()
        os.execv("mill", { "-i", "chiselTemplate.compile" })
    end)
end)

target("fmt", function()
    set_kind("phony")
    on_run(function()
        os.execv("mill", { "-i", "chiselTemplate.reformat" })
    end)
end)

target("clean", function()
    set_kind("phony")
    on_run(function()
        os.rmdir(path.join("build"))
    end)
end)

-- ============================================================================
-- yosys-sta 后端流程：综合 (Yosys) + 静态时序分析 (iSTA) + 功耗分析 (iPA)
-- 工具链与脚本位于 tools-backend/，等价于原仓库 Makefile 的 init/syn/sta/clean。
--
-- 默认参数（均可通过环境变量覆盖）：
--   DESIGN         = MyCPU                       顶层模块名
--   PDK            = icsprout55                  工艺库
--   CLK_PORT_NAME  = clock                       时钟端口（byPass 顶层为 clock）
--   CLK_FREQ_MHZ   = 500                         目标频率
--   SDC_FILE       = tools-backend/scripts/mycpu.sdc
--   RTL_FILES      = build/rtl/*.sv              空格分隔的 RTL 列表
--   O              = build/sta                   结果输出根目录
--
-- 用法：
--   xmake run sta-init     # 一次性下载 iEDA + sv2v + 克隆 icsprout55 PDK
--   xmake run sta-syn      # 仅跑 yosys 综合
--   xmake run sta          # 跑 sv2v + yosys + iSTA + iPA
--   xmake run sta-clean    # 清理 build/sta 与 tools-backend/result
-- ============================================================================

-- 工具链根目录（Lua 闭包内 path.join 需要绝对路径，通过 os.scriptdir() 获取）
local function _sta_tools_dir()
    return path.join(os.scriptdir(), "tools-backend")
end

-- 解析常用环境变量，返回综合 / STA 共用的参数表
local function _sta_params()
    local p = {}
    p.tools     = _sta_tools_dir()
    p.design    = os.getenv("DESIGN")        or "MyCPU"
    p.pdk       = os.getenv("PDK")           or "icsprout55"
    p.clk_port  = os.getenv("CLK_PORT_NAME") or "clock"
    p.clk_freq  = os.getenv("CLK_FREQ_MHZ")  or "500"
    p.sdc_file  = os.getenv("SDC_FILE")
                  or path.join(p.tools, "scripts", "mycpu.sdc")
    p.out_root  = os.getenv("O")
                  or path.join(os.scriptdir(), "build", "sta")
    p.result_dir = path.join(p.out_root, p.design .. "-" .. p.clk_freq .. "MHz")
    p.netlist    = path.join(p.result_dir, p.design .. ".netlist.v")

    -- RTL 文件列表：环境变量优先；否则取 build/rtl/*.sv
    local rtl_env = os.getenv("RTL_FILES")
    if rtl_env and rtl_env ~= "" then
        p.rtl_files = rtl_env
    else
        local files = os.files(path.join(os.scriptdir(), "build", "rtl", "*.sv"))
        p.rtl_files = table.concat(files, " ")
    end
    return p
end

-- 一次性环境初始化：下载预编译 iEDA + sv2v + 拉取 icsprout55 PDK
-- 这些大文件（iEDA ~35MB / sv2v ~10MB / PDK ~320MB）刻意不入仓，
-- 通过本 target 在本地工作区按需下载，避免膨胀 git 仓库体积。
target("sta-init", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        local tools = _sta_tools_dir()
        if not os.isdir(tools) then
            raise("tools-backend 目录不存在：" .. tools)
        end
        os.mkdir(path.join(tools, "bin"))
        os.mkdir(path.join(tools, "pdk"))

        -- 1) iEDA 预编译二进制（直接拉取 tar.bz2，避免上游脚本捎带下载不用的 nangate45 PDK，节省约 29MB 冗余文件）
        local ieda = path.join(tools, "bin", "iEDA")
        if not os.isfile(ieda) then
            cprint("${green underline}[INIT]${clear} 下载预编译 iEDA ...")
            os.execv("bash", {"-c", string.format(
                'set -e; cd "%s/bin" && ' ..
                'wget -q -O - https://ysyx.oscc.cc/slides/resources/archive/ieda.tar.bz2 | ' ..
                'tar xfj - && chmod +x iEDA',
                tools)})
        else
            cprint("${yellow underline}[INIT]${clear} iEDA 已存在，跳过下载")
        end

        -- 2) sv2v 预编译二进制（用于把 Chisel/firtool SV 转成 yosys 可读 V）
        local sv2v = path.join(tools, "bin", "sv2v")
        if not os.isfile(sv2v) then
            cprint("${green underline}[INIT]${clear} 下载 sv2v v0.0.13 ...")
            local sv2v_url = "https://github.com/zachjs/sv2v/releases/download/v0.0.13/sv2v-Linux.zip"
            os.execv("bash", {"-c", string.format(
                'set -e; cd "%s" && wget -q -O sv2v.zip "%s" && ' ..
                'unzip -o -q sv2v.zip && cp sv2v-Linux/sv2v bin/sv2v && ' ..
                'chmod +x bin/sv2v && rm -rf sv2v.zip sv2v-Linux',
                tools, sv2v_url)})
        else
            cprint("${yellow underline}[INIT]${clear} sv2v 已存在，跳过下载")
        end

        -- 3) icsprout55 PDK（55nm 标准单元 + liberty）
        local pdk = path.join(tools, "pdk", "icsprout55")
        if not os.isdir(pdk) then
            cprint("${green underline}[INIT]${clear} 克隆 icsprout55 PDK ...")
            os.execv("bash", {"-c", string.format(
                'cd "%s/pdk" && git clone -b ysyx --depth 1 ' ..
                'git@github.com:openecos-projects/icsprout55-pdk.git icsprout55',
                tools)})
        else
            cprint("${yellow underline}[INIT]${clear} icsprout55 PDK 已存在，跳过克隆")
        end

        -- 4) 自检
        cprint("${green underline}[INIT]${clear} 校验 iEDA ...")
        os.execv("bash", {"-c", string.format('cd "%s" && echo exit | ./bin/iEDA -v', tools)})
        cprint("${green underline}[INIT]${clear} 完成。可以执行 `xmake run sta`")
    end)
end)

-- 仅跑 yosys 综合，产出 <DESIGN>.netlist.v / synth_stat.txt / synth_check.txt
target("sta-syn", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        local p = _sta_params()
        if p.rtl_files == "" then
            raise("找不到 RTL 文件，请先 `xmake run rtl` 或显式设置 RTL_FILES")
        end
        os.mkdir(p.result_dir)

        -- 1) sv2v 预处理：把 Chisel 输出的现代 SV 转成 yosys 友好的 Verilog
        --    （Chisel/firtool 生成的 always_ff 内 automatic logic 等结构 yosys 不支持）
        --    `--top=$DESIGN` 让 sv2v 做死代码剔除，丢掉没被顶层引用的模块，
        --    显著缩短后续 yosys 综合时间。
        local sv2v = path.join(p.tools, "bin", "sv2v")
        local conv_v = path.join(p.result_dir, p.design .. ".sv2v.v")
        if not os.isfile(sv2v) then
            raise("未找到 sv2v: " .. sv2v .. "，请先执行 `xmake run sta-init`")
        end
        local sv2v_cmd = string.format("%s --top=%s -w %s %s",
                                       sv2v, p.design, conv_v, p.rtl_files)
        cprint("${green underline}[SV2V]${clear} %s -> %s", sv2v, conv_v)
        os.execv("bash", {"-c", sv2v_cmd})

        -- 2) yosys 综合：
        --    去掉 `-l yosys.log` 改走管道过滤，把每个 OPT_EXPR/AUTONAME 大量
        --    `Replacing $_..._ cell` / `Rename cell $abc$` / `Optimizing module`
        --    这类逐 cell 噪声过滤掉，避免 yosys.log 膨胀到 GB 级。
        local yosys_tcl = path.join(p.tools, "scripts", "yosys.tcl")
        local yosys_log = path.join(p.result_dir, "yosys.log")
        -- 过滤 yosys 大量逐 cell 噪声日志（OPT_EXPR / OPT_CLEAN / AUTONAME / ABC mapping 等）
        local filter = [==[grep -vE -e '^[[:space:]]*(removing unused|Removing [`\$]|Cell `\$|Optimizing away|Root of a mux tree|Redirecting output|Replacing [`\$]|Replacing \$_|created `?\$|creating `?\$|eval [`\$]|Found cells that share|Constant input on bit|Considering [`\$]|Creating register for signal|Adding SRST signal|Rename (cell|wire) \$|Removing wire |Removed [0-9]+|Optimizing module |rejecting switch:|[0-9]+/[0-9]+:)' -e '^Mapping (MyCPU\.|cell )' -e '^[[:space:]]*$' -e '^[[:space:]]+\\']==]
        local cmd = string.format(
            'export CLK_FREQ_MHZ=%s CLK_PORT_NAME=%s; ' ..
            'set -o pipefail; echo tcl %s %s %s "%s" %s | yosys -g -s - 2>&1 | %s | tee %s >/dev/null',
            p.clk_freq, p.clk_port,
            yosys_tcl, p.design, p.pdk, conv_v, p.netlist, filter, yosys_log)
        cprint("${green underline}[SYN]${clear} (log -> %s)", yosys_log)
        os.execv("bash", {"-c", cmd})
    end)
end)

-- 完整后端：先综合，再用 iSTA/iPA 跑时序与功耗
target("sta", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        local p = _sta_params()
        local sta_tcl = path.join(p.tools, "scripts", "sta.tcl")
        local ieda    = path.join(p.tools, "bin", "iEDA")
        local sv2v    = path.join(p.tools, "bin", "sv2v")

        if not os.isfile(ieda) then
            raise("未找到 iEDA: " .. ieda .. "，请先执行 `xmake run sta-init`")
        end
        if not os.isfile(sv2v) then
            raise("未找到 sv2v: " .. sv2v ..
                  "，请先执行 `xmake run sta-init`")
        end
        if not os.isfile(p.sdc_file) then
            raise("SDC 文件不存在：" .. p.sdc_file)
        end
        if p.rtl_files == "" then
            raise("找不到 RTL 文件，请先 `xmake run rtl` 或显式设置 RTL_FILES")
        end

        os.mkdir(p.result_dir)
        local env_prefix = string.format("export CLK_FREQ_MHZ=%s CLK_PORT_NAME=%s; ",
                                         p.clk_freq, p.clk_port)

        -- 1) sv2v 预处理 + 死代码剔除
        local conv_v = path.join(p.result_dir, p.design .. ".sv2v.v")
        local sv2v_cmd = string.format("%s --top=%s -w %s %s",
                                       sv2v, p.design, conv_v, p.rtl_files)
        cprint("${green underline}[SV2V]${clear} %s -> %s", sv2v, conv_v)
        os.execv("bash", {"-c", sv2v_cmd})

        -- 2) yosys 综合（管道过滤大量逐 cell 噪声日志）
        local yosys_tcl = path.join(p.tools, "scripts", "yosys.tcl")
        local yosys_log = path.join(p.result_dir, "yosys.log")
        local filter = [==[grep -vE -e '^[[:space:]]*(removing unused|Removing [`\$]|Cell `\$|Optimizing away|Root of a mux tree|Redirecting output|Replacing [`\$]|Replacing \$_|created `?\$|creating `?\$|eval [`\$]|Found cells that share|Constant input on bit|Considering [`\$]|Creating register for signal|Adding SRST signal|Rename (cell|wire) \$|Removing wire |Removed [0-9]+|Optimizing module |rejecting switch:|[0-9]+/[0-9]+:)' -e '^Mapping (MyCPU\.|cell )' -e '^[[:space:]]*$' -e '^[[:space:]]+\\']==]
        local syn_cmd = env_prefix .. string.format(
            'set -o pipefail; echo tcl %s %s %s "%s" %s | yosys -g -s - 2>&1 | %s | tee %s >/dev/null',
            yosys_tcl, p.design, p.pdk, conv_v, p.netlist, filter, yosys_log)
        cprint("${green underline}[SYN]${clear} (log -> %s)", yosys_log)
        os.execv("bash", {"-c", syn_cmd})

        -- 3) 再跑 iSTA + iPA
        local sta_log = path.join(p.result_dir, "sta.log")
        local sta_cmd = env_prefix .. string.format(
            'set -o pipefail && %s -script %s %s %s %s %s 2>&1 | tee %s',
            ieda, sta_tcl, p.sdc_file, p.netlist, p.design, p.pdk, sta_log)
        cprint("${green underline}[STA]${clear} %s", sta_cmd)
        os.execv("bash", {"-c", sta_cmd})

        -- 4) 后处理：iEDA iPA 在大型设计上对约 ~10% 组合 cell 的内部功耗会
        -- 因 slew 未传播给出 1e+150 数量级 garbage，导致 MyCPU.pwr 的总功耗
        -- 失真。clean_power.py 过滤 garbage cell，输出 MyCPU.pwr.clean。
        local cleaner = path.join(p.tools, "scripts", "clean_power.py")
        if os.isfile(cleaner) then
            cprint("${green underline}[PWR-CLEAN]${clear} %s %s", cleaner, p.result_dir)
            os.execv("bash", {"-c", string.format('python3 "%s" "%s"', cleaner, p.result_dir)})
        end

        cprint("${green underline}[DONE]${clear} 报告位于 %s", p.result_dir)
    end)
end)

-- 清理后端产物（不影响 RTL / Chisel 构建）
target("sta-clean", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        os.rmdir(path.join(os.scriptdir(), "build", "sta"))
        os.rmdir(path.join(_sta_tools_dir(), "result"))
        cprint("${green underline}[INFO]${clear} 已清理 build/sta 与 tools-backend/result")
    end)
end)

target("view", function () 
    set_kind("phony")
    on_run(function ()
        local infiles = os.files("build/rtl/*.sv")
        local outfiles = {}

        for _, file in ipairs(infiles) do
            table.insert(outfiles, path.absolute(file))
        end

        os.mkdir("build")
        io.writefile("build/rtl/filelist.f",
            #outfiles > 0 and (table.concat(outfiles, "\n") .. "\n") or "")

        cprint("${green underline}[INFO]${clear} generated: build/rtl/filelist.f")
        os.exec("hier-viewer -f build/rtl/filelist.f --preview --preview-host 0.0.0.0 --output out/")
    end)
end)

-- ============================================================================
-- 初始化子模块
-- ============================================================================
target("init", function()
    set_kind("phony")
    set_default(false)
    local function isempty(v)
        return v == nil or v == ""
    end
    local default_proxy = os.getenv("default_proxy_LAN")
    local http          = os.getenv("http_proxy")
    local https         = os.getenv("https_proxy")
    local isProxyEmpty  = isempty(http) or isempty(https)
    local autoSetProxy  = false

    before_run(function() -- Check whether the environment variables about system proxy is OK or not.
        if (isProxyEmpty) then
            cprint("${yellow underline}[WARNING]${clear} http_proxy and https_proxy have not been set.")
            if (isempty(default_proxy)) then
                cprint("${red underline}[SEVERE]${clear} There are no proxy set. Initialization operation failed.")
                local msg = format("Initialization failed")
                raise(msg)
            else
                autoSetProxy = true
            end
        end
    end)

    on_run(function()
        if (autoSetProxy) then
            local envs          = {}
            envs["http_proxy"]  = default_proxy
            envs["https_proxy"] = default_proxy
            os.addenvs(envs)
            cprint("${green underline}[INFO]${clear} Default proxy has been set. Proxy has been configured automatically.")
        end
        cprint("${green underline}[INFO]${clear} Updating submodules in this repo... This may take a few seconds.")
        os.cd(os.scriptdir())
        os.exec("git submodule update --init")
    end)
end)
